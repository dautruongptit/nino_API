-- V35__20260917_add_event_is_holiday_reminder.sql
-- Danh dau Event duoc tao tu luong "Nhac toi" o man Lich nghi le
-- (holiday_screen.dart), de HolidayReminderCleanupScheduler biet dong nao
-- duoc phep tu dong xoa sau khi qua ngay — KHONG bao gio suy doan co flag
-- nay tu title/date, chi set 1 lan luc tao va xoa ngay khi user sua event.
ALTER TABLE `events`
    ADD COLUMN `is_holiday_reminder` BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'true = tao tu man Lich nghi le, du dieu kien tu dong xoa sau khi qua ngay';
