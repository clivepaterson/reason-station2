package org.reasonstation.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnProperty(name = "app.request-logging.enabled", havingValue = "true", matchIfMissing = true)
public class RequestActivityFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger("org.reasonstation.logging.RequestActivity");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> HEALTH_PATHS = Set.of(
            "/actuator/health/liveness", "/actuator/health/readiness");
    private static final Set<String> ARTIST_PATHS = Set.of(
            "/api/discovery/artist-tracks", "/api/discovery/artist-patches");
    private static final Set<String> CATALOG_PATHS = Set.of(
            "/api/catalog/artists", "/api/catalog/tracks", "/api/catalog/patches",
            "/api/catalog/archived-songs", "/api/catalog/archived-refills");
    private static final Set<String> CATALOG_PARAMETERS = Set.of("filter", "page", "pageSize", "sort", "direction");
    private final List<IpAddressMatcher> trustedProxies;

    public RequestActivityFilter(@Value("${app.request-logging.trusted-proxies:}") String trustedProxies) {
        this.trustedProxies = Arrays.stream(trustedProxies.split(","))
                .map(String::trim).filter(trustedProxy -> !trustedProxy.isEmpty())
                .map(IpAddressMatcher::new).toList();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return HEALTH_PATHS.contains(originalRequest(request).getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var original = originalRequest(request);
        String requestId = UUID.randomUUID().toString();
        String previousRequestId = MDC.get("requestId");
        var event = new LinkedHashMap<String, Object>();
        event.put("event", "http_request");
        event.put("requestId", requestId);
        event.put("method", bounded(original.getMethod(), 16));
        event.put("path", bounded(original.getRequestURI().replaceAll(";[^/]*", ""), 512));
        event.put("clientIp", clientIp(original, event));
        addSearchParameters(original, event);
        request.setAttribute("requestId", requestId);
        response.setHeader("X-Request-ID", requestId);
        MDC.put("requestId", requestId);
        long started = System.nanoTime();
        boolean completed = false;
        try {
            chain.doFilter(request, response);
            completed = true;
        } finally {
            event.put("timestamp", Instant.now().toString());
            event.put("status", !completed && response.getStatus() < 400 ? 500 : response.getStatus());
            event.put("durationMs", (System.nanoTime() - started) / 1_000_000);
            try {
                LOG.info("{}", JSON.writeValueAsString(event));
            } finally {
                if (previousRequestId == null) {
                    MDC.remove("requestId");
                } else {
                    MDC.put("requestId", previousRequestId);
                }
            }
        }
    }

    private String clientIp(HttpServletRequest request, Map<String, Object> event) {
        String peer = request.getRemoteAddr();
        if (trustedProxies.stream().noneMatch(proxy -> proxy.matches(peer))) {
            return peer;
        }
        // ALB appends the actual connecting client after any client-supplied XFF values.
        String forwarded = null;
        var headers = request.getHeaders("X-Forwarded-For");
        while (headers.hasMoreElements()) {
            forwarded = headers.nextElement();
        }
        if (forwarded != null) {
            String address = forwarded.substring(forwarded.lastIndexOf(',') + 1).trim();
            try {
                return InetAddress.ofLiteral(address).getHostAddress();
            } catch (IllegalArgumentException exception) {
                event.put("invalidForwardedFor", true);
            }
        }
        return peer;
    }

    private void addSearchParameters(HttpServletRequest request, Map<String, Object> event) {
        if (!"GET".equals(request.getMethod())) {
            return;
        }
        String path = request.getRequestURI();
        Set<String> allowed;
        if ("/api/discovery/search".equals(path)) {
            allowed = Set.of("query", "page");
        } else if (CATALOG_PATHS.contains(path)) {
            allowed = CATALOG_PARAMETERS;
        } else if (ARTIST_PATHS.contains(path)) {
            allowed = Set.of("artistAlias");
        } else {
            return;
        }
        String query = request.getQueryString();
        if (query == null) {
            return;
        }
        if (query.length() > 8192) {
            event.put("parametersOmitted", true);
            return;
        }
        var parameters = new LinkedHashMap<String, String>();
        for (String pair : query.split("&")) {
            int separator = pair.indexOf('=');
            try {
                String name = URLDecoder.decode(separator < 0 ? pair : pair.substring(0, separator),
                        StandardCharsets.UTF_8);
                if (allowed.contains(name) && !parameters.containsKey(name)) {
                    String value = separator < 0 ? "" : URLDecoder.decode(pair.substring(separator + 1),
                            StandardCharsets.UTF_8);
                    parameters.put(name, bounded(value, 256));
                }
            } catch (IllegalArgumentException exception) {
                event.put("malformedParameters", true);
            }
        }
        event.put("parameters", parameters);
    }

    private static HttpServletRequest originalRequest(HttpServletRequest request) {
        // ForwardedHeaderFilter may have replaced both remoteAddr and the visible headers.
        ServletRequest original = request;
        while (original instanceof ServletRequestWrapper wrapper) {
            original = wrapper.getRequest();
        }
        return (HttpServletRequest) original;
    }

    private static String bounded(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit);
    }
}
