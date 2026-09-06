-- ============================================================
-- V25__20260906_fix_birth_column_types.sql
-- V24 tao birth_month/birth_day kieu TINYINT — Hibernate map Integer
-- (java.lang.Integer) mac dinh sang SQL INTEGER, gay loi
-- Schema-validation: wrong column type khi Flyway da chay V24 roi (khong
-- duoc sua lai noi dung V24 vi se lech checksum) -> sua kieu cot o day.
-- ============================================================

ALTER TABLE `relatives`
    MODIFY COLUMN `birth_month` INT NULL COMMENT '1-12, NULL neu chua co ngay sinh',
    MODIFY COLUMN `birth_day`   INT NULL COMMENT '1-31, NULL neu chua co ngay sinh';
