package com.app.nino.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AppConfig {

    // Danh sach origin duoc phep goi API, phan cach boi dau phay — cau hinh
    // rieng cho dev (application.yml) va prod (application-prod.yml), KHONG
    // duoc chua "*" (SEC finding: wildcard cho phep MOI website goi API kem
    // Bearer token bi lo qua XSS/log/thiet bi mat, lam vo hieu hoa allowlist
    // domain cu the dung kem no).
    @Value("#{'${app.cors.allowed-origins}'.split(',')}")
    private java.util.List<String> allowedOrigins;

    /** Pool rieng, nho, cho viec luu RequestLog bat dong bo (RequestLogService)
     * — tach khoi cac tac vu async khac de 1 con lag ghi log khong anh huong
     * phan con lai cua he thong. */
    @Bean(name = "requestLogExecutor")
    public Executor requestLogExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("request-log-");
        executor.initialize();
        return executor;
    }

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**")
                    .allowedOriginPatterns(allowedOrigins.toArray(new String[0]))
                    .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                    .allowedHeaders("*")
                    .allowCredentials(false);
            }
        };
    }
}
