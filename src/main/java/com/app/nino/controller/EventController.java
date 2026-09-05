package com.app.nino.controller;

import com.app.nino.model.dto.request.CreateEventRequest;
import com.app.nino.model.dto.response.BaseResponse;
import com.app.nino.service.EventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/events")
@RequiredArgsConstructor
@Tag(name = "Events", description = "Quản lý sự kiện cá nhân và sự kiện người thân")
public class EventController {

    private final EventService eventService;

    @GetMapping
    @Operation(summary = "Danh sách sự kiện có filter")
    public ResponseEntity<BaseResponse<?>> getAll(
            @AuthenticationPrincipal Long userId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long relativeId,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer year) {
        return ResponseEntity.ok(
            BaseResponse.success(eventService.getEvents(userId, categoryId, relativeId, month, year)));
    }

    @GetMapping("/categories")
    @Operation(summary = "Danh sách danh mục sự kiện (id/code/tên/icon/màu)")
    public ResponseEntity<BaseResponse<?>> getCategories() {
        return ResponseEntity.ok(BaseResponse.success(eventService.getCategories()));
    }

    @GetMapping("/upcoming")
    @Operation(summary = "Sự kiện sắp tới (90 ngày)")
    public ResponseEntity<BaseResponse<?>> getUpcoming(
            @AuthenticationPrincipal Long userId,
            @RequestParam(defaultValue = "5") int limit) {
        return ResponseEntity.ok(BaseResponse.success(eventService.getUpcoming(userId, limit)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Chi tiết 1 sự kiện + reminders + participants")
    public ResponseEntity<BaseResponse<?>> getById(
            @PathVariable Long id, @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(BaseResponse.success(eventService.getById(id, userId)));
    }

    @PostMapping
    @Operation(summary = "Tạo sự kiện")
    public ResponseEntity<BaseResponse<?>> create(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody CreateEventRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(BaseResponse.success(eventService.create(userId, req)));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Cập nhật sự kiện")
    public ResponseEntity<BaseResponse<?>> update(
            @PathVariable Long id,
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody CreateEventRequest req) {
        return ResponseEntity.ok(BaseResponse.success(eventService.update(id, userId, req)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Xóa sự kiện (soft delete)")
    public ResponseEntity<BaseResponse<?>> delete(
            @PathVariable Long id, @AuthenticationPrincipal Long userId) {
        eventService.delete(id, userId);
        return ResponseEntity.ok(BaseResponse.success(null, "Xóa sự kiện thành công"));
    }
}
