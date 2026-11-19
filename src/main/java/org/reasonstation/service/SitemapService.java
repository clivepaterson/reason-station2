package org.reasonstation.service;

import org.reasonstation.repository.SitemapEntry;
import org.reasonstation.repository.SitemapRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.LongSupplier;

@Service
public class SitemapService {
    private static final DateTimeFormatter LAST_MODIFIED =
            DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    private final SitemapRepository repository;
    private final SingleFlightCache<String, String> cache;

    @Autowired
    public SitemapService(SitemapRepository repository,
                          @Value("${app.sitemap.cache-ttl:PT5M}") Duration ttl) {
        this(repository, ttl.toMillis(), System::nanoTime);
    }

    public SitemapService(SitemapRepository repository, long ttlMillis) {
        this(repository, ttlMillis, System::nanoTime);
    }

    SitemapService(SitemapRepository repository, long ttlMillis, LongSupplier ticker) {
        this.repository = repository;
        this.cache = new SingleFlightCache<>(ttlMillis, ticker);
    }

    public String sitemap(String baseUrl) {
        return cache.get(baseUrl, () -> render(baseUrl));
    }

    private String render(String baseUrl) {
        StringBuilder xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                """);
        List.of(
                "/index.html", "/index.html?view=artists", "/index.html?view=tracks",
                "/index.html?view=patches", "/index.html?view=tutorials",
                "/index.html?view=archived-songs", "/index.html?view=archived-refills",
                "/index.html?view=links", "/index.html?view=about"
        ).forEach(path -> appendUrl(xml, baseUrl + path, null));
        repository.findTracks().forEach(entry -> appendUrl(xml, baseUrl + "/tracks/" + entry.id(), entry));
        repository.findPatches().forEach(entry -> appendUrl(xml, baseUrl + "/patches/" + entry.id(), entry));
        return xml.append("</urlset>\n").toString();
    }

    private void appendUrl(StringBuilder xml, String location, SitemapEntry entry) {
        xml.append("  <url><loc>").append(HtmlUtils.htmlEscape(location)).append("</loc>");
        if (entry != null && entry.dateUploaded() != null) {
            xml.append("<lastmod>").append(LAST_MODIFIED.format(entry.dateUploaded())).append("</lastmod>");
        }
        xml.append("</url>\n");
    }
}
