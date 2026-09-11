package com.app.nino.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "login_histories")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "device_type", length = 30)
    private String deviceType;

    @Column(name = "device_name", length = 100)
    private String deviceName;

    @Column(name = "os", length = 100)
    private String os;

    @Column(name = "browser", length = 100)
    private String browser;

    @Column(name = "country", length = 100)
    private String country;

    @Column(name = "is_success", nullable = false)
    private Boolean isSuccess;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason", length = 30)
    private FailureReason failureReason;

    @Column(name = "login_at", nullable = false)
    private LocalDateTime loginAt;

    /** Claim "sid" cua JWT phat hanh o lan dang nhap nay — xem JwtTokenProvider.
     *  NULL cho dong that bai (khong co phien nao de theo doi) hoac dong tao
     *  truoc khi co migration nay. */
    @Column(name = "session_id", length = 36)
    private String sessionId;

    /** Han refresh token HIEN TAI cua phien nay — cap nhat lai moi lan
     *  /auth/refresh thanh cong (cua so 7 ngay truot toi). */
    @Column(name = "refresh_expires_at")
    private LocalDateTime refreshExpiresAt;

    /** Thoi diem phien bi thu hoi (tu dang xuat thuong hoac dang xuat tu xa
     *  qua man Lich su dang nhap) — NULL nghia la chua thu hoi. */
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @PrePersist
    protected void onCreate() {
        if (this.loginAt == null) this.loginAt = LocalDateTime.now();
    }

    public enum FailureReason {
        WRONG_PASSWORD, ACCOUNT_LOCKED, ACCOUNT_INACTIVE
    }
}
