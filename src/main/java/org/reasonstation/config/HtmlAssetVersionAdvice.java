package org.reasonstation.config;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@ControllerAdvice
public class HtmlAssetVersionAdvice implements ResponseBodyAdvice<String> {
    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return StringHttpMessageConverter.class.isAssignableFrom(converterType);
    }

    @Override
    public String beforeBodyWrite(String body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body != null && MediaType.TEXT_HTML.isCompatibleWith(contentType)
                && request instanceof ServletServerHttpRequest servletRequest) {
            return HtmlAssetVersionTransformer.versionAssets(servletRequest.getServletRequest(), body);
        }
        return body;
    }
}
