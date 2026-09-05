package com.app.nino.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "events")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Người thân chính liên quan — NULL = sự kiện của bản thân */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "relative_id")
    private Relative relative;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    /** Danh mục sự kiện — quản lý icon/màu từ DB (thay thế enum EventType cũ) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private EventCategory category;

    @Column(name = "event_date", nullable = false)
    private LocalDate eventDate;

    @Column(name = "event_time")
    private LocalTime eventTime;

    @Column(name = "is_recurring")
    @Builder.Default
    private Boolean isRecurring = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "recurrence_type", length = 15)
    private RecurrenceType recurrenceType;

    /** Chỉ có giá trị khi recurrenceType = LUNAR_YEARLY — nguồn sự thật để tính lại ngày dương mỗi năm */
    @Column(name = "lunar_day")
    private Integer lunarDay;

    @Column(name = "lunar_month")
    private Integer lunarMonth;

    /** Chỉ có giá trị khi recurrenceType = CUSTOM — VD: mỗi 3 tuần */
    @Column(name = "custom_interval_value")
    private Integer customIntervalValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "custom_interval_unit", length = 10)
    private CustomIntervalUnit customIntervalUnit;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** Nhắc nhở gắn với sự kiện — xóa hết + tạo lại mỗi lần update (không merge) */
    @OneToMany(mappedBy = "event", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<EventReminder> reminders = new ArrayList<>();

    // ── Lifecycle callbacks ────────────────────────────────────────────────

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // ── Enum ────────────────────────────────────────────────────────────────

    public enum RecurrenceType {
        YEARLY,         // lặp theo Dương lịch (VD: sinh nhật tính theo dương)
        MONTHLY,
        WEEKLY,
        LUNAR_YEARLY,   // lặp theo Âm lịch (ngày giỗ) — dùng lunarDay/lunarMonth
        DAILY,          // lặp mỗi ngày
        HOURLY,         // lặp mỗi giờ
        CUSTOM          // lặp theo chu kỳ tuỳ chỉnh — dùng customIntervalValue/customIntervalUnit
    }

    public enum CustomIntervalUnit {
        HOUR, DAY, WEEK, MONTH, YEAR
    }
}
