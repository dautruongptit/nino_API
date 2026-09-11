package com.app.nino.service;

import com.app.nino.exception.BadRequestException;
import com.app.nino.exception.ResourceNotFoundException;
import com.app.nino.exception.UnauthorizedException;
import com.app.nino.model.dto.request.LoginRequest;
import com.app.nino.model.dto.request.RegisterRequest;
import com.app.nino.model.dto.request.UpdateProfileRequest;
import com.app.nino.model.dto.request.UpdateSettingsRequest;
import com.app.nino.model.dto.response.AuthResponse;
import com.app.nino.model.dto.response.LoginHistoryResponse;
import com.app.nino.model.dto.response.UserProfileResponse;
import com.app.nino.model.entity.LoginHistory;
import com.app.nino.model.entity.Role;
import com.app.nino.model.entity.User;
import com.app.nino.repository.LoginHistoryRepository;
import com.app.nino.repository.RoleRepository;
import com.app.nino.repository.UserDeviceRepository;
import com.app.nino.repository.UserRepository;
import com.app.nino.security.JwtTokenProvider;
import com.app.nino.security.TokenBlacklistService;
import com.app.nino.util.DeviceParser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int LOCK_DURATION_MINUTES = 30;
    private static final String UPLOAD_DIR = "uploads/avatars/";

    // Whitelist content-type -> extension. Ten file luu tren dia luon do server
    // sinh ra tu day, KHONG bao gio dung ten/duoi file client gui len — vua chan
    // path traversal, vua chan upload file khong phai anh (html/svg co script...).
    private static final Map<String, String> ALLOWED_AVATAR_TYPES = Map.of(
        "image/jpeg", ".jpg",
        "image/png",  ".png",
        "image/webp", ".webp",
        "image/gif",  ".gif"
    );

    private final UserRepository         userRepo;
    private final RoleRepository         roleRepo;
    private final LoginHistoryRepository loginHistoryRepo;
    private final UserDeviceRepository   userDeviceRepo;        // NEW
    private final PasswordEncoder        passwordEncoder;
    private final JwtTokenProvider       jwtTokenProvider;
    private final TokenBlacklistService  tokenBlacklistService; // NEW

    // ── REGISTER ──────────────────────────────────────────────────────────────
    @Transactional
    public AuthResponse register(RegisterRequest req) {
        log.info("[Auth] Dang ky moi: email={}", req.getEmail());

        if (userRepo.existsByEmail(req.getEmail())) {
            log.warn("[Auth] Dang ky that bai — email da ton tai: email={}", req.getEmail());
            throw new BadRequestException("Email đã được sử dụng");
        }

        Role userRole = roleRepo.findByName("ROLE_USER")
            .orElseThrow(() -> new ResourceNotFoundException("Role", "ROLE_USER"));

        User user = User.builder()
            .username(req.getEmail())  // username chua dung toi noi khac — dung email cho chac chan unique
            .fullName(req.getFullName())
            .email(req.getEmail())
            .passwordHash(passwordEncoder.encode(req.getPassword()))
            .status("ACT")  // Chua co ha tang xac minh email — kich hoat ngay khi register
            // HashSet (khong dung Set.of()) — Hibernate can goi .clear() tren
            // collection nay khi merge/update entity ve sau; Set.of() la immutable
            // se nem UnsupportedOperationException luc do.
            .roles(new HashSet<>(Set.of(userRole)))
            .build();

        userRepo.save(user);
        log.info("[Auth] Dang ky thanh cong: userId={} email={}", user.getId(), user.getEmail());

        String sid = UUID.randomUUID().toString();
        String accessToken  = jwtTokenProvider.generateAccessToken(user.getId(), user.getRoles(), sid);
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), sid);

        return AuthResponse.builder()
            .accessToken(accessToken).refreshToken(refreshToken)
            .userId(user.getId()).fullName(user.getFullName()).email(user.getEmail())
            .build();
    }

    // ── LOGIN ─────────────────────────────────────────────────────────────────
    @Transactional
    public AuthResponse login(LoginRequest req, HttpServletRequest httpRequest) {
        log.info("[Auth] Login attempt: email={}", req.getEmail());

        User user = userRepo.findByEmail(req.getEmail())
            .orElseThrow(() -> {
                log.warn("[Auth] Login that bai — email khong ton tai: email={}", req.getEmail());
                return new UnauthorizedException("Email hoặc mật khẩu không đúng");
            });

        String ip = DeviceParser.getClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        if (!user.canLogin()) {
            log.warn("[Auth] Login that bai — tai khoan chua kich hoat: userId={}", user.getId());
            saveLoginHistory(user, ip, userAgent, req.getDeviceName(), false, LoginHistory.FailureReason.ACCOUNT_INACTIVE, null, null);
            throw new UnauthorizedException("Tài khoản chưa được kích hoạt");
        }

        if (user.isCurrentlyLocked()) {
            log.warn("[Auth] Login that bai — tai khoan dang bi khoa: userId={} unlockInMinutes={}",
                user.getId(), user.getMinutesUntilUnlock());
            saveLoginHistory(user, ip, userAgent, req.getDeviceName(), false, LoginHistory.FailureReason.ACCOUNT_LOCKED, null, null);
            throw new UnauthorizedException(
                "Tài khoản đang bị khóa, thử lại sau " + user.getMinutesUntilUnlock() + " phút");
        }

        if (user.getPasswordHash() == null
                || !passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            handleFailedLogin(user);
            saveLoginHistory(user, ip, userAgent, req.getDeviceName(), false, LoginHistory.FailureReason.WRONG_PASSWORD, null, null);
            log.warn("[Auth] Login that bai — sai mat khau: userId={} failedCount={}",
                user.getId(), user.getFailedLoginCount());
            throw new UnauthorizedException("Email hoặc mật khẩu không đúng");
        }

        String sid = UUID.randomUUID().toString();
        LocalDateTime refreshExpiresAt = LocalDateTime.now().plus(Duration.ofMillis(jwtTokenProvider.getRefreshExpirationMs()));
        handleSuccessLogin(user, ip);
        saveLoginHistory(user, ip, userAgent, req.getDeviceName(), true, null, sid, refreshExpiresAt);
        log.info("[Auth] Login thanh cong: userId={} ip={}", user.getId(), ip);

        String accessToken  = jwtTokenProvider.generateAccessToken(user.getId(), user.getRoles(), sid);
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), sid);

        return AuthResponse.builder()
            .accessToken(accessToken).refreshToken(refreshToken)
            .userId(user.getId()).fullName(user.getFullName()).email(user.getEmail())
            .build();
    }

    // ── REFRESH TOKEN ─────────────────────────────────────────────────────────
    @Transactional
    public AuthResponse refreshToken(String refreshToken) {
        if (!jwtTokenProvider.validateToken(refreshToken)
                || !"refresh".equals(jwtTokenProvider.getTokenType(refreshToken))) {
            log.warn("[Auth] Refresh token khong hop le hoac sai loai token");
            throw new UnauthorizedException("Refresh token không hợp lệ hoặc đã hết hạn");
        }

        Long userId = jwtTokenProvider.getUserId(refreshToken);
        String sid = jwtTokenProvider.getSid(refreshToken);
        User user = userRepo.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        // sid da bi thu hoi (dang xuat tu xa) nhung Redis chua kip phan anh —
        // hang phong thu thu 2 ben canh validateToken() da kiem tra blacklist.
        loginHistoryRepo.findBySessionId(sid).ifPresent(history -> {
            if (history.getRevokedAt() != null) {
                throw new UnauthorizedException("Phiên đăng nhập đã bị thu hồi");
            }
            history.setRefreshExpiresAt(
                LocalDateTime.now().plus(Duration.ofMillis(jwtTokenProvider.getRefreshExpirationMs())));
            loginHistoryRepo.save(history);
        });

        String newAccessToken  = jwtTokenProvider.generateAccessToken(user.getId(), user.getRoles(), sid);
        String newRefreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), sid);
        log.info("[Auth] Refresh token thanh cong: userId={} sid={}", userId, sid);

        return AuthResponse.builder()
            .accessToken(newAccessToken).refreshToken(newRefreshToken)
            .userId(user.getId()).fullName(user.getFullName()).email(user.getEmail())
            .build();
    }

    // ── LOGOUT ────────────────────────────────────────────────────────────────
    // Thu hoi ngay ca access lan refresh token cua PHIEN NAY (theo sid, khong
    // phai tung chuoi token rieng le) — nen van hoat dong dung ke ca sau khi
    // client da /auth/refresh nhieu lan. Refresh token (neu con hop le) song
    // lau hon access token nen TTL blacklist uu tien lay tu no.
    @Transactional
    public void logout(Long userId, String accessToken, String refreshToken, String fcmToken) {
        String sid = null;
        if (accessToken != null) {
            sid = jwtTokenProvider.getSid(accessToken);
        }
        Duration ttl = Duration.ZERO;
        boolean refreshValid = refreshToken != null && jwtTokenProvider.validateToken(refreshToken);
        if (refreshValid) {
            if (sid == null) sid = jwtTokenProvider.getSid(refreshToken);
            ttl = jwtTokenProvider.getRemainingValidity(refreshToken);
        } else if (accessToken != null) {
            ttl = jwtTokenProvider.getRemainingValidity(accessToken);
        }
        if (sid != null) {
            tokenBlacklistService.blacklist(sid, ttl);
            loginHistoryRepo.findBySessionId(sid).ifPresent(history -> {
                history.setRevokedAt(LocalDateTime.now());
                loginHistoryRepo.save(history);
            });
        }
        if (fcmToken != null) {
            userDeviceRepo.deleteByFcmTokenAndUserId(fcmToken, userId);
        }
        log.info("[Auth] Dang xuat: userId={} sid={} huyThietBi={}", userId, sid, fcmToken != null);
    }

    // ── PROFILE — cache 30 phút ──────────────────────────────────────────────
    @Cacheable(value = "userProfile", key = "#userId")
    public UserProfileResponse getProfile(Long userId) {
        User user = userRepo.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        return UserProfileResponse.from(user);
    }

    @CacheEvict(value = "userProfile", key = "#userId")
    @Transactional
    public UserProfileResponse updateProfile(Long userId, UpdateProfileRequest req) {
        User user = userRepo.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        user.setFullName(req.getFullName());
        user.setPhone(req.getPhone());
        user.setGender(req.getGender() != null ? User.Gender.valueOf(req.getGender()) : null);
        user.setBirthMonth(req.getBirthMonth());
        user.setBirthDay(req.getBirthDay());
        user.setBirthYear(req.getBirthYear());
        return UserProfileResponse.from(userRepo.save(user));
    }

    @CacheEvict(value = "userProfile", key = "#userId")
    @Transactional
    public UserProfileResponse updateSettings(Long userId, UpdateSettingsRequest req) {
        User user = userRepo.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        if (req.getLanguage() != null) user.setLanguage(req.getLanguage());
        if (req.getDarkMode() != null) user.setDarkMode(req.getDarkMode());
        return UserProfileResponse.from(userRepo.save(user));
    }

    @CacheEvict(value = "userProfile", key = "#userId")
    @Transactional
    public UserProfileResponse uploadAvatar(Long userId, MultipartFile file) {
        User user = userRepo.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        String extension = ALLOWED_AVATAR_TYPES.get(file.getContentType());
        if (extension == null) {
            throw new BadRequestException("Chỉ chấp nhận ảnh JPEG/PNG/WEBP/GIF");
        }

        try {
            Path uploadDir = Paths.get(UPLOAD_DIR).toAbsolutePath().normalize();
            Files.createDirectories(uploadDir);

            // Ten file hoan toan do server sinh (UUID + duoi tu content-type da whitelist)
            // — khong dung filename client gui len nen khong the path-traversal.
            String filename = UUID.randomUUID() + extension;
            Path path = uploadDir.resolve(filename).normalize();
            if (!path.getParent().equals(uploadDir)) {
                throw new BadRequestException("Tên file không hợp lệ");
            }

            Files.copy(file.getInputStream(), path);
            user.setAvatarUrl("/uploads/avatars/" + filename);
        } catch (IOException e) {
            log.error("[Auth] Upload avatar that bai: userId={}", userId, e);
            throw new BadRequestException("Không thể upload avatar: " + e.getMessage());
        }

        return UserProfileResponse.from(userRepo.save(user));
    }

    // ── LOGIN HISTORY ─────────────────────────────────────────────────────────
    public Page<LoginHistoryResponse> getLoginHistory(Long userId, int page, int size) {
        return loginHistoryRepo.findByUserIdOrderByLoginAtDesc(userId, PageRequest.of(page, size))
            .map(LoginHistoryResponse::from);
    }

    // ── GOOGLE CALENDAR (placeholder — chưa implement logic đồng bộ thật) ──────
    @Transactional
    public void connectGoogleCalendar(Long userId, String authCode) {
        User user = userRepo.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        // TODO: exchange authCode voi Google OAuth2 de lay token that (SEC backlog)
        user.setGoogleCalendarToken(authCode);
        userRepo.save(user);
    }

    // ── ADMIN ─────────────────────────────────────────────────────────────────
    public Page<UserProfileResponse> getAllUsers(int page, int size) {
        return userRepo.findAll(PageRequest.of(page, size))
            .map(UserProfileResponse::from);
    }

    @Transactional
    public void deactivateUser(Long targetUserId, Long adminUserId) {
        if (targetUserId.equals(adminUserId)) {
            throw new BadRequestException("Không thể tự khóa chính mình");
        }
        User user = userRepo.findById(targetUserId)
            .orElseThrow(() -> new ResourceNotFoundException("User", targetUserId));
        user.setStatus("INA");
        userRepo.save(user);
        log.info("[Auth] Admin khoa tai khoan: targetUserId={} adminUserId={}", targetUserId, adminUserId);
    }

    @Transactional
    public void grantAdminRole(Long targetUserId) {
        User user = userRepo.findById(targetUserId)
            .orElseThrow(() -> new ResourceNotFoundException("User", targetUserId));
        Role adminRole = roleRepo.findByName("ROLE_ADMIN")
            .orElseThrow(() -> new ResourceNotFoundException("Role", "ROLE_ADMIN"));
        user.getRoles().add(adminRole);
        userRepo.save(user);
        log.info("[Auth] Cap quyen admin: targetUserId={}", targetUserId);
    }

    // ── HELPERS — Login tracking ─────────────────────────────────────────────

    private void handleFailedLogin(User user) {
        user.setFailedLoginCount(user.getFailedLoginCount() + 1);
        user.setLastFailedAt(LocalDateTime.now());
        if (user.getFailedLoginCount() >= MAX_FAILED_ATTEMPTS) {
            user.setLockedUntil(LocalDateTime.now().plusMinutes(LOCK_DURATION_MINUTES));
            log.warn("[Auth] Tai khoan bi khoa do dang nhap sai qua {} lan: userId={}",
                MAX_FAILED_ATTEMPTS, user.getId());
        }
        userRepo.save(user);
    }

    private void handleSuccessLogin(User user, String ip) {
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(LocalDateTime.now());
        user.setTotalLoginCount(user.getTotalLoginCount() + 1);
        user.setLastLoginIp(ip);
        userRepo.save(user);
    }

    private void saveLoginHistory(User user, String ip, String userAgent, String deviceName,
                                   boolean success, LoginHistory.FailureReason reason,
                                   String sessionId, LocalDateTime refreshExpiresAt) {
        LoginHistory history = LoginHistory.builder()
            .user(user)
            .ipAddress(ip)
            .userAgent(userAgent)
            .deviceType(DeviceParser.parseDeviceType(userAgent))
            .deviceName(deviceName)
            .os(DeviceParser.parseOs(userAgent))
            .browser(DeviceParser.parseBrowser(userAgent))
            .isSuccess(success)
            .failureReason(reason)
            .sessionId(sessionId)
            .refreshExpiresAt(refreshExpiresAt)
            .build();
        loginHistoryRepo.save(history);
    }
}
