package org.reasonstation.config;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.reasonstation.controller.PatchController;
import org.reasonstation.controller.TrackController;
import org.reasonstation.entity.Patch;
import org.reasonstation.entity.Track;
import org.reasonstation.entity.User;
import org.reasonstation.repository.PatchRatingRepository;
import org.reasonstation.repository.PatchRepository;
import org.reasonstation.repository.TrackDownloadRepository;
import org.reasonstation.repository.TrackRatingRepository;
import org.reasonstation.repository.TrackRepository;
import org.reasonstation.repository.UserRepository;
import org.reasonstation.service.AdminAccessService;
import org.reasonstation.service.S3StorageService;
import org.reasonstation.service.SocialAccountService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockServletContext;
import org.springframework.http.HttpMethod;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

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
    @ValueSource(strings = {"image", "audio", "audio/metadata"})
    void allowsAnonymousTrackMediaRequests(String mediaType) throws Exception {
        assertEquals(200, request("/api/tracks/42/" + mediaType, null, true).getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/tracks/42", "/api/patches/42"})
    void requiresAuthenticationForEditing(String path) throws Exception {
        assertEquals(302, request("PUT", path, null, false, "text/html").getStatus());
        assertEquals(200, request("PUT", path, "listener", true, "text/html").getStatus());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void authenticatedMultipartPutBindsOptionalFilesThroughSecurityAndMvc(boolean isTrack) throws Exception {
        User owner = new User(7L, "listener", "listener@example.com", "hash");
        owner.setEmailVerified(true);
        when(users.findByAlias("listener")).thenReturn(Optional.of(owner));
        var tracks = mock(TrackRepository.class);
        var patches = mock(PatchRepository.class);
        var storage = mock(S3StorageService.class);
        Object controller;
        if (isTrack) {
            var track = new Track(7L, "Artist", "Track", "Rock", "old.reason", 100L, "s3://bucket/old");
            track.setTrackid(42L);
            when(tracks.findById(42L)).thenReturn(Optional.of(track));
            when(tracks.save(any())).thenAnswer(call -> call.getArgument(0));
            controller = new TrackController(tracks, mock(TrackDownloadRepository.class),
                    mock(TrackRatingRepository.class), users, storage);
        } else {
            var patch = new Patch(7L, "Artist", "Patch", null,
                    "old.thor", ".thor", 100L, "s3://bucket/old");
            patch.setPatchid(42L);
            when(patches.findById(42L)).thenReturn(Optional.of(patch));
            when(patches.save(any())).thenAnswer(call -> call.getArgument(0));
            controller = new PatchController(patches, mock(PatchRatingRepository.class), users, storage);
        }
        var mvc = standaloneSetup(controller).addFilters(security).build();
        String path = isTrack ? "/api/tracks/42" : "/api/patches/42";
        mvc.perform(multipart(HttpMethod.PUT, path).servletPath(path).header("Accept", "text/html")
                        .param("artistAlias", "Artist").param(isTrack ? "trackName" : "patchName", "Edited")
                        .param("musicStyle", "Rock"))
                .andExpect(status().is3xxRedirection());
        verifyNoInteractions(tracks, patches, storage);

        for (boolean withEmptyParts : List.of(false, true)) {
            var session = new MockHttpSession();
            session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                    new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(
                            "listener", "", List.of())));
            var request = multipart(HttpMethod.PUT, path).servletPath(path).session(session)
                    .param("artistAlias", "Artist").param(isTrack ? "trackName" : "patchName", "Edited")
                    .param("musicStyle", "Rock");
            if (withEmptyParts) {
                request.file(new MockMultipartFile("file", "empty.reason", null, new byte[0]))
                        .file(new MockMultipartFile("image", "empty.png", null, new byte[0]));
                if (isTrack) {
                    request.file(new MockMultipartFile("audio", "empty.mp3", null, new byte[0]));
                }
            }
            mvc.perform(request).andExpect(status().isOk())
                    .andExpect(jsonPath(isTrack ? "$.trackid" : "$.patchid").value(42))
                    .andExpect(jsonPath(isTrack ? "$.trackName" : "$.patchName").value("Edited"))
                    .andExpect(jsonPath("$.filename").value(isTrack ? "old.reason" : "old.thor"));
        }
        verifyNoInteractions(storage);
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
        return request("GET", path, alias, allowed, accept);
    }

    private MockHttpServletResponse request(String method, String path, String alias, boolean allowed, String accept)
            throws Exception {
        var request = new MockHttpServletRequest(method, path);
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
