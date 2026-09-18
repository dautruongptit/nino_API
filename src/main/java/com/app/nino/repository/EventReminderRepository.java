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
     *
     * JOIN FETCH e.category + LEFT JOIN FETCH e.relative: ReminderScheduler
     * KHÔNG còn @Transactional (xem comment của lớp đó) nên không có Hibernate
     * session mở sẵn lúc fireReminder() -> NotificationTemplateService.build()
     * đọc event.getCategory()/event.getRelative().getName() — phải fetch sẵn
     * 2 quan hệ LAZY này trong chính câu query, giống pattern
     * EventRepository (xem comment ở đó cho HolidayReminderCleanupScheduler).
     * Thiếu fetch này gây LazyInitializationException bị nuốt âm thầm bởi
     * try/catch trong fireDueGroup() -> tính năng nhắc nhở im lặng ngừng hoạt
     * động hoàn toàn, quét lại thất bại y hệt mỗi 5 phút, không bao giờ gửi
     * được thông báo nào, mà không có lỗi nào lộ ra ngoài.
     * LEFT JOIN FETCH cho e.relative (không phải inner JOIN): Event.relative
     * nullable — inner join sẽ loại khỏi kết quả MỌI reminder của sự kiện
     * không gắn người thân, còn tệ hơn bug đang sửa. e.category luôn tồn tại
     * (nullable = false) nên dùng inner JOIN FETCH là an toàn.
     */
    @Query("SELECT r FROM EventReminder r JOIN FETCH r.event e JOIN FETCH e.user"
         + " JOIN FETCH e.category"
         + " LEFT JOIN FETCH e.relative"
         + " WHERE r.isEnabled = true"
         + " AND e.isActive = true"
         + " AND e.eventDate >= :fromDate"
         + " AND e.user.status <> 'DEL'")
    List<EventReminder> findDueCandidates(@Param("fromDate") LocalDate fromDate);
}
