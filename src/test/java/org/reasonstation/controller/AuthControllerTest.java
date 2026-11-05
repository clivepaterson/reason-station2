package org.reasonstation.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reasonstation.entity.User;
import org.reasonstation.repository.UserRepository;
import org.reasonstation.service.VerificationEmailService;
import org.reasonstation.service.RecaptchaService;
import org.reasonstation.service.RecaptchaService.RecaptchaUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.mock.web.MockHttpSession;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    private RecaptchaService recaptcha;
    @Mock
    private Authentication authentication;
    @Mock
    private UserDetails userDetails;

    private AuthController controller;

    @BeforeEach
    void setUp() {
        controller = new AuthController(users, passwordEncoder, verificationEmails, recaptcha);
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
        assertEquals(false, response.get("admin"));
    }

    @Test
    void exposesAdminFlagForAdminNavigation() {
        User user = unverifiedUser();
        user.setAdmin(true);
        when(authentication.getPrincipal()).thenReturn(userDetails);
        when(userDetails.getUsername()).thenReturn("listener");
        when(users.findByAlias("listener")).thenReturn(Optional.of(user));

        assertEquals(true, controller.me(authentication).get("admin"));
    }

    @Test
    void resolvesAdminNavigationForJwtPreferredUsername() {
        User user = unverifiedUser();
        user.setAdmin(true);
        when(users.findByAlias("listener")).thenReturn(Optional.of(user));
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "RS256")
                .subject("provider-subject").claim("preferred_username", "listener").build();

        var response = controller.me(new JwtAuthenticationToken(jwt, java.util.List.of()));

        assertEquals("listener", response.get("username"));
        assertEquals(true, response.get("admin"));
    }

    @Test
    void assignsCreationDateWhenRegisteringUser() {
        when(recaptcha.verify("valid-token")).thenReturn(true);
        when(passwordEncoder.encode("password")).thenReturn("encoded-password");
        when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = controller.register(new AuthController.RegisterRequest(
                "listener", "listener@example.com", "password", "valid-token"));

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(users).save(userCaptor.capture());
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(userCaptor.getValue().getDateCreated());
        assertEquals(false, userCaptor.getValue().isAdmin());
    }

    @Test
    void rejectsMissingRegistrationBody() {
        var response = controller.register(null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(users, never()).save(any());
    }

    @Test
    void rejectsExcessivelyLongPassword() {
        var response = controller.register(new AuthController.RegisterRequest(
                "listener", "listener@example.com", "p".repeat(129), "valid-token"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(passwordEncoder, never()).encode(any());
        verify(users, never()).save(any());
    }

    @Test
    void rejectsRegistrationWithoutCaptcha() {
        var response = controller.register(new AuthController.RegisterRequest(
                "listener", "listener@example.com", "password", null));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(users, never()).findByAlias(any());
        verify(users, never()).save(any());
        verify(passwordEncoder, never()).encode(any());
        verify(verificationEmails, never()).send(any());
    }

    @Test
    void rejectsInvalidCaptcha() {
        var response = controller.register(new AuthController.RegisterRequest(
                "listener", "listener@example.com", "password", "invalid-token"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(users, never()).save(any());
    }

    @Test
    void rejectsRegistrationWhenCaptchaServiceIsUnavailable() {
        when(recaptcha.verify("token")).thenThrow(new RecaptchaUnavailableException("Unavailable"));

        var response = controller.register(new AuthController.RegisterRequest(
                "listener", "listener@example.com", "password", "token"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        verify(users, never()).save(any());
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    void exposesOnlyPublicCaptchaKey() {
        when(recaptcha.isEnabled()).thenReturn(true);
        when(recaptcha.siteKey()).thenReturn("public-site-key");

        var response = controller.recaptchaConfiguration();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(Map.of("enabled", true, "siteKey", "public-site-key"), response.getBody());
    }

    @Test
    void reportsUnavailableCaptchaConfiguration() {
        when(recaptcha.isEnabled()).thenReturn(true);
        when(recaptcha.siteKey()).thenThrow(new RecaptchaUnavailableException("Missing keys"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, controller.recaptchaConfiguration().getStatusCode());
    }

    @Test
    void exposesDisabledLocalCaptchaWithoutReadingKeys() {
        var response = controller.recaptchaConfiguration();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(Map.of("enabled", false), response.getBody());
        verify(recaptcha, never()).siteKey();
    }

    @Test
    void allowsLocalRegistrationWithoutCaptchaToken() {
        var localController = new AuthController(users, passwordEncoder, verificationEmails,
                new RecaptchaService(false, "", ""));
        when(passwordEncoder.encode("password")).thenReturn("encoded-password");
        when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = localController.register(new AuthController.RegisterRequest(
                "listener", "listener@example.com", "password", null));

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        verify(verificationEmails).send(any(User.class));
    }

    @Test
    void storesAliasBeforeStartingGoogleRegistration() {
        MockHttpSession session = new MockHttpSession();

        var response = controller.registerWithGoogle("Google_Artist", session);

        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        assertEquals("/oauth2/authorization/google", response.getHeaders().getLocation().toString());
        assertEquals("Google_Artist", session.getAttribute(
                org.reasonstation.service.SocialAccountService.REGISTRATION_ALIAS_SESSION_ATTRIBUTE));
    }

    @Test
    void rejectsUnavailableGoogleRegistrationAlias() {
        when(users.findByAlias("listener")).thenReturn(Optional.of(unverifiedUser()));

        var response = controller.registerWithGoogle("listener", new MockHttpSession());

        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        assertEquals("/index.html?register=true&aliasError=unavailable",
                response.getHeaders().getLocation().toString());
    }

    @Test
    void clearsPendingRegistrationAliasBeforeGoogleLogin() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                org.reasonstation.service.SocialAccountService.REGISTRATION_ALIAS_SESSION_ATTRIBUTE,
                "Pending_Alias");

        var response = controller.loginWithGoogle(session);

        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        assertEquals(null, session.getAttribute(
                org.reasonstation.service.SocialAccountService.REGISTRATION_ALIAS_SESSION_ATTRIBUTE));
    }

    private User unverifiedUser() {
        return new User(7L, "listener", "listener@example.com", "password");
    }
}
