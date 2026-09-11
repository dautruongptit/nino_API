-- ============================================================
-- V31__20260911_add_user_phone_gender_dob.sql
-- Them cot phone, gender, birth_month/birth_day/birth_year vao bang
-- users — cung cau truc voi relatives (V21/V24) de man "Sua ho so"
-- (edit_profile_screen.dart) co the luu duoc Dien thoai/Gioi tinh/Ngay
-- sinh, thay vi chi giu tam o client roi bo qua khi Luu.
--
-- birth_year NULLABLE, khong dung gia tri dai dien nao khi khong ro nam
-- sinh (giong quyet dinh da ap dung cho relatives o V24).
-- ============================================================

ALTER TABLE `users`
    ADD COLUMN `phone`       VARCHAR(20) NULL COMMENT 'So dien thoai, khong bat buoc' AFTER `avatar_url`,
    ADD COLUMN `gender`      VARCHAR(10) NULL COMMENT 'MALE, FEMALE, OTHER' AFTER `phone`,
    ADD COLUMN `birth_month` TINYINT     NULL COMMENT '1-12, NULL neu chua co ngay sinh' AFTER `gender`,
    ADD COLUMN `birth_day`   TINYINT     NULL COMMENT '1-31, NULL neu chua co ngay sinh' AFTER `birth_month`,
    ADD COLUMN `birth_year`  INT         NULL COMMENT 'NULL = khong ro nam sinh' AFTER `birth_day`;
