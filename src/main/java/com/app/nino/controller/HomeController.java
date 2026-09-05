package com.app.nino.controller;

import com.app.nino.model.dto.response.BaseResponse;
import com.app.nino.service.HomeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/home")
@RequiredArgsConstructor
@Tag(name = "Home Dashboard", description = "Dữ liệu tổng hợp màn hình Home")
public class HomeController {

    private final HomeService homeService;

    @GetMapping
    @Operation(summary = "Dữ liệu tổng hợp trang chủ")
    public ResponseEntity<BaseResponse<?>> getHome(@AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(BaseResponse.success(homeService.getHomeData(userId)));
    }

    @GetMapping("/my-events")
    @Operation(summary = "Sự kiện của bản thân")
    public ResponseEntity<BaseResponse<?>> getMyEvents(@AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(BaseResponse.success(homeService.getMyEvents(userId)));
    }
}
