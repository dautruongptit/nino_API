package com.app.nino.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(name = "event_reminders")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventReminder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Column(name = "remind_days_before")
    private Integer remindDaysBefore;

    @Column(name = "remind_hours_before")
    private Integer remindHoursBefore;

    @Column(name = "remind_minutes_before")
    private Integer remindMinutesBefore;

    @Column(name = "is_enabled")
    @Builder.Default
    private Boolean isEnabled = true;

    /** Thời điểm lần gần nhất đã gửi nhắc nhở cho lần trigger hiện tại — dùng
     * để ReminderScheduler tránh gửi trùng khi quét lại (NULL = chưa từng gửi). */
    @Column(name = "notified_at")
    private LocalDateTime notifiedAt;

    /** Nếu có giá trị: sau lần bắn đầu tiên, tự bắn lại mỗi N phút cho tới khi
     * người dùng đọc thông báo ở chuông (VD: nhắc uống thuốc mỗi 30 phút). */
    @Column(name = "repeat_interval_minutes")
    private Integer repeatIntervalMinutes;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    /** Tính thời điểm trigger thực sự dựa trên ngày/giờ sự kiện + mốc nhắc */
    public LocalDateTime computeTriggerTime(LocalDate eventDate, LocalTime eventTime) {
        LocalDateTime base = LocalDateTime.of(eventDate, eventTime != null ? eventTime : LocalTime.of(8, 0));
        if (remindDaysBefore != null) {
            return base.minusDays(remindDaysBefore);
        }
        if (remindHoursBefore != null) {
            return base.minusHours(remindHoursBefore);
        }
        if (remindMinutesBefore != null) {
            return base.minusMinutes(remindMinutesBefore);
        }
        return base;
    }
}
