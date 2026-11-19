package org.reasonstation.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;
import org.springframework.util.StreamUtils;
import org.springframework.web.servlet.resource.ResourceTransformer;
import org.springframework.web.servlet.resource.ResourceTransformerChain;
import org.springframework.web.servlet.resource.ResourceUrlProvider;
import org.springframework.web.servlet.resource.ResourceUrlProviderExposingInterceptor;
import org.springframework.web.servlet.resource.TransformedResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

public class HtmlAssetVersionTransformer implements ResourceTransformer {
    private static final Pattern ASSET_ATTRIBUTE = Pattern.compile(
            "\\b(?:src|href)\\s*=\\s*([\"'])(/(?:css|js)/[^\"'?#]+\\.(?:css|js))([?#][^\"']*)?\\1");

    @Override
    public Resource transform(HttpServletRequest request, Resource resource,
                              ResourceTransformerChain chain) throws IOException {
        Resource transformed = chain.transform(request, resource);
        String filename = transformed.getFilename();
        if (filename == null || !filename.endsWith(".html")) {
            return transformed;
        }
        String html;
        try (var input = transformed.getInputStream()) {
            html = StreamUtils.copyToString(input, StandardCharsets.UTF_8);
        }
        return new TransformedResource(transformed, versionAssets(request, html).getBytes(StandardCharsets.UTF_8));
    }

    static String versionAssets(HttpServletRequest request, String html) {
        var provider = request.getAttribute(ResourceUrlProviderExposingInterceptor.RESOURCE_URL_PROVIDER_ATTR);
        Assert.state(provider instanceof ResourceUrlProvider, "MVC resource URL provider is required");
        ResourceUrlProvider urls = (ResourceUrlProvider) provider;
        return ASSET_ATTRIBUTE.matcher(html).replaceAll(match -> {
            String versioned = urls.getForLookupPath(match.group(2));
            Assert.state(versioned != null, "Static asset not found: " + match.group(2));
            return java.util.regex.Matcher.quoteReplacement(match.group().replace(match.group(2), versioned));
        });
    }
}
