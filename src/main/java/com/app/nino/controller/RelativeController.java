package com.app.nino.controller;

import com.app.nino.model.dto.request.CreateRelativeRequest;
import com.app.nino.model.dto.response.BaseResponse;
import com.app.nino.service.RelativeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/relatives")
@RequiredArgsConstructor
@Tag(name = "Relatives", description = "Quản lý người thân theo nhóm")
public class RelativeController {

    private final RelativeService relativeService;

    @GetMapping
    @Operation(summary = "Danh sách người thân")
    public ResponseEntity<BaseResponse<?>> getAll(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "GIA_DINH|VO_CHONG|CON_CAI|BAN_BE|ANH_CHI_EM")
            @RequestParam(required = false) String groupType,
            @RequestParam(required = false) String search) {
        return ResponseEntity.ok(
            BaseResponse.success(relativeService.getRelatives(userId, groupType, search)));
    }

    @GetMapping("/groups")
    @Operation(summary = "Tổng hợp số lượng theo nhóm")
    public ResponseEntity<BaseResponse<?>> getGroups(@AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(BaseResponse.success(relativeService.getGroupSummary(userId)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Chi tiết 1 người thân + sự kiện liên quan")
    public ResponseEntity<BaseResponse<?>> getDetail(
            @PathVariable Long id, @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(BaseResponse.success(relativeService.getDetail(id, userId)));
    }

    @PostMapping
    @Operation(summary = "Thêm người thân")
    public ResponseEntity<BaseResponse<?>> create(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody CreateRelativeRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(BaseResponse.success(relativeService.create(userId, req)));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Cập nhật người thân")
    public ResponseEntity<BaseResponse<?>> update(
            @PathVariable Long id,
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody CreateRelativeRequest req) {
        return ResponseEntity.ok(BaseResponse.success(relativeService.update(id, userId, req)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Xóa người thân (cascade xóa sự kiện liên quan)")
    public ResponseEntity<BaseResponse<?>> delete(
            @PathVariable Long id, @AuthenticationPrincipal Long userId) {
        relativeService.delete(id, userId);
        return ResponseEntity.ok(BaseResponse.success(null, "Xóa người thân thành công"));
    }
}
