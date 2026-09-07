-- V28_20260907_add_reminder_minutes_before.sql
-- Truoc day chi co remind_days_before / remind_hours_before -> khong the
-- bieu dien "nhac truoc 30 phut". Them remind_minutes_before de ho tro
-- moc nhac theo phut (xem EventReminder.computeTriggerTime).

ALTER TABLE `event_reminders`
    ADD COLUMN `remind_minutes_before` INT DEFAULT NULL AFTER `remind_hours_before`;
