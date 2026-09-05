package com.app.nino.repository;

import com.app.nino.model.entity.EventReminder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface EventReminderRepository extends JpaRepository<EventReminder, Long> {

    /**
     * Ứng viên cần kiểm tra nhắc nhở cho ReminderScheduler — giới hạn
     * event_date >= fromDate để không quét toàn bộ lịch sử sự kiện đã qua.
     */
    @Query("SELECT r FROM EventReminder r JOIN FETCH r.event e JOIN FETCH e.user"
         + " WHERE r.isEnabled = true"
         + " AND e.isActive = true"
         + " AND e.eventDate >= :fromDate")
    List<EventReminder> findDueCandidates(@Param("fromDate") LocalDate fromDate);
}
