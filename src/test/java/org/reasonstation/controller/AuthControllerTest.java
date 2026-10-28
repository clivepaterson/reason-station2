package org.reasonstation.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reasonstation.entity.User;
import org.reasonstation.repository.UserRepository;
import org.reasonstation.service.VerificationEmailService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {
    @Mock
    private UserRepository users;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private VerificationEmailService verificationEmails;
    @Mock
    private Authentication authentication;
    @Mock
    private UserDetails userDetails;

    private AuthController controller;

    @BeforeEach
    void setUp() {
        controller = new AuthController(users, passwordEncoder, verificationEmails);
    }

    @Test
    void resendsVerificationEmailWithFreshToken() {
        User user = unverifiedUser();
        UUID previousToken = user.getVerifyEmailToken();
        when(authentication.getName()).thenReturn("listener");
        when(users.findByAlias("listener")).thenReturn(Optional.of(user));

        var response = controller.resendVerificationEmail(authentication);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotEquals(previousToken, user.getVerifyEmailToken());
        verify(users).save(user);
        verify(verificationEmails).send(user);
    }

    @Test
    void doesNotResendForVerifiedUser() {
        User user = unverifiedUser();
        user.setEmailVerified(true);
        when(authentication.getName()).thenReturn("listener");
        when(users.findByAlias("listener")).thenReturn(Optional.of(user));

        var response = controller.resendVerificationEmail(authentication);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        verify(users, never()).save(any());
        verify(verificationEmails, never()).send(any());
    }

    @Test
    void exposesVerificationStatusForMyTracksPage() {
        User user = unverifiedUser();
        when(authentication.getPrincipal()).thenReturn(userDetails);
        when(userDetails.getUsername()).thenReturn("listener");
        when(users.findByAlias("listener")).thenReturn(Optional.of(user));

        Map<String, Object> response = controller.me(authentication);

        assertEquals("listener", response.get("username"));
        assertEquals(false, response.get("emailVerified"));
    }

    private User unverifiedUser() {
        return new User(7L, "listener", "listener@example.com", "password");
    }
}
