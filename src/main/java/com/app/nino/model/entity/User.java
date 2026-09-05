package com.app.nino.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "users")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", nullable = false, unique = true)
    @Builder.Default
    private String username = "";

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    /** Nullable — user đăng nhập qua Google không có password */
    @Column(name = "password_hash")
    private String passwordHash;

    /** ID định danh từ Google (payload.getSubject()) — null nếu chưa liên kết Google */
    @Column(name = "google_id", unique = true)
    private String googleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_provider", nullable = false, length = 10)
    @Builder.Default
    private AuthProvider authProvider = AuthProvider.LOCAL;

    @Column(name = "status", nullable = false, length = 3)
    @Builder.Default
    private String status = "REG";

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(name = "language")
    @Builder.Default
    private String language = "vi";

    @Column(name = "dark_mode")
    @Builder.Default
    private Boolean darkMode = false;

    @Column(name = "total_events")
    @Builder.Default
    private Integer totalEvents = 0;

    @Column(name = "total_relatives")
    @Builder.Default
    private Integer totalRelatives = 0;

    @Column(name = "google_calendar_token", columnDefinition = "TEXT")
    private String googleCalendarToken;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "failed_login_count", nullable = false)
    @Builder.Default
    private Integer failedLoginCount = 0;

    @Column(name = "last_failed_at")
    private LocalDateTime lastFailedAt;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "total_login_count", nullable = false)
    @Builder.Default
    private Integer totalLoginCount = 0;

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    @Column(name = "last_login_ip")
    private String lastLoginIp;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "user_roles",
        joinColumns = @JoinColumn(name = "user_id"),
        inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    @Builder.Default
    private Set<Role> roles = new HashSet<>();

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // ── Helper methods ─────────────────────────────────────────────────────

    public boolean hasRole(String roleName) {
        return roles.stream().anyMatch(r -> r.getName().equals(roleName));
    }

    public boolean isCurrentlyLocked() {
        return lockedUntil != null && lockedUntil.isAfter(LocalDateTime.now());
    }

    public long getMinutesUntilUnlock() {
        if (!isCurrentlyLocked()) return 0;
        return java.time.Duration.between(LocalDateTime.now(), lockedUntil).toMinutes();
    }

    public boolean isActive() {
        return "ACT".equals(status);
    }

    public boolean canLogin() {
        return "ACT".equals(status) || "VRF".equals(status);
    }

    public boolean isGoogleUser() {
        return AuthProvider.GOOGLE.equals(this.authProvider);
    }

    public enum AuthProvider {
        LOCAL, GOOGLE
    }
}
