package org.reasonstation.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reasonstation.entity.User;
import org.reasonstation.repository.UserRepository;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAccessServiceTest {
    @Mock
    private UserRepository users;

    @Test
    void requiresStoredFlagRatherThanAliasOrClaimedAuthority() {
        User user = new User(7L, "admin", "listener@example.com", "hash");
        when(users.findByAlias("admin")).thenReturn(Optional.of(user));
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                "admin", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        var access = new AdminAccessService(users);

        assertFalse(access.isAdmin(authentication));
        user.setAdmin(true);
        assertTrue(access.isAdmin(authentication));
        user.setAdmin(false);
        assertFalse(access.isAdmin(authentication));
    }

    @Test
    void rejectsAnonymousAndUnauthenticatedRequests() {
        var access = new AdminAccessService(users);

        assertFalse(access.isAdmin(null));
        assertFalse(access.isAdmin(UsernamePasswordAuthenticationToken.unauthenticated("listener", "")));
        assertFalse(access.isAdmin(new AnonymousAuthenticationToken("key", "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")))));
        verifyNoInteractions(users);
    }

    @Test
    void rejectsDeletedOrUnknownAccounts() {
        var authentication = UsernamePasswordAuthenticationToken.authenticated("missing", "", List.of());
        assertFalse(new AdminAccessService(users).isAdmin(authentication));
    }

    @Test
    void resolvesJwtPreferredUsernameAgainstDatabase() {
        User user = new User(7L, "listener", "listener@example.com", "hash");
        user.setAdmin(true);
        when(users.findByAlias("listener")).thenReturn(Optional.of(user));
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "RS256")
                .subject("provider-subject").claim("preferred_username", "listener").build();

        assertTrue(new AdminAccessService(users).isAdmin(
                new JwtAuthenticationToken(jwt, List.of())));
    }

    @Test
    void resolvesProvisionedGoogleAliasAgainstDatabase() {
        User user = new User(7L, "listener", "listener@example.com", "hash");
        user.setAdmin(true);
        when(users.findByAlias("listener")).thenReturn(Optional.of(user));
        OidcUser principal = mock(OidcUser.class);
        when(principal.getName()).thenReturn("listener");

        assertTrue(new AdminAccessService(users).isAdmin(
                new OAuth2AuthenticationToken(principal, List.of(), "google")));
    }
}
