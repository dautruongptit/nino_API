package com.app.nino.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DeviceTokenRequest {

    @NotBlank(message = "fcmToken khong duoc de trong")
    private String fcmToken;

    @NotBlank(message = "platform khong duoc de trong")
    private String platform;   // ANDROID, IOS, WEB

    private String deviceName; // tuy chon, VD: "iPhone 15 Pro"
}
