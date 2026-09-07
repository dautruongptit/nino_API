package com.app.nino.controller;

import com.app.nino.model.dto.request.DeviceTokenRequest;
import com.app.nino.model.dto.response.BaseResponse;
import com.app.nino.model.entity.User;
import com.app.nino.model.entity.UserDevice;
import com.app.nino.repository.UserDeviceRepository;
import com.app.nino.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/users/me/devices")
@RequiredArgsConstructor
@Tag(name = "Devices", description = "Đăng ký/hủy thiết bị nhận push notification (FCM)")
public class DeviceController {

    private final UserDeviceRepository deviceRepo;
    private final UserRepository       userRepo;

    /**
     * POST /users/me/devices
     * Goi API nay moi khi: app moi mo lan dau, sau khi login,
     * hoac FCM token duoc Firebase SDK tu lam moi (token co the doi theo thoi gian).
     */
    @PostMapping
    @Operation(summary = "Đăng ký FCM token cho thiết bị hiện tại",
               description = "Nếu token đã tồn tại sẽ cập nhật lại user sở hữu (trường hợp đăng xuất/đăng nhập tài khoản khác trên cùng máy).")
    public ResponseEntity<BaseResponse<?>> registerDevice(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody DeviceTokenRequest req) {

        User user = userRepo.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));

        UserDevice device = deviceRepo.findByFcmToken(req.getFcmToken())
            .map(existing -> {
                existing.setUser(user);   // token co the thuoc user khac truoc do (doi tai khoan tren cung may)
                existing.setDeviceName(req.getDeviceName());
                return existing;
            })
            .orElseGet(() -> UserDevice.builder()
                .user(user)
                .fcmToken(req.getFcmToken())
                .platform(UserDevice.Platform.valueOf(req.getPlatform().toUpperCase()))
                .deviceName(req.getDeviceName())
                .build());

        deviceRepo.save(device);

        return ResponseEntity.ok(BaseResponse.success(null, "Đăng ký thiết bị thành công"));
    }

    /**
     * DELETE /users/me/devices
     * Goi khi user dang xuat — tranh gui push nham cho thiet bi da logout.
     */
    @DeleteMapping
    @Operation(summary = "Hủy đăng ký FCM token (gọi khi logout)")
    @Transactional
    public ResponseEntity<BaseResponse<?>> unregisterDevice(
            @AuthenticationPrincipal Long userId,
            @RequestBody Map<String, String> body) {

        String fcmToken = body.get("fcmToken");
        // Chi xoa neu token dung la cua chinh user dang goi — tranh IDOR
        // (truoc day xoa theo fcmToken bat ky, cho phep huy dang ky thiet bi cua nguoi khac).
        deviceRepo.deleteByFcmTokenAndUserId(fcmToken, userId);
        return ResponseEntity.ok(BaseResponse.success(null, "Đã hủy đăng ký thiết bị"));
    }

    /**
     * GET /users/me/devices
     * Xem danh sach thiet bi dang nhan thong bao (man Bao mat co the hien thi).
     */
    @GetMapping
    @Operation(summary = "Danh sách thiết bị đang nhận thông báo")
    public ResponseEntity<BaseResponse<?>> getMyDevices(
            @AuthenticationPrincipal Long userId) {

        List<UserDevice> devices = deviceRepo.findByUserId(userId);
        return ResponseEntity.ok(BaseResponse.success(devices));
    }
}
