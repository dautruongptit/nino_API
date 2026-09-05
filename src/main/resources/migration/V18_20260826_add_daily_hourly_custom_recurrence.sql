-- V18_20260826_add_daily_hourly_custom_recurrence.sql
-- Mo rong recurrence_type them DAILY, HOURLY, CUSTOM (app da co san 2 option
-- "Hang ngay" va "Tuy chinh" trong dropdown nhung backend chua ho tro, chon
-- vao la loi). CUSTOM dung kem 2 cot moi de luu chu ky tuy chinh (VD: moi 3 tuan).

ALTER TABLE `events`
    MODIFY COLUMN `recurrence_type`
        ENUM('YEARLY','MONTHLY','WEEKLY','LUNAR_YEARLY','DAILY','HOURLY','CUSTOM') DEFAULT NULL,
    ADD COLUMN `custom_interval_value` INT DEFAULT NULL AFTER `lunar_month`,
    ADD COLUMN `custom_interval_unit` ENUM('HOUR','DAY','WEEK','MONTH','YEAR') DEFAULT NULL AFTER `custom_interval_value`;
