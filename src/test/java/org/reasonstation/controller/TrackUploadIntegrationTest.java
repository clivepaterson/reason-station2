package org.reasonstation.controller;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.reasonstation.config.SecurityConfig;
import org.reasonstation.entity.Track;
import org.reasonstation.entity.User;
import org.reasonstation.repository.TrackDownloadRepository;
import org.reasonstation.repository.TrackRatingRepository;
import org.reasonstation.repository.TrackRepository;
import org.reasonstation.repository.UserRepository;
import org.reasonstation.service.AdminAccessService;
import org.reasonstation.service.DatabaseUserDetailsService;
import org.reasonstation.service.S3StorageService;
import org.reasonstation.service.WaveformService;
import org.reasonstation.service.SocialAccountService;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = TrackUploadIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.aws.secretsmanager.enabled=false",
                "spring.cloud.aws.region.static=us-east-1",
                "management.endpoint.health.group.readiness.include=readinessState",
                "app.base-url=http://localhost"
        })
@ActiveProfiles("integration-test")
class TrackUploadIntegrationTest {
    private static final String BOUNDARY = "----WebKitFormBoundaryEkSB6ciBh9FViZa0";
    private static final String AUDIO_LINK = "https://on.soundcloud.com/gmYm46vmkFu4lBZzE1";
    private static final String FILENAME = "mildly melancholic.reason";

    @LocalServerPort
    private int port;

    @MockitoBean
    private UserRepository users;
    @MockitoBean
    private TrackRepository tracks;
    @MockitoBean
    private TrackDownloadRepository downloads;
    @MockitoBean
    private TrackRatingRepository ratings;
    @MockitoBean
    private S3StorageService storage;
    @MockitoBean
    private WaveformService waveforms;
    @MockitoBean
    private SocialAccountService socialAccountService;

    private HttpClient client;
    private User user;

    @BeforeEach
    void setUp() throws Exception {
        var cookies = new CookieManager();
        client = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        user = new User(7L, "comdek", "comdek@example.com", "{noop}test-password");
        user.setEmailVerified(true);
        when(users.findByEmail("comdek@example.com")).thenReturn(Optional.of(user));
        when(users.findByAlias("comdek")).thenReturn(Optional.of(user));

        var response = client.send(HttpRequest.newBuilder(uri("/login"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "username=comdek%40example.com&password=test-password"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(302, response.statusCode(), response.body());
        assertEquals(uri("/index.html"),
                uri("/login").resolve(response.headers().firstValue("Location").orElseThrow()));
        assertTrue(cookies.getCookieStore()
                .getCookies().stream().anyMatch(cookie -> "JSESSIONID".equals(cookie.getName())));
    }

    @ParameterizedTest
    @ValueSource(ints = {1024, 3 * 1024 * 1024})
    void bindsBrowserMultipartFieldsAndUploadsTrack(int fileSize) throws Exception {
        byte[] content = reasonFile(fileSize);
        stubSuccessfulUpload();

        var response = upload(fields(), content);

        assertEquals(201, response.statusCode(), response.body());
        var json = JsonPath.parse(response.body());
        assertEquals(42L, json.read("$.trackid", Long.class));
        assertEquals("comdek", json.read("$.artistAlias", String.class));
        assertEquals("Mildly Melancholic", json.read("$.trackName", String.class));
        assertEquals("Electronic", json.read("$.musicStyle", String.class));
        assertEquals(FILENAME, json.read("$.filename", String.class));
        assertEquals((long) content.length, json.read("$.filesize", Long.class));
        assertEquals(AUDIO_LINK, json.read("$.audioLink", String.class));

        var saved = ArgumentCaptor.forClass(Track.class);
        verify(tracks).save(saved.capture());
        assertEquals(7L, saved.getValue().getUserid());
        assertEquals("comdek", saved.getValue().getArtistAlias());
        assertEquals("Mildly Melancholic", saved.getValue().getTrackName());
        assertEquals("Electronic", saved.getValue().getMusicStyle());
        assertEquals(FILENAME, saved.getValue().getFilename());
        assertEquals((long) content.length, saved.getValue().getFilesize());
        assertEquals(AUDIO_LINK, saved.getValue().getAudioLink());
        assertEquals("s3://tracks/upload", saved.getValue().getTrackUri());
        verify(storage).upload(startsWith("tracks/"), eq(content), eq("application/octet-stream"));
    }

    @Test
    void acceptsMultipartUploadWithoutOptionalAudioLink() throws Exception {
        stubSuccessfulUpload();
        var fields = fields();
        fields.remove("audioLink");

        var response = upload(fields, reasonFile(1024));

        assertEquals(201, response.statusCode(), response.body());
        assertNull(JsonPath.parse(response.body()).read("$.audioLink", String.class));
    }

    @Test
    void rejectsMultipartUploadWhenArtistAliasIsMissing() throws Exception {
        var fields = fields();
        fields.remove("artistAlias");

        var response = upload(fields, reasonFile(1024));

        assertEquals(400, response.statusCode(), response.body());
        verifyNoInteractions(storage, tracks);
    }

    @Test
    void rejectsMultipartUploadWhenEmailIsNotVerified() throws Exception {
        user.setEmailVerified(false);

        var response = upload(fields(), reasonFile(1024));

        assertEquals(403, response.statusCode(), response.body());
        assertEquals("Verify your email address before uploading tracks.",
                JsonPath.parse(response.body()).read("$.error", String.class));
        verifyNoInteractions(storage, tracks);
    }

    private void stubSuccessfulUpload() throws IOException {
        when(storage.upload(any(), any(), any())).thenReturn("s3://tracks/upload");
        when(tracks.save(any())).thenAnswer(invocation -> {
            Track track = invocation.getArgument(0);
            track.setTrackid(42L);
            return track;
        });
    }

    private HttpResponse<String> upload(Map<String, String> fields, byte[] content) throws Exception {
        // Send raw multipart bytes so Tomcat, not MockMvc, parses the form fields and file.
        var body = new StringBuilder();
        fields.forEach((name, value) -> body.append("--").append(BOUNDARY)
                .append("\r\nContent-Disposition: form-data; name=\"").append(name)
                .append("\"\r\n\r\n").append(value).append("\r\n"));
        body.append("--").append(BOUNDARY)
                .append("\r\nContent-Disposition: form-data; name=\"file\"; filename=\"")
                .append(FILENAME).append("\"\r\nContent-Type: application/octet-stream\r\n\r\n");
        String closingBoundary = "\r\n--" + BOUNDARY + "--\r\n";
        byte[] prefix = body.toString().getBytes(StandardCharsets.UTF_8);
        byte[] suffix = closingBoundary.getBytes(StandardCharsets.UTF_8);
        byte[] requestBody = ByteBuffer.allocate(prefix.length + content.length + suffix.length)
                .put(prefix).put(content).put(suffix).array();

        return client.send(HttpRequest.newBuilder(uri("/api/tracks"))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, String> fields() {
        var fields = new LinkedHashMap<String, String>();
        fields.put("artistAlias", "comdek");
        fields.put("trackName", "Mildly Melancholic");
        fields.put("musicStyle", "Electronic");
        fields.put("audioLink", AUDIO_LINK);
        return fields;
    }

    private byte[] reasonFile(int size) {
        return ByteBuffer.allocate(size).put("X15_FILE".getBytes(StandardCharsets.US_ASCII)).array();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.data.jdbc.autoconfigure.DataJdbcRepositoriesAutoConfiguration",
            "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
    })
    @Import({TrackController.class, SecurityConfig.class, DatabaseUserDetailsService.class,
            AdminAccessService.class})
    static class TestApplication {
    }
}
