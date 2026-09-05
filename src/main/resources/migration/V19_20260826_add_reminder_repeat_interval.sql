-- V19_20260826_add_reminder_repeat_interval.sql
-- Nhac lap lai moi N phut cho toi khi doc thong bao (VD: nhac uong thuoc
-- moi 30 phut). Can biet thong bao lan ban gan nhat cua 1 reminder da doc
-- chua -> them lien ket reminder_id vao notifications.

ALTER TABLE `event_reminders`
    ADD COLUMN `repeat_interval_minutes` INT DEFAULT NULL AFTER `is_enabled`;

ALTER TABLE `notifications`
    ADD COLUMN `reminder_id` BIGINT DEFAULT NULL AFTER `event_id`,
    ADD CONSTRAINT `notifications_reminder_fk`
        FOREIGN KEY (`reminder_id`) REFERENCES `event_reminders` (`id`) ON DELETE SET NULL;
