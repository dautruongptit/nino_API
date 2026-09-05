package com.app.nino.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

@Slf4j
@Configuration
public class FirebaseConfig {

    @Value("${app.firebase.credentials-path:firebase-service-account.json}")
    private String credentialsPath;

    /**
     * Khoi tao FirebaseApp 1 lan duy nhat khi app start.
     * File service account KHONG duoc commit Git — xem huong dan lay file
     * tai Firebase Console > Project Settings > Service Accounts.
     */
    @PostConstruct
    public void initFirebase() {
        try {
            if (!FirebaseApp.getApps().isEmpty()) {
                log.info("[Firebase] FirebaseApp da duoc khoi tao truoc do, bo qua.");
                return;
            }

            Resource resource = new ClassPathResource(credentialsPath);
            try (InputStream serviceAccount = resource.getInputStream()) {
                FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials
                        .fromStream(serviceAccount)
                        .createScoped(List.of("https://www.googleapis.com/auth/firebase.messaging")))
                    .build();

                FirebaseApp.initializeApp(options);
                log.info("[Firebase] Khoi tao FirebaseApp thanh cong.");
            }
        } catch (IOException e) {
            log.error("[Firebase] KHONG THE khoi tao FirebaseApp — kiem tra file {}: {}",
                credentialsPath, e.getMessage());
            // Khong throw exception — app van chay binh thuong,
            // chi tinh nang push se khong hoat dong (graceful degradation)
        }
    }
}
