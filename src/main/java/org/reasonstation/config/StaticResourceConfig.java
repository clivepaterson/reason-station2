package org.reasonstation.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.VersionResourceResolver;

import java.time.Duration;

@Configuration
public class StaticResourceConfig implements WebMvcConfigurer {
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        CacheControl cacheControl = CacheControl.maxAge(Duration.ofDays(1)).cachePublic();
        for (String directory : new String[]{"css", "js"}) {
            registry.addResourceHandler("/" + directory + "/**")
                    .addResourceLocations("classpath:/static/" + directory + "/")
                    .setCacheControl(cacheControl)
                    .resourceChain(true)
                    .addResolver(new VersionResourceResolver().addContentVersionStrategy("/**"));
        }
        registry.addResourceHandler("/images/**")
                .addResourceLocations("classpath:/static/images/")
                .setCacheControl(cacheControl);
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noStore())
                // HTML must be refreshed even when only a referenced asset has changed.
                .setUseLastModified(false)
                .resourceChain(true)
                .addTransformer(new HtmlAssetVersionTransformer());
    }
}
