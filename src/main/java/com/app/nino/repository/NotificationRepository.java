package com.app.nino.repository;

import com.app.nino.model.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findByUserIdOrderBySentAtDesc(Long userId, Pageable pageable);

    long countByUserIdAndIsReadFalse(Long userId);

    @Modifying
    @Query("UPDATE Notification n SET n.isRead = true WHERE n.user.id = :userId AND n.isRead = false")
    void markAllAsRead(@Param("userId") Long userId);

    /** Thông báo gần nhất của 1 reminder — ReminderScheduler dùng để biết
     * lần bắn trước (của reminder lặp lại) đã được đọc hay chưa. */
    Optional<Notification> findFirstByReminderIdOrderBySentAtDesc(Long reminderId);

    /** Gỡ liên kết reminder_id (giữ nguyên lịch sử thông báo, chỉ null hoá
     * tham chiếu) trước khi xoá các EventReminder — EventService.update()
     * xoá/tạo lại toàn bộ reminders của sự kiện mỗi lần sửa, và
     * orphanRemoval=true trên Event.reminders sẽ DELETE các EventReminder
     * cũ; nếu một thông báo đã bắn còn trỏ reminder_id vào đó thì DELETE
     * đó vi phạm khoá ngoại (MySQL error 1451). */
    @Modifying
    @Query("UPDATE Notification n SET n.reminder = null WHERE n.reminder.id IN :reminderIds")
    void detachReminders(@Param("reminderIds") List<Long> reminderIds);
}
