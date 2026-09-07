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

    @PrePersist
    protected void onCreate() {
        if (this.loginAt == null) this.loginAt = LocalDateTime.now();
    }

    public enum FailureReason {
        WRONG_PASSWORD, ACCOUNT_LOCKED, ACCOUNT_INACTIVE
    }
}
