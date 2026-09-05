-- V17_20260826_add_reminder_notified_at.sql
-- Them cot notified_at vao event_reminders: ReminderScheduler quet moi 5 phut
-- (thay vi 1 lan/ngay luc 8h) va dung cot nay de tranh gui trung nhac nho
-- cho cung 1 lan trigger. NULL = chua tung gui.

ALTER TABLE `event_reminders`
    ADD COLUMN `notified_at` DATETIME DEFAULT NULL AFTER `is_enabled`;
