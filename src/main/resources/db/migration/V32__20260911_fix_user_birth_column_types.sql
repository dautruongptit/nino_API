-- ============================================================
-- V32__20260911_fix_user_birth_column_types.sql
-- V31 tao birth_month/birth_day kieu TINYINT — Hibernate map Integer
-- (java.lang.Integer) mac dinh sang SQL INTEGER, gay loi
-- Schema-validation: wrong column type (dung y het truong hop
-- V25__20260906_fix_birth_column_types.sql da sua cho relatives).
-- Khong sua lai noi dung V31 vi se lech checksum voi ban da chay.
-- ============================================================

ALTER TABLE `users`
    MODIFY COLUMN `birth_month` INT NULL COMMENT '1-12, NULL neu chua co ngay sinh',
    MODIFY COLUMN `birth_day`   INT NULL COMMENT '1-31, NULL neu chua co ngay sinh';
