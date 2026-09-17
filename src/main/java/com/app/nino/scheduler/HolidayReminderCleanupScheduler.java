package com.app.nino.scheduler;

import com.app.nino.model.entity.Event;
import com.app.nino.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

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

    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Ho_Chi_Minh")
    @Transactional
    public void cleanupExpiredHolidayReminders() {
        LocalDate today = LocalDate.now();
        List<Event> expired = eventRepo.findExpiredHolidayReminders(today);

        int deleted = 0;
        for (Event event : expired) {
            try {
                eventRepo.delete(event);
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
