package org.reasonstation.service;

import org.reasonstation.entity.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

@Service
public class VerificationEmailService {
    private final SesV2Client ses;
    private final String fromAddress;
    private final String applicationBaseUrl;

    public VerificationEmailService(
            SesV2Client ses,
            @Value("${app.email.from-address:no-reply@example.com}") String fromAddress,
            @Value("${app.base-url:http://localhost:8080}") String applicationBaseUrl) {
        this.ses = ses;
        this.fromAddress = fromAddress;
        this.applicationBaseUrl = applicationBaseUrl;
    }

    public void send(User user) {
        if (user.getVerifyEmailToken() == null) {
            throw new IllegalArgumentException("A verification token is required.");
        }

        String verificationUrl = UriComponentsBuilder.fromUriString(applicationBaseUrl)
                .path("/api/verify-email")
                .queryParam("verifyEmailToken", user.getVerifyEmailToken())
                .build()
                .encode()
                .toUriString();
        String text = "Verify your ReasonStation email address:\n\n" + verificationUrl;
        String html = """
                <p>Verify your ReasonStation email address:</p>
                <p><a href="%s">Verify email address</a></p>
                """.formatted(verificationUrl);

        SendEmailRequest request = SendEmailRequest.builder()
                .fromEmailAddress(fromAddress)
                .destination(Destination.builder().toAddresses(user.getEmail()).build())
                .content(EmailContent.builder()
                        .simple(Message.builder()
                                .subject(Content.builder()
                                        .data("Verify your ReasonStation email address")
                                        .build())
                                .body(Body.builder()
                                        .text(Content.builder().data(text).build())
                                        .html(Content.builder().data(html).build())
                                        .build())
                                .build())
                        .build())
                .build();
        try {
            ses.sendEmail(request);
        } catch (SdkException exception) {
            throw new VerificationEmailException("Amazon SES could not send the verification email.", exception);
        }
    }

    public static class VerificationEmailException extends RuntimeException {
        public VerificationEmailException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
