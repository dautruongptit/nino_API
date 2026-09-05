package com.app.nino.config;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;

/**
 * Khoi tao GoogleIdTokenVerifier dung de xac thuc idToken tu Google Sign-In SDK
 * (endpoint POST /auth/google). "app.google.client-id" phai khop voi OAuth 2.0
 * Client ID cau hinh tren Google Cloud Console — day cung la "audience" bat buoc
 * cua token, tranh token phat hanh cho app khac bi chap nhan nham.
 */
@Configuration
public class GoogleAuthConfig {

    @Value("${app.google.client-id}")
    private String googleClientId;

    @Bean
    public GoogleIdTokenVerifier googleIdTokenVerifier() throws Exception {
        return new GoogleIdTokenVerifier.Builder(
                GoogleNetHttpTransport.newTrustedTransport(), GsonFactory.getDefaultInstance())
            .setAudience(Collections.singletonList(googleClientId))
            .build();
    }
}
