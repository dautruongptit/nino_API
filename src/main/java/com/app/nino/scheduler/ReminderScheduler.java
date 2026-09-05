package com.app.nino.scheduler;

import com.app.nino.model.entity.Event;
import com.app.nino.model.entity.EventReminder;
import com.app.nino.model.entity.Notification;
import com.app.nino.repository.EventReminderRepository;
import com.app.nino.repository.NotificationRepository;
import com.app.nino.service.FcmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReminderScheduler {

    private final EventReminderRepository reminderRepo;
    private final NotificationRepository  notifRepo;
    private final CacheManager            cacheManager;
    private final FcmService              fcmService;

    /**
     * Quét mỗi 5 phút thay vì 1 lần/ngày lúc 8h — dùng
     * {@link EventReminder#computeTriggerTime} để nhắc đúng thời điểm, hỗ trợ
     * chính xác cả "trước X giờ" lẫn "trước X ngày" (trước đây remindHoursBefore
     * chỉ kiểm tra sự kiện có rơi vào hôm nay hay không, bỏ qua số giờ thực tế).
     */
    @Scheduled(cron = "0 */5 * * * *", zone = "Asia/Ho_Chi_Minh")
    @Transactional
    public void checkReminders() {
        LocalDateTime now = LocalDateTime.now();
        List<EventReminder> candidates = reminderRepo.findDueCandidates(LocalDate.now().minusDays(1));

        int fired = 0;
        for (EventReminder reminder : candidates) {
            Event event = reminder.getEvent();
            LocalDateTime trigger = reminder.computeTriggerTime(event.getEventDate(), event.getEventTime());

            boolean lastNotificationRead = false;
            if (reminder.getRepeatIntervalMinutes() != null && reminder.getNotifiedAt() != null) {
                lastNotificationRead = notifRepo.findFirstByReminderIdOrderBySentAtDesc(reminder.getId())
                    .map(Notification::getIsRead)
                    .orElse(false);
            }

            if (!isDue(trigger, now, reminder.getNotifiedAt(),
                    reminder.getRepeatIntervalMinutes(), lastNotificationRead)) {
                continue;
            }

            try {
                fireReminder(reminder, event, now);
                fired++;
            } catch (Exception e) {
                log.error("[Scheduler] Loi khi xu ly reminder id={} (event id={}): {}",
                    reminder.getId(), event.getId(), e.getMessage());
            }
        }

        if (fired > 0) {
            log.info("[Scheduler] Da gui {} nhac nho", fired);
        }
    }

    /**
     * Nhắc nhở đến hạn và cần bắn (lần đầu, hoặc lặp lại đúng chu kỳ).
     *
     * notifiedAt cũ hơn trigger hiện tại (VD: sự kiện lặp âm lịch đã sang
     * kỳ mới) luôn tính là chưa báo cho lần trigger này — bắn ngay, không
     * quan tâm repeatIntervalMinutes/lastNotificationRead (thuộc chu kỳ cũ).
     *
     * Nếu đã bắn ít nhất 1 lần cho đúng chu kỳ hiện tại:
     *   - repeatIntervalMinutes == null -> nhắc 1 lần duy nhất, không bắn lại.
     *   - ngược lại -> chỉ bắn lại khi CHƯA đọc thông báo lần trước và đã
     *     qua đủ repeatIntervalMinutes kể từ lần bắn gần nhất.
     */
    static boolean isDue(LocalDateTime trigger, LocalDateTime now, LocalDateTime notifiedAt,
                          Integer repeatIntervalMinutes, boolean lastNotificationRead) {
        if (trigger.isAfter(now)) {
            return false;
        }
        if (notifiedAt == null || notifiedAt.isBefore(trigger)) {
            return true;
        }
        if (repeatIntervalMinutes == null) {
            return false;
        }
        if (lastNotificationRead) {
            return false;
        }
        return !notifiedAt.plusMinutes(repeatIntervalMinutes).isAfter(now);
    }

    private void fireReminder(EventReminder reminder, Event event, LocalDateTime now) {
        String title = "Nhắc nhở: " + event.getTitle();
        String body  = buildBody(event);

        Notification notif = Notification.builder()
            .user(event.getUser())
            .event(event)
            .reminder(reminder)
            .title(title)
            .body(body)
            .isRead(false)
            .sentAt(now)
            .build();

        notifRepo.save(notif);

        evictUnreadCount(event.getUser().getId());

        fcmService.sendToUser(
            event.getUser().getId(),
            title,
            body,
            Map.of(
                "eventId", String.valueOf(event.getId()),
                "type", "EVENT_REMINDER"
            )
        );

        reminder.setNotifiedAt(now);
        reminderRepo.save(reminder);

        log.info("[Scheduler] Da tao notification + gui push cho event id={} user={} reminder id={}",
            event.getId(), event.getUser().getId(), reminder.getId());
    }

    private void evictUnreadCount(Long userId) {
        Cache cache = cacheManager.getCache("unreadCount");
        if (cache != null) {
            cache.evict(userId);
        }
    }

    private String buildBody(Event event) {
        return String.format("Sự kiện '%s' diễn ra vào ngày %s",
            event.getTitle(), event.getEventDate());
    }
}
