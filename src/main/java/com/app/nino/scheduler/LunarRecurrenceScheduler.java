package com.app.nino.scheduler;

import com.app.nino.model.entity.Event;
import com.app.nino.repository.EventRepository;
import com.app.nino.service.LunarCalendarService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Scheduler rieng, chay TRUOC ReminderScheduler (8h sang), de dam bao
 * khi ReminderScheduler quet su kien can nhac, event_date cua cac
 * su kien LUNAR_YEARLY da duoc cap nhat dung cho ky tiep theo.
 *
 * Vi du: "Gio Ong Noi" luu lunar_day=20, lunar_month=7.
 * Sau khi ngay gio nam nay (VD 2026-08-20) da qua, job nay se tu dong
 * tinh lai event_date cho nam sau (VD 2027-08-08 — vi am lich moi nam
 * lech ngay duong khac nhau).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LunarRecurrenceScheduler {

    private final EventRepository        eventRepo;
    private final LunarCalendarService lunarCalendarService;

    // Chay luc 7:00 sang — TRUOC ReminderScheduler (8:00 sang)
    @Scheduled(cron = "0 0 7 * * *", zone = "Asia/Ho_Chi_Minh")
    @Transactional
    public void recalculateLunarEvents() {
        LocalDate today = LocalDate.now();

        List<Event> lunarEvents = eventRepo
            .findByRecurrenceTypeAndIsActiveTrue(Event.RecurrenceType.LUNAR_YEARLY);

        int updated = 0;
        for (Event event : lunarEvents) {
            if (event.getLunarDay() == null || event.getLunarMonth() == null) {
                log.warn("[LunarScheduler] Event id={} thieu lunar_day/lunar_month, bo qua",
                    event.getId());
                continue;
            }

            // Neu ngay hien tai da qua eventDate luu trong DB -> tinh lai cho ky toi
            if (event.getEventDate().isBefore(today)) {
                LocalDate nextOccurrence = lunarCalendarService.getNextSolarOccurrence(
                    event.getLunarDay(), event.getLunarMonth(), today);

                event.setEventDate(nextOccurrence);
                eventRepo.save(event);
                updated++;

                log.info("[LunarScheduler] Cap nhat event id={} '{}': ngay am {}/{} -> duong {}",
                    event.getId(), event.getTitle(),
                    event.getLunarDay(), event.getLunarMonth(), nextOccurrence);
            }
        }

        if (updated > 0) {
            log.info("[LunarScheduler] Da cap nhat {} su kien Am lich cho ky tiep theo", updated);
        }
    }
}
