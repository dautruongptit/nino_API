package com.app.nino.scheduler;

import com.app.nino.model.entity.Event;
import com.app.nino.model.entity.EventReminder;
import com.app.nino.repository.EventRepository;
import com.app.nino.repository.NotificationRepository;
import com.app.nino.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/** Xoa CUNG (hard delete, khac voi EventService.delete() von chi set
 *  isActive=false) cac Event duoc tao tu nut "Nhac toi" o man Lich nghi le
 *  (isHolidayReminder=true) sau khi eventDate da qua, VOI DIEU KIEN chua bi
 *  nguoi dung sua (EventService.update() luon clear flag nay) va khong lien
 *  ket voi nguoi than / khong lap lai (defense-in-depth, du luong tao hien
 *  tai khong bao gio set 2 truong nay). Chay hang ngay luc 3h30 sang — sau
 *  AccountDeletionScheduler (3h) va truoc LunarRecurrenceScheduler (7h).
 *  EventReminder con lai cua Event bi xoa se tu dong bi xoa theo qua
 *  orphanRemoval/cascade da khai bao tren Event.reminders. */
@Slf4j
@Component
@RequiredArgsConstructor
public class HolidayReminderCleanupScheduler {

    private final EventRepository eventRepo;
    private final NotificationRepository notificationRepo;
    private final UserRepository userRepo;

    // KHONG @Transactional o day — neu bo o muc method nay, delete() cua
    // TUNG event ben trong vong lap se khong flush SQL ngay (JPA hoan xuong
    // luc commit cua transaction bao ngoai, tuc la sau khi CA vong lap chay
    // xong). Nghia la try/catch quanh moi eventRepo.delete() khong bat duoc
    // gi ca luc runtime that: 1 constraint violation o bat ky row nao cung
    // chi lo dien luc flush cuoi cung va rollback CA loat, con log "Da xoa N
    // event" da chay xong tu truoc do nen bao cao sai (thanh cong trong khi
    // khong co gi duoc xoa that). SimpleJpaRepository.delete() da tu mang
    // @Transactional rieng cho tung loi goi, nen bo @Transactional o day de
    // moi delete() mo/flush/commit doc lap — try/catch tung item tro thanh
    // that, va bien dem "deleted" phan anh dung so event da xoa thanh cong.
    // Cung ly do voi AccountDeletionScheduler va ReminderScheduler (xem
    // comment cua 2 lop do).
    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Ho_Chi_Minh")
    public void cleanupExpiredHolidayReminders() {
        LocalDate today = LocalDate.now();
        List<Event> expired = eventRepo.findExpiredHolidayReminders(today);

        int deleted = 0;
        for (Event event : expired) {
            try {
                // Sự kiện quá hạn gần như chắc chắn đã bắn nhắc nhở (reminder
                // đã fire => có notifications.reminder_id trỏ vào), nên phải
                // gỡ liên kết đó trước khi xoá — giống EventService.update(),
                // nếu không MySQL sẽ chặn DELETE do khoá ngoại (error 1451)
                // khi orphanRemoval cascade xoá EventReminder của event.
                List<Long> reminderIds = event.getReminders().stream()
                        .map(EventReminder::getId)
                        .filter(reminderId -> reminderId != null)
                        .collect(Collectors.toList());
                if (!reminderIds.isEmpty()) {
                    notificationRepo.detachReminders(reminderIds);
                }

                eventRepo.delete(event);

                // Chỉ giảm total_events khi event vẫn đang active — query
                // findExpiredHolidayReminders() cố tình KHÔNG lọc isActive
                // (hard-delete luôn cả những event nhắc lễ đã bị người dùng
                // soft-delete trước đó qua UI là dọn dẹp hợp lệ), nhưng
                // những event đó ĐÃ bị trừ 1 lần rồi lúc EventService.delete()
                // gọi decrementEventCount — trừ thêm lần nữa ở đây sẽ khiến
                // counter bị âm/lệch xuống dưới giá trị thật.
                if (Boolean.TRUE.equals(event.getIsActive())) {
                    userRepo.decrementEventCount(event.getUser().getId());
                }

                deleted++;
            } catch (Exception e) {
                log.error("[HolidayCleanup] Loi khi xoa eventId={}: {}", event.getId(), e.getMessage());
            }
        }
        if (deleted > 0) {
            log.info("[HolidayCleanup] Da xoa {} event nhac nho ngay le da qua han", deleted);
        }
    }
}
