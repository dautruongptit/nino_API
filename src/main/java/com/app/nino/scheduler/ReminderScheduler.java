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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

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
    // KHÔNG @Transactional ở đây (khác trước) — bọc cả vòng lặp candidates
    // trong 1 transaction duy nhất nghĩa là notifiedAt của MỌI reminder đã
    // xử lý trong lượt quét chỉ thực sự ghi xuống DB khi toàn bộ vòng lặp
    // chạy xong. Nếu app bị restart/crash giữa chừng (rất hay gặp lúc dev,
    // "mvnw spring-boot:run" bị kill để chạy lại) — TOÀN BỘ notifiedAt của
    // lượt quét đó mất trắng dù push đã gửi thật, lượt quét kế tiếp coi như
    // chưa từng báo và bắn lại — đây là nguyên nhân gây nhắc nhở trùng lặp
    // (xem log ngày 2026-09-11: reminder id 5/7/10 cùng 1 event bắn lại 4
    // lần trong 8 phút, mỗi lần app khởi động lại). Bỏ @Transactional ở
    // đây để mỗi repo.save() bên trong fireReminder() tự commit ngay lập
    // tức (transaction riêng, theo mặc định của Spring Data JPA) thay vì
    // gộp chung — một reminder xử lý xong là ghi nhận NGAY, không phụ
    // thuộc các reminder xử lý sau nó trong cùng lượt quét.
    @Scheduled(cron = "0 */5 * * * *", zone = "Asia/Ho_Chi_Minh")
    public void checkReminders() {
        LocalDateTime now = LocalDateTime.now();
        List<EventReminder> candidates = reminderRepo.findDueCandidates(LocalDate.now().minusDays(1));

        // Gom các reminder ĐẾN HẠN theo sự kiện trước khi bắn — backend
        // không chạy 24/7 (chỉ bật khi dev cần dùng), nên nhiều mốc nhắc
        // khác nhau (7 ngày/3 ngày/1 ngày/1 giờ trước...) của CÙNG 1 sự
        // kiện có thể cùng "đến hạn" trong 1 lượt quét nếu chúng quá hạn
        // suốt thời gian không có backend nào chạy. Mốc càng cũ càng hết
        // giá trị cảnh báo sớm (nhắc "7 ngày trước" khi chỉ còn 1 tiếng
        // nữa là vô nghĩa) và bắn hết tất cả chỉ dồn cục N thông báo giống
        // hệt nhau (buildBody không phân biệt theo mốc nào) nhân thêm với
        // số thiết bị đã đăng ký của user — đây là nguyên nhân thực tế
        // khiến 1 sự kiện dội về nhiều thông báo trùng lặp cùng lúc (xem
        // trao đổi 2026-09-12: sự kiện "ffff" dội 4 mốc x 3 thiết bị cùng
        // lúc 12:30 dù giờ sự kiện là 14:00).
        Map<Long, List<DueReminder>> dueByEvent = candidates.stream()
            .map(r -> toDueOrNull(r, now))
            .filter(Objects::nonNull)
            .collect(Collectors.groupingBy(d -> d.event.getId()));

        int fired = 0;
        for (List<DueReminder> group : dueByEvent.values()) {
            fired += fireDueGroup(group, now);
        }

        if (fired > 0) {
            log.info("[Scheduler] Da gui {} nhac nho", fired);
        }
    }

    private record DueReminder(EventReminder reminder, Event event, LocalDateTime trigger) {}

    private DueReminder toDueOrNull(EventReminder reminder, LocalDateTime now) {
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
            return null;
        }
        return new DueReminder(reminder, event, trigger);
    }

    /**
     * Bắn 1 push duy nhất cho cả nhóm (mốc có trigger GẦN "bây giờ" nhất —
     * tức ít trễ nhất, còn giá trị cảnh báo nhất); các mốc trễ hơn cùng sự
     * kiện vẫn được đánh dấu đã xử lý (notifiedAt) để không rơi vào lượt
     * quét sau và bắn lại, nhưng không tạo thêm thông báo/push nào khác.
     */
    private int fireDueGroup(List<DueReminder> group, LocalDateTime now) {
        DueReminder toNotify = group.stream()
            .max(Comparator.comparing(DueReminder::trigger))
            .orElseThrow();

        try {
            fireReminder(toNotify.reminder, toNotify.event, now);
        } catch (Exception e) {
            log.error("[Scheduler] Loi khi xu ly reminder id={} (event id={}): {}",
                toNotify.reminder.getId(), toNotify.event.getId(), e.getMessage());
            return 0;
        }

        for (DueReminder skipped : group) {
            if (skipped == toNotify) continue;
            skipped.reminder.setNotifiedAt(now);
            reminderRepo.save(skipped.reminder);
        }

        return 1;
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

        // Ghi notifiedAt XUỐNG DB TRƯỚC khi gọi FCM (gọi mạng ngoài, chậm và
        // có thể văng exception/app bị kill giữa chừng) — không làm ngược
        // lại. Nếu ghi notifiedAt sau cùng, app crash/restart đúng lúc vừa
        // gửi push xong nhưng chưa kịp lưu notifiedAt sẽ khiến lượt quét kế
        // tiếp coi reminder này "chưa từng báo" và gửi trùng push lần nữa —
        // đây chính là nguyên nhân 1 sự kiện nhận được nhiều thông báo giống
        // hệt nhau dồn dập. Đánh đổi: nếu chính lệnh gửi FCM bị lỗi sau khi
        // đã lưu notifiedAt, reminder coi như đã báo dù push thất bại (mất
        // 1 lần nhắc) — chấp nhận được, còn hơn spam trùng lặp; thông báo
        // vẫn có trong chuông app (đã lưu notifRepo.save ở trên) dù có thể
        // không có push.
        reminder.setNotifiedAt(now);
        reminderRepo.save(reminder);

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
