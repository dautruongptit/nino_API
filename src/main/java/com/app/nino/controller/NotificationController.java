package com.app.nino.controller;

import com.app.nino.model.dto.response.BaseResponse;
import com.app.nino.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
@Tag(name = "Notifications", description = "Thông báo và trạng thái đã đọc / chưa đọc")
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    @Operation(summary = "Danh sách thông báo (phân trang)")
    public ResponseEntity<BaseResponse<?>> getAll(
            @AuthenticationPrincipal Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(
            BaseResponse.success(notificationService.getNotifications(userId, page, size)));
    }

    @GetMapping("/unread-count")
    @Operation(summary = "Số thông báo chưa đọc")
    public ResponseEntity<BaseResponse<?>> getUnreadCount(@AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(BaseResponse.success(notificationService.countUnread(userId)));
    }

    @PutMapping("/{id}/read")
    @Operation(summary = "Đánh dấu 1 thông báo đã đọc")
    public ResponseEntity<BaseResponse<?>> markAsRead(
            @PathVariable Long id, @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(BaseResponse.success(notificationService.markAsRead(id, userId)));
    }

    @PutMapping("/read-all")
    @Operation(summary = "Đánh dấu tất cả đã đọc")
    public ResponseEntity<BaseResponse<?>> markAllAsRead(@AuthenticationPrincipal Long userId) {
        notificationService.markAllAsRead(userId);
        return ResponseEntity.ok(BaseResponse.success(null, "Đã đánh dấu tất cả đã đọc"));
    }
}
