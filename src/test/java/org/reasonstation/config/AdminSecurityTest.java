package org.reasonstation.config;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.reasonstation.entity.User;
import org.reasonstation.repository.UserRepository;
import org.reasonstation.service.AdminAccessService;
import org.reasonstation.service.SocialAccountService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminSecurityTest {
    private AnnotationConfigWebApplicationContext context;
    private UserRepository users;
    private Filter security;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfig.class);
        context.refresh();
        users = context.getBean(UserRepository.class);
        security = context.getBean("springSecurityFilterChain", Filter.class);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/html", "application/json"})
    void hidesAdminPagesAndApiFromNonAdmins(String accept) throws Exception {
        User user = new User(7L, "listener", "listener@example.com", "hash");
        when(users.findByAlias("listener")).thenReturn(Optional.of(user));

        for (String path : List.of("/admin", "/admin/", "/admin/index.html", "/api/admin", "/api/admin/users")) {
            var response = request(path, "listener", false, accept);
            assertEquals(404, response.getStatus(), path);
            assertNull(response.getHeader("Location"), path);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/html", "application/json"})
    void hidesAdminPagesAndApiFromAnonymousUsers(String accept) throws Exception {
        for (String path : List.of("/admin", "/admin/", "/admin/index.html", "/api/admin", "/api/admin/users")) {
            var response = request(path, null, false, accept);
            assertEquals(404, response.getStatus(), path);
            assertNull(response.getHeader("Location"), path);
        }
    }

    @Test
    void preservesLoginRedirectForOtherProtectedPages() throws Exception {
        assertEquals(302, request("/api/me", null, false).getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"image", "audio"})
    void allowsAnonymousTrackMediaRequests(String mediaType) throws Exception {
        assertEquals(200, request("/api/tracks/42/" + mediaType, null, true).getStatus());
    }

    @Test
    void hidesAdminPagesFromNonLocalAdminAlias() throws Exception {
        for (String path : List.of("/admin", "/admin/index.html", "/api/admin/users")) {
            assertEquals(404, request(path, "admin", false).getStatus(), path);
        }
    }

    @Test
    void allowsAdminsForPageAndApi() throws Exception {
        User user = new User(7L, "listener", "listener@example.com", "hash");
        user.setAdmin(true);
        when(users.findByAlias("listener")).thenReturn(Optional.of(user));

        for (String path : List.of("/admin", "/admin/", "/admin/index.html", "/api/admin/users")) {
            assertEquals(200, request(path, "listener", true).getStatus(), path);
        }
    }

    private MockHttpServletResponse request(String path, String alias, boolean allowed) throws Exception {
        return request(path, alias, allowed, "text/html");
    }

    private MockHttpServletResponse request(String path, String alias, boolean allowed, String accept)
            throws Exception {
        var request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("Accept", accept);
        if (alias != null) {
            var session = new MockHttpSession();
            session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                    new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(
                            alias, "", List.of())));
            request.setSession(session);
        }
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        security.doFilter(request, response, chain);
        if (allowed) {
            assertNotNull(chain.getRequest(), path);
        } else {
            assertNull(chain.getRequest(), path);
        }
        return response;
    }

    @Configuration
    @EnableWebSecurity
    @Import(SecurityConfig.class)
    static class TestConfig {
        @Bean
        UserRepository users() {
            return mock(UserRepository.class);
        }

        @Bean
        AdminAccessService adminAccessService(UserRepository users) {
            return new AdminAccessService(users);
        }

        @Bean
        SocialAccountService socialAccountService() {
            return mock(SocialAccountService.class);
        }

        @Bean
        ClientRegistrationRepository clientRegistrationRepository() {
            return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("google")
                    .clientId("test-client")
                    .clientSecret("test-secret")
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                    .scope("openid")
                    .authorizationUri("https://example.com/authorize")
                    .tokenUri("https://example.com/token")
                    .jwkSetUri("https://example.com/jwks")
                    .userInfoUri("https://example.com/userinfo")
                    .userNameAttributeName("sub")
                    .build());
        }
    }
}
