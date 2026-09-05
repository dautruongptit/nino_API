package com.app.nino.controller;

import com.app.nino.model.dto.request.UpdateProfileRequest;
import com.app.nino.model.dto.request.UpdateSettingsRequest;
import com.app.nino.model.dto.response.BaseResponse;
import com.app.nino.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
@Tag(name = "User Management", description = "Profile cá nhân và quản trị user (Admin)")
public class UserController {

    private final AuthService authService;

    // ── USER ─────────────────────────────────────────────────────────────────

    @GetMapping("/me")
    @Operation(summary = "Lấy profile cá nhân")
    public ResponseEntity<BaseResponse<?>> getProfile(@AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(BaseResponse.success(authService.getProfile(userId)));
    }

    @PutMapping("/me")
    @Operation(summary = "Cập nhật họ tên")
    public ResponseEntity<BaseResponse<?>> updateProfile(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody UpdateProfileRequest req) {
        return ResponseEntity.ok(BaseResponse.success(authService.updateProfile(userId, req)));
    }

    @PutMapping("/me/settings")
    @Operation(summary = "Cập nhật ngôn ngữ, dark mode")
    public ResponseEntity<BaseResponse<?>> updateSettings(
            @AuthenticationPrincipal Long userId,
            @RequestBody UpdateSettingsRequest req) {
        return ResponseEntity.ok(BaseResponse.success(authService.updateSettings(userId, req)));
    }

    @PutMapping("/me/avatar")
    @Operation(summary = "Upload avatar")
    public ResponseEntity<BaseResponse<?>> uploadAvatar(
            @AuthenticationPrincipal Long userId,
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(BaseResponse.success(authService.uploadAvatar(userId, file)));
    }

    @GetMapping("/me/login-history")
    @Operation(summary = "Lịch sử đăng nhập của mình")
    public ResponseEntity<BaseResponse<?>> getMyLoginHistory(
            @AuthenticationPrincipal Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(BaseResponse.success(authService.getLoginHistory(userId, page, size)));
    }

    // ── ADMIN ────────────────────────────────────────────────────────────────

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "[Admin] Danh sách tất cả user")
    public ResponseEntity<BaseResponse<?>> getAllUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(BaseResponse.success(authService.getAllUsers(page, size)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "[Admin] Chi tiết 1 user bất kỳ")
    public ResponseEntity<BaseResponse<?>> getUserById(@PathVariable Long id) {
        return ResponseEntity.ok(BaseResponse.success(authService.getProfile(id)));
    }

    @PutMapping("/{id}/deactivate")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "[Admin] Khóa user")
    public ResponseEntity<BaseResponse<?>> deactivateUser(
            @PathVariable Long id,
            @AuthenticationPrincipal Long adminUserId) {
        authService.deactivateUser(id, adminUserId);
        return ResponseEntity.ok(BaseResponse.success(null, "Đã khóa user"));
    }

    @PutMapping("/{id}/grant-admin")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "[Admin] Cấp quyền ADMIN")
    public ResponseEntity<BaseResponse<?>> grantAdmin(@PathVariable Long id) {
        authService.grantAdminRole(id);
        return ResponseEntity.ok(BaseResponse.success(null, "Đã cấp quyền ADMIN"));
    }

    @GetMapping("/{id}/login-history")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "[Admin] Lịch sử đăng nhập của user bất kỳ")
    public ResponseEntity<BaseResponse<?>> getUserLoginHistory(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(BaseResponse.success(authService.getLoginHistory(id, page, size)));
    }
}
