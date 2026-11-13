package org.reasonstation.controller;

import org.reasonstation.entity.Track;
import org.reasonstation.entity.Patch;
import org.reasonstation.repository.PatchRepository;
import org.reasonstation.repository.TrackRepository;
import org.reasonstation.repository.TrackWithRating;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Controller
public class SeoController {
    private static final String SITE_DESCRIPTION =
            "Discover, share, and download Reason music projects while learning sound synthesis techniques from other artists.";

    private final TrackRepository tracks;
    private final PatchRepository patches;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public SeoController(TrackRepository tracks, PatchRepository patches, ObjectMapper objectMapper,
                         @Value("${app.base-url:http://localhost:8080}") String baseUrl) {
        this.tracks = tracks;
        this.patches = patches;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    @GetMapping(value = "/tracks/{trackid}", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> trackPage(@PathVariable Long trackid) {
        if (trackid == null || trackid < 1) {
            return ResponseEntity.badRequest().body("Track ID must be a positive integer.");
        }
        return tracks.findWithRatingById(trackid)
                .map(track -> ResponseEntity.ok(trackHtml(track)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(notFoundHtml("Track", "tracks")));
    }

    @GetMapping(value = "/patches/{patchid}", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> patchPage(@PathVariable Long patchid) {
        if (patchid == null || patchid < 1) {
            return ResponseEntity.badRequest().body("Patch ID must be a positive integer.");
        }
        return patches.findById(patchid)
                .map(patch -> ResponseEntity.ok(patchHtml(patch)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(notFoundHtml("Patch", "patches")));
    }

    @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    @ResponseBody
    public String sitemap() {
        StringBuilder xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                """);
        List.of(
                "/index.html", "/index.html?view=artists", "/index.html?view=tracks",
                "/index.html?view=patches", "/index.html?view=tutorials",
                "/index.html?view=archived-songs", "/index.html?view=archived-refills",
                "/index.html?view=links", "/index.html?view=about"
        ).forEach(path -> appendSitemapUrl(xml, baseUrl + path, null));

        StreamSupport.stream(tracks.findAll().spliterator(), false)
                .forEach(track -> appendSitemapUrl(
                        xml,
                        baseUrl + "/tracks/" + track.getTrackid(),
                        track.getDateUploaded() == null
                                ? null
                                : DateTimeFormatter.ISO_LOCAL_DATE
                                        .withZone(java.time.ZoneOffset.UTC)
                                        .format(track.getDateUploaded())));
        StreamSupport.stream(patches.findAll().spliterator(), false)
                .forEach(patch -> appendSitemapUrl(
                        xml,
                        baseUrl + "/patches/" + patch.getPatchid(),
                        patch.getDateUploaded() == null ? null
                                : DateTimeFormatter.ISO_LOCAL_DATE
                                        .withZone(java.time.ZoneOffset.UTC)
                                        .format(patch.getDateUploaded())));
        return xml.append("</urlset>\n").toString();
    }

    @GetMapping(value = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    @ResponseBody
    public String robots() {
        return """
                User-agent: *
                Allow: /
                Disallow: /api/
                Disallow: /oauth2/
                Disallow: /login
                Disallow: /logout
                Disallow: /scalar/
                Disallow: /swagger-ui/
                Disallow: /*?view=my-tracks
                Sitemap: %s/sitemap.xml
                """.formatted(baseUrl);
    }

    private String trackHtml(TrackWithRating track) {
        String trackName = escape(track.trackName());
        String artistAlias = escape(track.artistAlias());
        String musicStyle = escape(track.musicStyle());
        String filename = escape(track.filename())
                + (track.programVersion() == null || track.programVersion().isBlank()
                ? "" : " (Reason " + escape(track.programVersion()) + ")");
        String downloadUrl = "/api/tracks/" + track.trackid() + "/download";
        String canonicalUrl = baseUrl + "/tracks/" + track.trackid();
        String imageUrl = track.imageUri() == null || track.imageUri().isBlank()
                ? baseUrl + "/images/logo_by_chatgpt.jpg"
                : baseUrl + "/api/tracks/" + track.trackid() + "/image";
        String description = "Download " + track.trackName() + " by " + track.artistAlias()
                + ", a " + track.musicStyle() + " Reason music project shared on Reason Station.";
        String escapedDescription = escape(description);
        String title = escape(track.trackName() + " by " + track.artistAlias() + " | Reason Station");
        String artistUrl = "/index.html?artistAlias="
                + UriUtils.encodeQueryParam(track.artistAlias(), StandardCharsets.UTF_8);
        String dateUploaded = track.dateUploaded() == null
                ? ""
                : DateTimeFormatter.ISO_INSTANT.format(track.dateUploaded());
        String visibleDate = track.dateUploaded() == null
                ? ""
                : DateTimeFormatter.ofPattern("MMMM d, uuuu")
                        .withZone(java.time.ZoneOffset.UTC)
                        .format(track.dateUploaded());

        Map<String, Object> structuredData = new LinkedHashMap<>();
        structuredData.put("@context", "https://schema.org");
        structuredData.put("@type", "MusicRecording");
        structuredData.put("name", track.trackName());
        structuredData.put("url", canonicalUrl);
        structuredData.put("description", description);
        structuredData.put("genre", track.musicStyle());
        structuredData.put("byArtist", Map.of(
                "@type", "MusicGroup",
                "name", track.artistAlias(),
                "url", baseUrl + artistUrl));
        if (!dateUploaded.isEmpty()) {
            structuredData.put("datePublished", dateUploaded);
        }
        structuredData.put("encoding", Map.of(
                "@type", "MediaObject",
                "name", track.filename(),
                "contentUrl", baseUrl + "/api/tracks/" + track.trackid() + "/download"));
        String ratingTrackData = toJson(Map.of(
                "trackid", track.trackid(),
                "trackName", track.trackName(),
                "artistAlias", track.artistAlias(),
                "musicStyle", track.musicStyle(),
                "imageUri", track.imageUri() == null ? "" : track.imageUri()));

        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>%s</title>
                    <meta name="description" content="%s">
                    <meta name="author" content="%s">
                    <meta name="robots" content="index, follow, max-image-preview:large, max-snippet:-1">
                    <meta name="theme-color" content="#111827">
                    <link rel="canonical" href="%s">
                    <link rel="icon" href="/images/logo_by_chatgpt.jpg" type="image/jpeg">
                    <meta property="og:type" content="music.song">
                    <meta property="og:site_name" content="Reason Station">
                    <meta property="og:title" content="%s">
                    <meta property="og:description" content="%s">
                    <meta property="og:url" content="%s">
                    <meta property="og:image" content="%s">
                    <meta property="og:image:alt" content="Reason Station logo">
                    <meta property="music:musician" content="%s">
                    <meta name="twitter:card" content="summary">
                    <meta name="twitter:title" content="%s">
                    <meta name="twitter:description" content="%s">
                    <meta name="twitter:image" content="%s">
                    <script type="application/ld+json">%s</script>
                    <link rel="stylesheet" href="/css/styles.css">
                </head>
                <body class="site-page track-detail-page">
                    <header class="navbar track-detail-header">
                        <div class="container">
                            <a class="logo-title" href="/index.html" aria-label="Reason Station home">
                                <img src="/images/logo_by_chatgpt.jpg" alt="">
                                <span>Reason <b>Station</b></span><span id="header-rs-2">2</span>
                            </a>
                        </div>
                    </header>
                    <main class="container track-detail-main">
                        <nav class="track-detail-breadcrumb" aria-label="Breadcrumb">
                            <a href="/index.html">Home</a><span aria-hidden="true">/</span>
                            <a href="/index.html?view=tracks">Tracks</a>
                        </nav>
                        <article class="track-card track-detail-card">
                            <img id="track-style-icon" class="track-card-art"
                                 src="%s" alt="%s">
                            <div class="track-card-content">
                                <div class="track-card-heading">
                                    <h1>%s</h1>
                                    <span class="track-style-badge">%s</span>
                                </div>
                                <a class="track-artist-link" href="%s">by %s</a>
                                <a class="track-file-meta" href="%s" aria-label="Download %s by %s">%s</a>
                                %s
                                %s
                                <div class="track-card-meta">
                                    <span class="track-style-meta">Style: %s</span>
                                    %s
                                    <span class="track-downloads-meta">Downloads: %d</span>
                                    %s
                                    %s
                                </div>
                            </div>
                            <div class="track-card-actions">
                                <a class="download-track-link" href="/api/tracks/%d/download"
                                   aria-label="Download %s by %s">Download</a>
                                <div id="track-rating-action" class="track-rating-action"></div>
                            </div>
                        </article>
                    </main>
                    <script id="track-rating-data" type="application/json">%s</script>
                    <script src="/js/style_icons.js"></script>
                    <script src="/js/track_rating.js"></script>
                    <script src="/js/copy_link.js"></script>
                    <script>
                        var track = JSON.parse(document.getElementById('track-rating-data').textContent);
                        document.getElementById('track-style-icon').replaceWith(
                            window.createTrackArt(track));
                        document.getElementById('track-rating-action').appendChild(
                            window.createRateTrackButton(track));
                        document.querySelector('.track-card-actions').appendChild(
                            window.createCopyLinkButton('/tracks/' + encodeURIComponent(track.trackid), track.trackName));
                    </script>
                </body>
                </html>
                """.formatted(
                title, escapedDescription, artistAlias, canonicalUrl,
                title, escapedDescription, canonicalUrl, imageUrl, artistAlias,
                title, escapedDescription, imageUrl, toJson(structuredData),
                track.imageUri() == null || track.imageUri().isBlank()
                        ? "/images/style-icons/other.svg" : "/api/tracks/" + track.trackid() + "/image",
                track.imageUri() == null || track.imageUri().isBlank() ? "" : "Image for " + trackName,
                trackName, musicStyle, escape(artistUrl), artistAlias,
                downloadUrl, trackName, artistAlias, filename,
                track.audioUri() == null || track.audioUri().isBlank() ? ""
                        : "<a class=\"track-file-meta track-mp3-link\" href=\"/api/tracks/" + track.trackid()
                                + "/audio\" aria-label=\"Download MP3 for " + trackName + " by " + artistAlias
                                + "\">Download MP3</a>",
                track.audioLink() == null || track.audioLink().isBlank() ? ""
                        : "<a class=\"track-file-meta track-audio-link\" href=\"" + escape(track.audioLink())
                                + "\" target=\"_blank\" rel=\"noopener noreferrer\">"
                                + escape(track.audioLink()) + "</a>",
                musicStyle,
                filesizeHtml(track.filesize()), track.downloadCount(), ratingHtml(track.averageTrackRating()),
                visibleDate.isEmpty() ? "" : "<time class=\"track-uploaded-meta\" datetime=\"" + dateUploaded
                        + "\">Uploaded: " + escape(visibleDate) + "</time>",
                track.trackid(), trackName, artistAlias, ratingTrackData);
    }

    private String patchHtml(Patch patch) {
        String canonicalUrl = baseUrl + "/patches/" + patch.getPatchid();
        String imageUrl = patch.getPatchImgUri() == null
                ? baseUrl + "/images/logo_by_chatgpt.jpg"
                : baseUrl + "/api/patches/" + patch.getPatchid() + "/image";
        String description = "Download " + patch.getPatchName() + " by " + patch.getArtistAlias()
                + ", a Reason patch shared on Reason Station."
                + (patch.getDescription() == null ? "" : " " + patch.getDescription());
        String title = escape(patch.getPatchName() + " by " + patch.getArtistAlias() + " | Reason Station");
        String artistUrl = "/index.html?artistAlias="
                + UriUtils.encodeQueryParam(patch.getArtistAlias(), StandardCharsets.UTF_8);
        String downloadUrl = "/api/patches/" + patch.getPatchid() + "/download";
        Map<String, Object> structuredData = new LinkedHashMap<>();
        structuredData.put("@context", "https://schema.org");
        structuredData.put("@type", "CreativeWork");
        structuredData.put("name", patch.getPatchName());
        structuredData.put("url", canonicalUrl);
        structuredData.put("description", description);
        structuredData.put("image", imageUrl);
        structuredData.put("author", Map.of("@type", "Person", "name", patch.getArtistAlias(),
                "url", baseUrl + artistUrl));
        if (patch.getDateUploaded() != null) {
            structuredData.put("datePublished", DateTimeFormatter.ISO_INSTANT.format(patch.getDateUploaded()));
        }
        Map<String, Object> encoding = new LinkedHashMap<>();
        encoding.put("@type", "MediaObject");
        encoding.put("name", patch.getFilename());
        encoding.put("contentUrl", baseUrl + downloadUrl);
        if (patch.getFilesize() != null) {
            encoding.put("contentSize", patch.getFilesize() + " bytes");
        }
        structuredData.put("encoding", encoding);
        String ratingPatchData = toJson(Map.of(
                "patchid", patch.getPatchid(),
                "patchName", patch.getPatchName(),
                "artistAlias", patch.getArtistAlias()));
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>%s</title>
                    <meta name="description" content="%s">
                    <meta name="author" content="%s">
                    <meta name="robots" content="index, follow, max-image-preview:large, max-snippet:-1">
                    <link rel="canonical" href="%s">
                    <link rel="icon" href="/images/logo_by_chatgpt.jpg" type="image/jpeg">
                    <meta property="og:type" content="article">
                    <meta property="og:site_name" content="Reason Station">
                    <meta property="og:title" content="%s">
                    <meta property="og:description" content="%s">
                    <meta property="og:url" content="%s">
                    <meta property="og:image" content="%s">
                    <meta property="og:image:alt" content="%s">
                    <meta name="twitter:card" content="summary_large_image">
                    <meta name="twitter:title" content="%s">
                    <meta name="twitter:description" content="%s">
                    <meta name="twitter:image" content="%s">
                    <script type="application/ld+json">%s</script>
                    <link rel="stylesheet" href="/css/styles.css">
                </head>
                <body class="site-page patch-detail-page">
                    <header class="navbar track-detail-header">
                        <div class="container">
                            <a class="logo-title" href="/index.html" aria-label="Reason Station home">
                                <img src="/images/logo_by_chatgpt.jpg" alt="">
                                <span>Reason <b>Station</b></span><span id="header-rs-2">2</span>
                            </a>
                        </div>
                    </header>
                    <main class="container track-detail-main">
                        <nav class="track-detail-breadcrumb" aria-label="Breadcrumb">
                            <a href="/index.html">Home</a><span aria-hidden="true">/</span>
                            <a href="/index.html?view=patches">Patches</a>
                        </nav>
                        <article class="track-card track-detail-card patch-card">
                            %s
                            <div class="track-card-content">
                                <div class="patch-card-heading">
                                    <div class="patch-card-identity">
                                        <h1>%s</h1>
                                        <a class="track-artist-link" href="%s">by %s</a>
                                    </div>
                                    %s
                                </div>
                                <span class="track-style-badge">%s</span>
                                <a class="track-file-meta" href="%s">%s</a>
                                <div class="track-card-meta patch-card-meta">
                                    %s
                                    %s
                                </div>
                            </div>
                            <div class="track-card-actions">
                                <a class="download-track-link" href="%s"
                                   aria-label="Download %s by %s">Download</a>
                                <div id="patch-rating-action" class="track-rating-action"></div>
                            </div>
                        </article>
                    </main>
                    <script src="/js/image_zoom.js"></script>
                    <script id="patch-rating-data" type="application/json">%s</script>
                    <script src="/js/track_rating.js"></script>
                    <script src="/js/copy_link.js"></script>
                    <script>
                        var patch = JSON.parse(document.getElementById('patch-rating-data').textContent);
                        document.getElementById('patch-rating-action').appendChild(
                            window.createRatePatchButton(patch));
                        document.querySelector('.track-card-actions').appendChild(
                            window.createCopyLinkButton('/patches/' + encodeURIComponent(patch.patchid), patch.patchName));
                    </script>
                </body>
                </html>
                """.formatted(
                title, escape(description), escape(patch.getArtistAlias()), escape(canonicalUrl),
                title, escape(description), escape(canonicalUrl), escape(imageUrl),
                escape(patch.getPatchName()), title, escape(description), escape(imageUrl), toJson(structuredData),
                patch.getPatchImgUri() == null
                        ? "<img class=\"track-card-art patch-card-image\" src=\"/images/icon-patch.svg\" alt=\"\">"
                        : "<img class=\"track-card-art patch-card-image\" data-image-zoom role=\"button\" tabindex=\"0\""
                                + " aria-label=\"View larger image: Image for " + escape(patch.getPatchName())
                                + "\" src=\"" + escape(imageUrl) + "\" alt=\"Image for "
                                + escape(patch.getPatchName()) + "\">",
                escape(patch.getPatchName()), escape(artistUrl), escape(patch.getArtistAlias()),
                patch.getDescription() == null || patch.getDescription().isEmpty() ? ""
                        : "<p class=\"patch-description\">Description: " + escape(patch.getDescription()) + "</p>",
                patch.getExt() == null || patch.getExt().isEmpty() ? "Unknown" : escape(patch.getExt()),
                downloadUrl, escape(patch.getFilename()), filesizeHtml(patch.getFilesize()),
                patch.getDateUploaded() == null ? "" : "<time class=\"patch-uploaded-meta\" datetime=\"" + patch.getDateUploaded()
                        + "\">Uploaded: " + DateTimeFormatter.ISO_LOCAL_DATE.withZone(java.time.ZoneOffset.UTC)
                        .format(patch.getDateUploaded()) + "</time>", downloadUrl,
                escape(patch.getPatchName()), escape(patch.getArtistAlias()), ratingPatchData);
    }

    private String filesizeHtml(Long filesize) {
        if (filesize == null || filesize < 0) {
            return "";
        }
        var format = java.text.NumberFormat.getNumberInstance(java.util.Locale.US);
        format.setMaximumFractionDigits(1);
        String size;
        if (filesize < 1024) {
            size = format.format(filesize) + (filesize == 1 ? " byte" : " bytes");
        } else {
            double divisor = filesize < 1024 * 1024 ? 1024.0 : 1024.0 * 1024;
            size = format.format(Math.round(filesize / divisor * 10) / 10.0)
                    + (filesize < 1024 * 1024 ? " Kb" : " Mb");
        }
        return "<span class=\"track-filesize-meta\">Size: " + size + "</span>";
    }

    private String ratingHtml(Double rating) {
        if (rating == null) {
            return "";
        }
        double average = Math.max(0, Math.min(10, rating));
        return String.format(java.util.Locale.ROOT, """
                <span class="track-rating-meta" aria-label="Average rating: %.1f out of 10 stars">
                    <span class="track-rating-stars" aria-hidden="true" style="--rating-percent: %.1f%%"></span>
                    <span class="track-rating-value" aria-hidden="true">%.1f/10</span>
                </span>
                """, average, average * 10, average);
    }

    private String notFoundHtml(String type, String catalog) {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>%s not found | Reason Station</title>
                    <meta name="robots" content="noindex, follow">
                    <link rel="stylesheet" href="/css/styles.css">
                </head>
                <body class="account-page">
                    <main class="account-card">
                        <h1>%s not found</h1>
                        <p>This %s may have been removed.</p>
                        <a class="cta-button" href="/index.html?view=%s">Browse %s</a>
                    </main>
                </body>
                </html>
                """.formatted(type, type, type.toLowerCase(java.util.Locale.ROOT), catalog, catalog);
    }

    private void appendSitemapUrl(StringBuilder xml, String location, String lastModified) {
        xml.append("  <url><loc>").append(escape(location)).append("</loc>");
        if (lastModified != null) {
            xml.append("<lastmod>").append(lastModified).append("</lastmod>");
        }
        xml.append("</url>\n");
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value).replace("</", "<\\/");
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to create structured data.", exception);
        }
    }

    private String escape(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }
}
