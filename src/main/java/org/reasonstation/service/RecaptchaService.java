package org.reasonstation.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;

@Service
public class RecaptchaService {
    private final String siteKey;
    private final String secretKey;
    private final RestClient client;

    @Autowired
    public RecaptchaService(
            @Value("${app.recaptcha.site-key:}") String siteKey,
            @Value("${app.recaptcha.secret-key:}") String secretKey) {
        this(siteKey, secretKey, createClient());
    }

    RecaptchaService(String siteKey, String secretKey, RestClient client) {
        this.siteKey = siteKey;
        this.secretKey = secretKey;
        this.client = client;
    }

    private static RestClient createClient() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(factory).build();
    }

    public String siteKey() {
        requireConfiguration();
        return siteKey;
    }

    public boolean verify(String token) {
        requireConfiguration();
        if (token == null || token.isBlank() || token.length() > 8192) {
            return false;
        }
        var form = new LinkedMultiValueMap<String, String>();
        form.add("secret", secretKey);
        form.add("response", token);
        try {
            VerificationResponse response = client.post()
                    .uri("https://www.google.com/recaptcha/api/siteverify")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(VerificationResponse.class);
            if (response == null) {
                throw new RecaptchaUnavailableException("reCAPTCHA returned an empty response.");
            }
            return Boolean.TRUE.equals(response.success());
        } catch (RestClientException exception) {
            throw new RecaptchaUnavailableException("reCAPTCHA verification is unavailable.", exception);
        }
    }

    private void requireConfiguration() {
        if (siteKey.isBlank() || secretKey.isBlank()) {
            throw new RecaptchaUnavailableException("reCAPTCHA keys are not configured.");
        }
    }

    public record VerificationResponse(Boolean success) {
    }

    public static class RecaptchaUnavailableException extends RuntimeException {
        public RecaptchaUnavailableException(String message) {
            super(message);
        }

        public RecaptchaUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
