package org.reasonstation.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;

import java.net.SocketTimeoutException;

class RecaptchaServiceTest {
    private MockRestServiceServer server;
    private RecaptchaService service;
    private RestClient client;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = builder.build();
        service = new RecaptchaService("site-key", "secret-key", client);
    }

    @Test
    void verifiesTokenWithGoogleUsingFormPost() {
        server.expect(requestTo("https://www.google.com/recaptcha/api/siteverify"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string("secret=secret-key&response=test%2Btoken"))
                .andRespond(withSuccess(
                        "{\"success\":true,\"hostname\":\"localhost\",\"challenge_ts\":\"2026-11-05T00:00:00Z\"}",
                        MediaType.APPLICATION_JSON));

        assertTrue(service.verify("test+token"));
        server.verify();
    }

    @Test
    void rejectsFailedChallenge() {
        server.expect(requestTo("https://www.google.com/recaptcha/api/siteverify"))
                .andRespond(withSuccess(
                        "{\"success\":false,\"error-codes\":[\"timeout-or-duplicate\"]}",
                        MediaType.APPLICATION_JSON));

        assertFalse(service.verify("expired-token"));
        server.verify();
    }

    @Test
    void rejectsMissingOrOversizedTokensWithoutContactingGoogle() {
        assertFalse(service.verify(null));
        assertFalse(service.verify(" "));
        assertFalse(service.verify("x".repeat(8193)));
        server.verify();
    }

    @Test
    void failsClosedWhenKeysAreMissing() {
        var missingSecret = new RecaptchaService("site-key", "", client);
        var missingSiteKey = new RecaptchaService("", "secret-key", client);

        assertThrows(RecaptchaService.RecaptchaUnavailableException.class, missingSecret::siteKey);
        assertThrows(RecaptchaService.RecaptchaUnavailableException.class,
                () -> missingSecret.verify("token"));
        assertThrows(RecaptchaService.RecaptchaUnavailableException.class,
                () -> missingSiteKey.verify("token"));
        server.verify();
    }

    @Test
    void surfacesGoogleHttpFailure() {
        server.expect(requestTo("https://www.google.com/recaptcha/api/siteverify"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThrows(RecaptchaService.RecaptchaUnavailableException.class, () -> service.verify("token"));
        server.verify();
    }

    @Test
    void rejectsResponseWithoutExplicitSuccess() {
        server.expect(requestTo("https://www.google.com/recaptcha/api/siteverify"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertFalse(service.verify("token"));
        server.verify();
    }

    @Test
    void surfacesEmptyResponse() {
        server.expect(requestTo("https://www.google.com/recaptcha/api/siteverify"))
                .andRespond(withSuccess());

        assertThrows(RecaptchaService.RecaptchaUnavailableException.class, () -> service.verify("token"));
        server.verify();
    }

    @Test
    void surfacesGoogleTimeout() {
        server.expect(requestTo("https://www.google.com/recaptcha/api/siteverify"))
                .andRespond(withException(new SocketTimeoutException("Timed out")));

        assertThrows(RecaptchaService.RecaptchaUnavailableException.class, () -> service.verify("token"));
        server.verify();
    }

    @Test
    void surfacesMalformedResponse() {
        server.expect(requestTo("https://www.google.com/recaptcha/api/siteverify"))
                .andRespond(withSuccess("not-json", MediaType.APPLICATION_JSON));

        assertThrows(RecaptchaService.RecaptchaUnavailableException.class, () -> service.verify("token"));
        server.verify();
    }
}
