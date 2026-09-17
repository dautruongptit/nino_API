package com.app.nino.controller;

import com.app.nino.exception.BadRequestException;
import com.app.nino.model.dto.request.OtpRequestRequest;
import com.app.nino.model.dto.request.OtpVerifyRequest;
import com.app.nino.model.dto.response.BaseResponse;
import com.app.nino.model.dto.response.OtpVerifyResponse;
import com.app.nino.service.OtpService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth/otp")
@RequiredArgsConstructor
@Tag(name = "OTP", description = "Gửi và xác thực mã OTP qua email (đăng ký, quên mật khẩu, quên PIN)")
public class OtpController {

    private final OtpService otpService;

    @PostMapping("/request")
    @Operation(summary = "Gửi mã OTP qua email")
    public ResponseEntity<BaseResponse<?>> requestOtp(@Valid @RequestBody OtpRequestRequest req) {
        otpService.requestOtp(req.getEmail(), req.getPurpose());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(BaseResponse.success(null, "Đã gửi mã OTP, vui lòng kiểm tra email"));
    }

    @PostMapping("/verify")
    @Operation(summary = "Xác thực mã OTP")
    public ResponseEntity<BaseResponse<?>> verifyOtp(@Valid @RequestBody OtpVerifyRequest req) {
        OtpVerifyResponse result = otpService.verifyOtp(req.getEmail(), req.getPurpose(), req.getOtp());
        if (!result.isVerified()) {
            throw new BadRequestException("Mã OTP không đúng hoặc đã hết hạn");
        }
        return ResponseEntity.ok(BaseResponse.success(result));
    }
}
