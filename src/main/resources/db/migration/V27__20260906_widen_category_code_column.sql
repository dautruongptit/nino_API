-- ============================================================
-- V27_20260906_widen_category_code_column.sql
-- Mo rong cot code tu VARCHAR(20) sang VARCHAR(50) de chua
-- code tu sinh cho danh muc user: CUSTOM_<userId>_<timestamp>
-- VD: CUSTOM_12_1725625200000 = 25 ky tu, vuot qua 20.
-- ============================================================

ALTER TABLE `event_categories` MODIFY COLUMN `code` VARCHAR(50) NOT NULL;
