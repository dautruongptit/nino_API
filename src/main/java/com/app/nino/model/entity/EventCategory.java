package com.app.nino.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "event_categories")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, unique = true, length = 20)
    private String code;              // SINH_NHAT, KY_NIEM...

    @Column(name = "display_name", nullable = false, length = 50)
    private String displayName;       // "Sinh nhật"

    @Column(name = "icon", nullable = false, length = 50)
    private String icon;              // "cake" — tên icon dùng chung Backend/Flutter

    @Column(name = "color_hex", nullable = false, length = 7)
    private String colorHex;          // "#FF6B6B"

    @Column(name = "is_system", nullable = false)
    @Builder.Default
    private Boolean isSystem = true;

    /** NULL nếu là danh mục hệ thống. Dành cho tính năng user tự tạo sau này. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
