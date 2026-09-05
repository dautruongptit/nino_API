-- ============================================================
-- V16_20260808_add_lunar_recurrence.sql
-- Them ho tro su kien lap lai theo Am lich (ngay gio)
-- ============================================================

-- 1. Them cot luu ngay/thang Am lich co dinh (nguon su that de tinh lai
--    ngay Duong moi nam — khong luu lunar_year vi day la su kien lap lai)
ALTER TABLE `events`
    ADD COLUMN `lunar_day`   TINYINT DEFAULT NULL COMMENT 'Ngay Am lich (1-30), chi dung khi recurrence_type=LUNAR_YEARLY' AFTER `recurrence_type`,
    ADD COLUMN `lunar_month` TINYINT DEFAULT NULL COMMENT 'Thang Am lich (1-12), chi dung khi recurrence_type=LUNAR_YEARLY' AFTER `lunar_day`;

-- 2. Mo rong ENUM recurrence_type them gia tri LUNAR_YEARLY
ALTER TABLE `events`
    MODIFY COLUMN `recurrence_type`
        ENUM('YEARLY','MONTHLY','WEEKLY','LUNAR_YEARLY') DEFAULT NULL;
