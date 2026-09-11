package com.app.nino.service;

import com.app.nino.exception.ResourceNotFoundException;
import com.app.nino.exception.UnauthorizedException;
import com.app.nino.model.dto.response.AuthResponse;
import com.app.nino.model.entity.LoginHistory;
import com.app.nino.model.entity.Role;
import com.app.nino.model.entity.User;
import com.app.nino.repository.LoginHistoryRepository;
import com.app.nino.repository.RoleRepository;
import com.app.nino.repository.UserRepository;
import com.app.nino.security.JwtTokenProvider;
import com.app.nino.util.DeviceParser;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * Dang nhap / dang ky bang Google Sign-In (POST /auth/google).
 * Client gui idToken lay tu Google Sign-In SDK, server xac thuc idToken voi
 * Google roi tim-hoac-tao User tuong ung, cuoi cung phat hanh JWT cua he thong
 * (khong dung truc tiep idToken cua Google lam access token).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleAuthService {

    private final UserRepository         userRepo;
    private final RoleRepository         roleRepo;
    private final LoginHistoryRepository loginHistoryRepo;
    private final JwtTokenProvider       jwtTokenProvider;
    private final GoogleIdTokenVerifier  googleIdTokenVerifier;

    @Transactional
    public AuthResponse loginWithGoogle(String idToken, HttpServletRequest httpRequest, String deviceName) {
        log.info("[GoogleAuth] Login attempt voi Google idToken");

        GoogleIdToken.Payload payload = verifyIdToken(idToken);

        // Khong tin claim email neu Google chua tu xac thuc no — tranh gia mao email
        if (!Boolean.TRUE.equals(payload.getEmailVerified())) {
            log.warn("[GoogleAuth] Login that bai — email Google chua xac thuc: email={}", payload.getEmail());
            throw new UnauthorizedException("Email Google chua duoc xac thuc");
        }

        String googleId = payload.getSubject();
        String email     = payload.getEmail();
        String fullName  = (String) payload.get("name");
        String avatarUrl = (String) payload.get("picture");
        log.debug("[GoogleAuth] idToken hop le: email={} googleId={}", email, googleId);

        User user = userRepo.findByGoogleId(googleId).orElse(null);
        if (user == null) {
            // KHONG tu dong lien ket voi tai khoan email/password co san — neu lam vay,
            // bat ky ai co Google account trung email se chiem duoc tai khoan do ma
            // khong can biet mat khau (account takeover). Chi cho phep tao moi.
            if (userRepo.findByEmail(email).isPresent()) {
                log.warn("[GoogleAuth] Login that bai — email da dang ky bang mat khau: email={}", email);
                throw new UnauthorizedException(
                    "Email nay da duoc dang ky bang mat khau. Vui long dang nhap bang email/mat khau.");
            }
            user = createGoogleUser(googleId, email, fullName, avatarUrl);
            log.info("[GoogleAuth] Tao user moi tu Google: userId={} email={}", user.getId(), email);
        } else if (avatarUrl != null && !avatarUrl.equals(user.getAvatarUrl())) {
            // Anh dai dien Google co the doi theo thoi gian -> dong bo lai moi lan login
            user.setAvatarUrl(avatarUrl);
        }

        if (!user.canLogin()) {
            log.warn("[GoogleAuth] Login that bai — tai khoan chua kich hoat/bi khoa: userId={}", user.getId());
            throw new UnauthorizedException("Tai khoan chua duoc kich hoat hoac da bi khoa");
        }

        String ip = DeviceParser.getClientIp(httpRequest);
        handleSuccessLogin(user, ip);
        saveLoginHistory(user, ip, httpRequest.getHeader("User-Agent"), deviceName);
        log.info("[GoogleAuth] Login thanh cong: userId={} ip={}", user.getId(), ip);

        String sid = java.util.UUID.randomUUID().toString();
        String accessToken  = jwtTokenProvider.generateAccessToken(user.getId(), user.getRoles(), sid);
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), sid);

        return AuthResponse.builder()
            .accessToken(accessToken).refreshToken(refreshToken)
            .userId(user.getId()).fullName(user.getFullName()).email(user.getEmail())
            .build();
    }

    private GoogleIdToken.Payload verifyIdToken(String idToken) {
        try {
            GoogleIdToken token = googleIdTokenVerifier.verify(idToken);
            if (token == null) {
                log.warn("[GoogleAuth] idToken khong hop le hoac da het han (verify tra ve null)");
                throw new UnauthorizedException("idToken Google khong hop le hoac da het han");
            }
            return token.getPayload();
        } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
            // IllegalArgumentException: idToken khong dung dinh dang JWT/Base64 (client gui rac)
            log.warn("[GoogleAuth] idToken khong hop le: {}", e.getMessage());
            throw new UnauthorizedException("idToken Google khong hop le hoac da het han");
        }
    }

    private User createGoogleUser(String googleId, String email, String fullName, String avatarUrl) {
        Role userRole = roleRepo.findByName("ROLE_USER")
            .orElseThrow(() -> new ResourceNotFoundException("Role", "ROLE_USER"));

        User user = User.builder()
            .username(email)  // username chua dung toi noi khac — dung email cho chac chan unique
            .fullName(fullName != null ? fullName : email)
            .email(email)
            .googleId(googleId)
            .authProvider(User.AuthProvider.GOOGLE)
            .status("ACT")            // email da duoc Google xac thuc san
            .avatarUrl(avatarUrl)
            // Set.of() tra ve collection immutable — Hibernate can .clear() collection
            // nay khi merge entity (vd handleSuccessLogin save lai ngay sau khi tao),
            // gay UnsupportedOperationException. Phai bọc bang collection mutable.
            .roles(new HashSet<>(Set.of(userRole)))
            .build();
        return userRepo.save(user);
    }

    private void handleSuccessLogin(User user, String ip) {
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(LocalDateTime.now());
        user.setTotalLoginCount(user.getTotalLoginCount() + 1);
        user.setLastLoginIp(ip);
        userRepo.save(user);
    }

    private void saveLoginHistory(User user, String ip, String userAgent, String deviceName) {
        LoginHistory history = LoginHistory.builder()
            .user(user)
            .ipAddress(ip)
            .userAgent(userAgent)
            .deviceType(DeviceParser.parseDeviceType(userAgent))
            .deviceName(deviceName)
            .os(DeviceParser.parseOs(userAgent))
            .browser(DeviceParser.parseBrowser(userAgent))
            .isSuccess(true)
            .build();
        loginHistoryRepo.save(history);
    }
}
