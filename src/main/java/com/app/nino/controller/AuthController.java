package com.app.nino.controller;

import com.app.nino.model.dto.request.GoogleLoginRequest;
import com.app.nino.model.dto.request.LoginRequest;
import com.app.nino.model.dto.request.LogoutRequest;
import com.app.nino.model.dto.request.RefreshTokenRequest;
import com.app.nino.model.dto.request.RegisterRequest;
import com.app.nino.model.dto.response.BaseResponse;
import com.app.nino.service.AuthService;
import com.app.nino.service.GoogleAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Đăng ký, đăng nhập, refresh token, logout")
public class AuthController {

    private final AuthService        authService;
    private final GoogleAuthService  googleAuthService;

    @PostMapping("/register")
    @Operation(summary = "Đăng ký tài khoản mới")
    public ResponseEntity<BaseResponse<?>> register(@Valid @RequestBody RegisterRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(BaseResponse.success(authService.register(req)));
    }

    @PostMapping("/login")
    @Operation(summary = "Đăng nhập bằng email/mật khẩu")
    public ResponseEntity<BaseResponse<?>> login(
            @Valid @RequestBody LoginRequest req,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
            BaseResponse.success(authService.login(req, httpRequest)));
    }

    @PostMapping("/google")
    @Operation(summary = "Đăng nhập / đăng ký bằng Google",
               description = "Client gửi idToken lấy từ Google Sign-In SDK.")
    public ResponseEntity<BaseResponse<?>> loginWithGoogle(
            @Valid @RequestBody GoogleLoginRequest req,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
            BaseResponse.success(googleAuthService.loginWithGoogle(req.getIdToken(), httpRequest)));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Làm mới access token")
    public ResponseEntity<BaseResponse<?>> refresh(@Valid @RequestBody RefreshTokenRequest req) {
        return ResponseEntity.ok(
            BaseResponse.success(authService.refreshToken(req.getRefreshToken())));
    }

    @PostMapping("/logout")
    @Operation(summary = "Đăng xuất",
               description = "Thu hồi ngay access token hiện tại (và refresh token/thiết bị nếu gửi kèm trong body).")
    public ResponseEntity<BaseResponse<?>> logout(
            @AuthenticationPrincipal Long userId,
            @RequestBody(required = false) LogoutRequest req,
            HttpServletRequest httpRequest) {
        authService.logout(
            userId,
            extractBearerToken(httpRequest),
            req != null ? req.getRefreshToken() : null,
            req != null ? req.getFcmToken() : null);
        return ResponseEntity.ok(BaseResponse.success(null, "Đăng xuất thành công"));
    }

    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}
