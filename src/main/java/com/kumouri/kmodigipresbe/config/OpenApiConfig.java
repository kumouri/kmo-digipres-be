package com.kumouri.kmodigipresbe.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Springdoc OpenAPI configuration for the KMOSF CRM backend.
 *
 * <p>The published spec is committed at {@code docs/api/openapi.json} and verified on
 * every build via the {@code verifyOpenApi} Gradle task (which depends on
 * {@code generateOpenApiDocs}).  If the spec in the repo differs from what the app
 * generates, the build fails with an actionable message.
 *
 * <p>The spec is served unauthenticated at {@code /api/v1/openapi} (redirect from
 * {@code /api/v1/v3/api-docs}) and the Swagger UI at {@code /api/v1/swagger-ui.html}.
 * Both paths are explicitly permitted in {@link SecurityConfig}.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI kmosOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("KMOSF CRM API")
                        .description("KMO Solutions Foundry CRM — multi-tenant back-office platform")
                        .version("v1"))
                .addServersItem(new Server().url("/api/v1").description("Current versioned base path"))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("HS256 JWT issued by POST /api/v1/auth/login")));
    }
}
