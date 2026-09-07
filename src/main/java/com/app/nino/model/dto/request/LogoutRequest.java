package com.app.nino.model.dto.request;

import lombok.Data;

@Data
public class LogoutRequest {

    /** Tuy chon — neu gui kem, refresh token cung bi thu hoi ngay lap tuc
     *  thay vi cho no tu het han (toi 7 ngay). */
    private String refreshToken;

    /** Tuy chon — neu gui kem, huy dang ky thiet bi nay khoi FCM ngay lap
     *  tuc (dung push cho thiet bi nay tu thoi diem logout). */
    private String fcmToken;
}
