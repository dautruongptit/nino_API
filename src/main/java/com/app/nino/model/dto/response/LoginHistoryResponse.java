package com.app.nino.model.dto.response;

import com.app.nino.model.entity.LoginHistory;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data @Builder
public class LoginHistoryResponse {
    private Long          id;
    private String        ipAddress;
    private String        deviceType;   // Mobile | Desktop | Tablet
    private String        deviceName;   // VD "Pixel 8" — tu client gui khi login, co the null (log cu)
    private String        os;           // Windows | macOS | Android | iOS
    private String        browser;      // Chrome | Safari | Firefox | Edge
    private String        country;
    private Boolean       isSuccess;
    private String        failureReason;
    private LocalDateTime loginAt;

    public static LoginHistoryResponse from(LoginHistory h) {
        return LoginHistoryResponse.builder()
            .id(h.getId())
            .ipAddress(h.getIpAddress())
            .deviceType(h.getDeviceType())
            .deviceName(h.getDeviceName())
            .os(h.getOs())
            .browser(h.getBrowser())
            .country(h.getCountry())
            .isSuccess(h.getIsSuccess())
            .failureReason(h.getFailureReason() != null ? h.getFailureReason().name() : null)
            .loginAt(h.getLoginAt())
            .build();
    }
}
