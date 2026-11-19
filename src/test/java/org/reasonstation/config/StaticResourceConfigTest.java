package org.reasonstation.config;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.reasonstation.controller.SeoController;
import org.reasonstation.entity.Patch;
import org.reasonstation.repository.PatchRepository;
import org.reasonstation.repository.SitemapRepository;
import org.reasonstation.repository.TrackRepository;
import org.reasonstation.repository.TrackWithRating;
import org.reasonstation.service.SitemapService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.DigestUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.resource.ResourceUrlProvider;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

class StaticResourceConfigTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfig.class);
        context.refresh();
        mvc = webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/css/styles.css", "/js/main.js", "/js/pages/admin.js",
            "/images/logo_by_chatgpt.jpg", "/images/style-icons/rock.svg", "/images/audiomack-favicon.ico"})
    void cachesSuccessfulStaticAssetsForOneDayDespiteSecurityDefaults(String path) throws Exception {
        var response = mvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=86400, public"))
                .andReturn().getResponse();
        mvc.perform(head(path))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=86400, public"))
                .andExpect(content().string(""));
        mvc.perform(get(path).header("If-Modified-Since", response.getHeader("Last-Modified")))
                .andExpect(status().isNotModified())
                .andExpect(header().string("Cache-Control", "max-age=86400, public"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/index.html", "/embed.html", "/pages/home.html"})
    void versionsStaticHtmlAndDoesNotStoreIt(String path) throws Exception {
        String html = mvc.perform(get(path).header("If-Modified-Since", "Wed, 31 Dec 2098 23:59:59 GMT"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertVersionedReferences(html);
    }

    @Test
    void versionsAdminHtmlWithoutChangingItsAccessControl() throws Exception {
        mvc.perform(get("/admin/index.html")).andExpect(status().isNotFound());
        String html = webAppContextSetup(context).build().perform(get("/admin/index.html"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertVersionedReferences(html);
        assertTrue(html.matches("(?s).*src=\"/js/pages/admin-[a-f0-9]{32}\\.js\".*"));
    }

    @Test
    void versionsServerRenderedHtmlButLeavesOtherResponsesAndExternalUrlsAlone() throws Exception {
        String html = mvc.perform(get("/generated.html"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertVersionedReferences(html);
        assertTrue(html.contains("https://cdn.example/external.js"));
        assertTrue(html.contains("?mode=full#ready"));
        mvc.perform(get("/plain")).andExpect(content().string("/js/main.js"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/tracks/42", "/patches/15"})
    void versionsCssAndScriptsOnTrackAndPatchPages(String path) throws Exception {
        String html = mvc.perform(get(path)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertVersionedReferences(html);
        assertTrue(html.matches("(?s).*src=\"/js/copy_link-[a-f0-9]{32}\\.js\".*"), html);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/css/styles.css", "/js/main.js", "/js/pages/admin.js"})
    void servesVersionedUrlsMatchingTheAssetContent(String path) throws Exception {
        String versioned = context.getBean(ResourceUrlProvider.class).getForLookupPath(path);
        assertNotEquals(path, versioned);
        try (var input = new ClassPathResource("static" + path).getInputStream()) {
            String hash = DigestUtils.md5DigestAsHex(input);
            int extension = path.lastIndexOf('.');
            assertEquals(path.substring(0, extension) + "-" + hash + path.substring(extension), versioned);
        }
        byte[] original = mvc.perform(get(path)).andReturn().getResponse().getContentAsByteArray();
        mvc.perform(get(versioned))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=86400, public"))
                .andExpect(content().bytes(original));
        String wrongVersion = versioned.replaceFirst("-[a-f0-9]{32}\\.", "-00000000000000000000000000000000.");
        mvc.perform(get(wrongVersion)).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/css/missing.css", "/js/missing.js", "/images/missing.png"})
    void doesNotCacheMissingAssets(String path) throws Exception {
        String cacheControl = mvc.perform(get(path))
                .andExpect(status().isNotFound()).andReturn().getResponse().getHeader("Cache-Control");
        assertTrue(cacheControl.contains("no-store"));
        assertFalse(cacheControl.contains("86400"));
    }

    private void assertVersionedReferences(String html) {
        assertFalse(Pattern.compile("(?:src|href)=[\"']/(?:css|js)/[^\"']*"
                + "(?<![a-f0-9]{32})\\.(?:css|js)[\"']").matcher(html).find(), html);
        if (html.contains("/css/")) {
            assertTrue(html.matches("(?s).*href=\"/css/styles-[a-f0-9]{32}\\.css\".*"), html);
        }
    }

    @Configuration
    @EnableWebMvc
    @Import({StaticResourceConfig.class, HtmlAssetVersionAdvice.class,
            AdminSecurityTest.TestConfig.class, HtmlController.class})
    static class TestConfig {
        @Bean
        SeoController seoController() {
            TrackRepository tracks = mock(TrackRepository.class);
            when(tracks.findWithRatingById(42L)).thenReturn(Optional.of(new TrackWithRating(
                    42L, 7L, "Artist", "Track", "Electronic", "track.reason", 1024L,
                    "s3://tracks/track", Instant.parse("2026-11-03T10:15:30Z"), 8.5, 12,
                    null, "13.2.1", null, null, null)));
            PatchRepository patches = mock(PatchRepository.class);
            Patch patch = new Patch(7L, "Artist", "Patch", "Bass",
                    "bass.thor", ".thor", 1024L, "s3://tracks/patch");
            patch.setPatchid(15L);
            when(patches.findById(15L)).thenReturn(Optional.of(patch));
            return new SeoController(tracks, patches, new ObjectMapper(),
                    new SitemapService(mock(SitemapRepository.class), 300_000),
                    "https://reasonstation.example");
        }
    }

    @RestController
    static class HtmlController {
        @GetMapping(value = "/generated.html", produces = MediaType.TEXT_HTML_VALUE)
        String html() {
            return """
                    <link href="/css/styles.css" rel="stylesheet">
                    <script src="/js/main.js?mode=full#ready"></script>
                    <script src="https://cdn.example/external.js"></script>
                    """;
        }

        @GetMapping(value = "/plain", produces = MediaType.TEXT_PLAIN_VALUE)
        String plain() {
            return "/js/main.js";
        }
    }
}
