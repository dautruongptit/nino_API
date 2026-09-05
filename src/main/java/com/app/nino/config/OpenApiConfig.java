package com.app.nino.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Value("${app.openapi.dev-url:http://localhost:8080}")
    private String devUrl;

    @Value("${app.openapi.prod-url:https://api.nhacsu.app}")
    private String prodUrl;

    private static final String SECURITY_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI ninoOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Nino API")
                        .description("Backend API cho ung dung quan ly su kien va nhac nho")
                        .version("v2.3.0")
                        .contact(new Contact()
                                .name("Dev Team")
                                .email("dev@nhacsu.app"))
                        .license(new License()
                                .name("MIT")
                                .url("https://opensource.org/licenses/MIT")))
                .servers(List.of(
                        new Server().url(devUrl).description("Development"),
                        new Server().url(prodUrl).description("Production")))
                // Khai bao JWT Bearer scheme toan cuc
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME,
                                new SecurityScheme()
                                        .name(SECURITY_SCHEME)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description(
                                                "Nhap Access Token vao day. Format: Bearer <token>")))
                // Ap dung bearer auth cho toan bo API (tru /auth/**)
                .addSecurityItem(new SecurityRequirement()
                        .addList(SECURITY_SCHEME));
    }

}

