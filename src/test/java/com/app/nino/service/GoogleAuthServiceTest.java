package com.app.nino.service;

import com.app.nino.model.entity.Role;
import com.app.nino.model.entity.User;
import com.app.nino.repository.LoginHistoryRepository;
import com.app.nino.repository.RoleRepository;
import com.app.nino.repository.UserRepository;
import com.app.nino.security.JwtTokenProvider;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.GeneralSecurityException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GoogleAuthServiceTest {

    @Mock private UserRepository userRepo;
    @Mock private RoleRepository roleRepo;
    @Mock private LoginHistoryRepository loginHistoryRepo;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private GoogleIdTokenVerifier googleIdTokenVerifier;
    @Mock private GoogleIdToken googleIdToken;
    @Mock private HttpServletRequest httpRequest;

    @InjectMocks
    private GoogleAuthService service;

    private GoogleIdToken.Payload payloadWith(String googleId, String email, String picture) {
        GoogleIdToken.Payload payload = new GoogleIdToken.Payload();
        payload.setSubject(googleId);
        payload.setEmail(email);
        payload.setEmailVerified(true);
        payload.set("name", "Nguyen Van A");
        payload.set("picture", picture);
        return payload;
    }

    @BeforeEach
    void setUp() throws GeneralSecurityException, java.io.IOException {
        lenient().when(googleIdTokenVerifier.verify(anyString())).thenReturn(googleIdToken);
        lenient().when(jwtTokenProvider.generateAccessToken(any(), any(), any())).thenReturn("access-token");
        lenient().when(jwtTokenProvider.generateRefreshToken(any(), any())).thenReturn("refresh-token");
    }

    @Test
    void loginWithGoogle_newUser_savesAvatarUrlFromGoogle() {
        when(googleIdToken.getPayload()).thenReturn(
            payloadWith("g-1", "new@example.com", "https://google.com/avatar-new.jpg"));
        when(userRepo.findByGoogleId("g-1")).thenReturn(Optional.empty());
        when(userRepo.findByEmail("new@example.com")).thenReturn(Optional.empty());
        when(roleRepo.findByName("ROLE_USER")).thenReturn(Optional.of(Role.builder().id(1L).name("ROLE_USER").build()));
        when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        service.loginWithGoogle("id-token", httpRequest, null);

        verify(userRepo, atLeastOnce()).save(argThat(u ->
            "https://google.com/avatar-new.jpg".equals(u.getAvatarUrl())));
    }

    @Test
    void loginWithGoogle_returningUser_refreshesAvatarUrlFromGoogle() {
        User existing = User.builder()
            .id(5L)
            .googleId("g-2")
            .email("old@example.com")
            .fullName("Nguyen Van A")
            .avatarUrl("https://google.com/avatar-old.jpg")
            .status("ACT")
            .roles(new HashSet<>(Set.of(Role.builder().id(1L).name("ROLE_USER").build())))
            .totalLoginCount(3)
            .build();

        when(googleIdToken.getPayload()).thenReturn(
            payloadWith("g-2", "old@example.com", "https://google.com/avatar-changed.jpg"));
        when(userRepo.findByGoogleId("g-2")).thenReturn(Optional.of(existing));
        when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        service.loginWithGoogle("id-token", httpRequest, null);

        assertEquals("https://google.com/avatar-changed.jpg", existing.getAvatarUrl());
        verify(userRepo, atLeastOnce()).save(argThat(u ->
            "https://google.com/avatar-changed.jpg".equals(u.getAvatarUrl())));
    }

    @Test
    void loginWithGoogle_withDeviceName_savesItOnLoginHistory() throws Exception {
        when(googleIdToken.getPayload()).thenReturn(payloadWith("g-1", "a@b.com", "pic.jpg"));
        User user = User.builder().id(1L).email("a@b.com")
            .authProvider(User.AuthProvider.GOOGLE).status("ACT")
            .roles(new HashSet<>()).build();
        when(userRepo.findByGoogleId("g-1")).thenReturn(Optional.of(user));
        when(jwtTokenProvider.getRefreshExpirationMs()).thenReturn(604_800_000L);

        service.loginWithGoogle("id-token", httpRequest, "iPhone 15 Pro");

        org.mockito.ArgumentCaptor<com.app.nino.model.entity.LoginHistory> captor =
            org.mockito.ArgumentCaptor.forClass(com.app.nino.model.entity.LoginHistory.class);
        verify(loginHistoryRepo).save(captor.capture());
        assertEquals("iPhone 15 Pro", captor.getValue().getDeviceName());
        assertNotNull(captor.getValue().getSessionId());
        assertNotNull(captor.getValue().getRefreshExpiresAt());
    }
}
