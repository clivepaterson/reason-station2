package org.reasonstation.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reasonstation.entity.User;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class VerificationEmailServiceTest {
    @Mock
    private SesV2Client ses;

    @Test
    void sendsVerificationLinkToUsersEmailAddress() {
        User user = new User(7L, "listener", "listener@example.com", "password");
        VerificationEmailService service = new VerificationEmailService(
                ses, "verify@reasonstation.example", "https://reasonstation.example");

        service.send(user);

        ArgumentCaptor<SendEmailRequest> request = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(ses).sendEmail(request.capture());
        assertEquals("verify@reasonstation.example", request.getValue().fromEmailAddress());
        assertEquals("listener@example.com",
                request.getValue().destination().toAddresses().get(0));
        assertTrue(request.getValue().content().simple().body().html().data()
                .contains("https://reasonstation.example/api/verify-email?verifyEmailToken="
                        + user.getVerifyEmailToken()));
    }
}
