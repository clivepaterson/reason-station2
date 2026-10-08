package org.reasonstation.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .components(new Components()
                        .addSecuritySchemes("local-oidc", new SecurityScheme()
                                .type(SecurityScheme.Type.OAUTH2)
                                .flows(new OAuthFlows()
                                        .authorizationCode(new OAuthFlow()
                                                .authorizationUrl("http://localhost:8080/oauth2/authorize")
                                                .tokenUrl("http://localhost:8080/oauth2/token")
                                                .scopes(new Scopes()
                                                        .addString("openid", "OpenID Connect sign-in")
                                                        .addString("profile", "User profile")
                                                        .addString("email", "User email"))))))
                .addSecurityItem(new SecurityRequirement().addList("local-oidc"))
                .info(new Info()
                        .title("ReasonStation API")
                        .version("1.0")
                        .description("API documentation for ReasonStation application"));
    }
}
