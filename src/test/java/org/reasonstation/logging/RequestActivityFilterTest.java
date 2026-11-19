package org.reasonstation.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.core.status.Status;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ForwardedHeaderFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class RequestActivityFilterTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger("org.reasonstation.logging.RequestActivity");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final RequestActivityFilter filter = new RequestActivityFilter("10.0.0.0/24,10.0.1.0/24");

    @BeforeEach
    void setUp() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
        MDC.remove("requestId");
    }

    @Test
    void recordsSearchAndResponseWithServerGeneratedCorrelationId() throws Exception {
        var request = request("/api/discovery/search");
        request.setQueryString("query=drum+%22bass%22%0Anew&page=2&token=secret");
        request.addHeader("X-Request-ID", "untrusted");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            assertEquals(response.getHeader("X-Request-ID"), MDC.get("requestId"));
            assertEquals(MDC.get("requestId"), req.getAttribute("requestId"));
            ((jakarta.servlet.http.HttpServletResponse) res).setStatus(400);
        });

        JsonNode event = event();
        assertEquals("http_request", event.path("event").asText());
        assertEquals("GET", event.path("method").asText());
        assertEquals("/api/discovery/search", event.path("path").asText());
        assertEquals("198.51.100.42", event.path("clientIp").asText());
        assertEquals(400, event.path("status").asInt());
        assertTrue(event.path("durationMs").asLong() >= 0);
        assertFalse(event.path("timestamp").asText().isBlank());
        assertEquals("drum \"bass\"\nnew", event.path("parameters").path("query").asText());
        assertEquals("2", event.path("parameters").path("page").asText());
        assertFalse(event.path("parameters").has("token"));
        assertFalse(appender.list.getFirst().getFormattedMessage().contains("\n"));
        assertNotEquals("untrusted", event.path("requestId").asText());
        assertEquals(response.getHeader("X-Request-ID"), event.path("requestId").asText());
        assertNull(MDC.get("requestId"));
    }

    @Test
    void usesAlbAppendedAddressEvenAfterFrameworkForwardingAndSpoofedHeaders() throws Exception {
        var request = request("/api/discovery/search");
        request.setRemoteAddr("10.0.1.20");
        request.addHeader("X-Forwarded-For", "127.0.0.1, 203.0.113.25");
        request.addHeader("Forwarded", "for=127.0.0.1;proto=https;host=example.com");
        request.addHeader("X-Forwarded-Prefix", "/spoofed");

        new ForwardedHeaderFilter().doFilter(request, new MockHttpServletResponse(),
                (req, res) -> filter.doFilter(req, res, (innerRequest, innerResponse) -> {}));

        assertEquals("203.0.113.25", event().path("clientIp").asText());
        assertEquals("/api/discovery/search", event().path("path").asText());
    }

    @Test
    void ignoresForwardedAddressesFromDirectClients() throws Exception {
        var request = request("/api/catalog/tracks");
        request.addHeader("X-Forwarded-For", "127.0.0.1");
        request.setQueryString("filter=drum&page=3&pageSize=25&sort=trackName&direction=asc&password=secret");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {});

        assertEquals("198.51.100.42", event().path("clientIp").asText());
        assertEquals(5, event().path("parameters").size());
        assertFalse(appender.list.getFirst().getFormattedMessage().contains("secret"));
    }

    @Test
    void trustsNoProxyByDefault() throws Exception {
        var request = request("/api/me");
        request.setRemoteAddr("10.0.0.10");
        request.addHeader("X-Forwarded-For", "127.0.0.1");
        new RequestActivityFilter("").doFilter(request, new MockHttpServletResponse(), (req, res) -> {});
        assertEquals("10.0.0.10", event().path("clientIp").asText());
    }

    @Test
    void supportsIpv6Clients() throws Exception {
        var request = request("/api/me");
        request.setRemoteAddr("10.0.0.10");
        request.addHeader("X-Forwarded-For", "2001:db8::42");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {});
        assertEquals("2001:db8:0:0:0:0:0:42", event().path("clientIp").asText());
    }

    @Test
    void flagsInvalidProxyAddressWithoutResolvingHostnames() throws Exception {
        var request = request("/api/me");
        request.setRemoteAddr("10.0.0.10");
        request.addHeader("X-Forwarded-For", "attacker.example");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {});
        assertEquals("10.0.0.10", event().path("clientIp").asText());
        assertTrue(event().path("invalidForwardedFor").asBoolean());
    }

    @Test
    void usesLastForwardedHeaderAndFirstSearchParameter() throws Exception {
        var request = request("/api/discovery/search");
        request.setRemoteAddr("10.0.0.10");
        request.addHeader("X-Forwarded-For", "127.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.25");
        request.setQueryString("query=first&query=second");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {});
        assertEquals("203.0.113.25", event().path("clientIp").asText());
        assertEquals("first", event().path("parameters").path("query").asText());
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 401, 403, 404, 429, 503})
    void recordsSecurityAndErrorResponseStatuses(int status) throws Exception {
        filter.doFilter(request("/api/me"), new MockHttpServletResponse(), (req, res) ->
                ((jakarta.servlet.http.HttpServletResponse) res).setStatus(status));
        assertEquals(status, event().path("status").asInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/discovery/artist-tracks", "/api/discovery/artist-patches"})
    void recordsArtistFilters(String path) throws Exception {
        var request = request(path);
        request.setQueryString("artistAlias=Artist+Name&token=secret");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {});
        assertEquals("Artist Name", event().path("parameters").path("artistAlias").asText());
        assertEquals(1, event().path("parameters").size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/login", "/oauth2/authorize", "/login/oauth2/code/google",
            "/api/verify-email", "/api/catalog/archived-songs/download", "/missing"})
    void doesNotLogSensitiveParametersOnOtherEndpoints(String path) throws Exception {
        var request = request(path);
        request.setQueryString("query=secret&code=secret&token=secret&password=secret");
        request.addHeader("Authorization", "Bearer secret");
        request.addHeader("Cookie", "session=secret");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {});
        assertFalse(event().has("parameters"));
        assertFalse(appender.list.getFirst().getFormattedMessage().contains("secret"));
    }

    @Test
    void neverReadsRequestBodyAndStripsPathSessionIdentifiers() throws Exception {
        var request = request("/login;jsessionid=secret");
        request.setMethod("POST");
        request.setContentType("application/x-www-form-urlencoded");
        request.setContent("password=secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
                assertEquals('p', req.getInputStream().read()));
        assertEquals("/login", event().path("path").asText());
        assertFalse(appender.list.getFirst().getFormattedMessage().contains("secret"));
    }

    @Test
    void boundsParametersAndHandlesMalformedEncoding() throws Exception {
        var request = request("/api/discovery/search");
        request.setQueryString("query=" + "x".repeat(300) + "&page=%ZZ");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {});
        assertEquals(256, event().path("parameters").path("query").asText().length());
        assertTrue(event().path("malformedParameters").asBoolean());
    }

    @Test
    void omitsExcessivelyLargeQueryStrings() throws Exception {
        var request = request("/api/discovery/search");
        request.setQueryString("query=" + "x".repeat(8192));
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {});
        assertTrue(event().path("parametersOmitted").asBoolean());
        assertFalse(event().has("parameters"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health/liveness", "/actuator/health/readiness"})
    void excludesRoutineHealthChecks(String path) throws Exception {
        filter.doFilter(request(path), new MockHttpServletResponse(), (req, res) -> {});
        assertTrue(appender.list.isEmpty());
    }

    @Test
    void recordsUnhandledErrorsWithoutSwallowingThemAndRestoresMdc() {
        MDC.put("requestId", "outer-request");
        var failure = new ServletException("do not log sensitive exception details");
        assertSame(failure, assertThrows(ServletException.class, () ->
                filter.doFilter(request("/api/me"), new MockHttpServletResponse(), (req, res) -> {
                    throw failure;
                })));
        assertEquals(500, event().path("status").asInt());
        assertEquals("outer-request", MDC.get("requestId"));
        assertFalse(appender.list.getFirst().getFormattedMessage().contains("sensitive"));
    }

    @Test
    void configuresStandaloneJsonOutputWithoutDuplicateRootLogging() throws Exception {
        var context = new LoggerContext();
        try {
            var configurator = new JoranConfigurator();
            configurator.setContext(context);
            configurator.doConfigure(getClass().getResource("/logback-spring.xml"));
            assertFalse(context.getStatusManager().getCopyOfStatusList().stream()
                    .anyMatch(status -> status.getLevel() == Status.ERROR));
            var activityLogger = context.getLogger("org.reasonstation.logging.RequestActivity");
            assertFalse(activityLogger.isAdditive());
            var console = assertInstanceOf(ConsoleAppender.class, activityLogger.getAppender("REQUEST_ACTIVITY"));
            var encoder = assertInstanceOf(PatternLayoutEncoder.class, console.getEncoder());
            assertEquals("%msg%n", encoder.getPattern());
            assertNotNull(context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("CONSOLE"));
        } finally {
            context.stop();
        }
    }

    private MockHttpServletRequest request(String path) {
        var request = new MockHttpServletRequest("GET", path);
        request.setRemoteAddr("198.51.100.42");
        return request;
    }

    private JsonNode event() {
        assertEquals(1, appender.list.size());
        return mapper.readTree(appender.list.getFirst().getFormattedMessage());
    }
}
