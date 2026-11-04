package org.reasonstation.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reasonstation.entity.User;
import org.reasonstation.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SocialAccountServiceTest {
    @Mock
    private UserRepository users;
    @Mock
    private PasswordEncoder passwordEncoder;

    @Test
    void provisionsGoogleRegistrationWithSelectedAlias() {
        when(passwordEncoder.encode(any())).thenReturn("encoded-password");
        when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        SocialAccountService service = new SocialAccountService(users, passwordEncoder);

        service.provision("artist@example.com", "My_Alias", true);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(users).save(userCaptor.capture());
        assertEquals("My_Alias", userCaptor.getValue().getAlias());
        assertEquals(true, userCaptor.getValue().isEmailVerified());
    }
}
