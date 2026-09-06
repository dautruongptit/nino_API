package com.app.nino.controller;

import com.app.nino.model.dto.request.CreateCategoryRequest;
import com.app.nino.model.dto.request.CreateEventRequest;
import com.app.nino.model.dto.response.BaseResponse;
import com.app.nino.service.CategoryService;
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
    private final CategoryService categoryService;

    // ── CATEGORIES ──────────────────────────────────────────────────────

    @GetMapping("/categories")
    @Operation(summary = "Danh sach danh muc su kien (he thong + user tu tao)")
    public ResponseEntity<BaseResponse<?>> getCategories(@AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(BaseResponse.success(categoryService.getCategories(userId)));
    }

    @PostMapping("/categories")
    @Operation(summary = "Tao danh muc su kien tu tao (emoji icon)")
    public ResponseEntity<BaseResponse<?>> createCategory(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody CreateCategoryRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(BaseResponse.success(categoryService.create(userId, req)));
    }

    @PutMapping("/categories/{id}")
    @Operation(summary = "Cap nhat danh muc tu tao")
    public ResponseEntity<BaseResponse<?>> updateCategory(
            @PathVariable Long id,
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody CreateCategoryRequest req) {
        return ResponseEntity.ok(BaseResponse.success(categoryService.update(id, userId, req)));
    }

    @DeleteMapping("/categories/{id}")
    @Operation(summary = "Xoa danh muc tu tao")
    public ResponseEntity<BaseResponse<?>> deleteCategory(
            @PathVariable Long id,
            @AuthenticationPrincipal Long userId) {
        categoryService.delete(id, userId);
        return ResponseEntity.ok(BaseResponse.success(null, "Xoa danh muc thanh cong"));
    }

    // ── EVENTS ──────────────────────────────────────────────────────────

    @GetMapping
    @Operation(summary = "Danh sach su kien co filter")
    public ResponseEntity<BaseResponse<?>> getAll(
            @AuthenticationPrincipal Long userId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long relativeId,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer year) {
        return ResponseEntity.ok(
            BaseResponse.success(eventService.getEvents(userId, categoryId, relativeId, month, year)));
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
