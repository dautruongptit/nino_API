-- ============================================================
-- V21_20260902_add_relative_notes_and_sibling_group.sql
-- Sua bang: relatives
--   - them cot notes (ghi chu tu do, khop man Sua nguoi than thiet ke moi)
--   - them gia tri ANH_CHI_EM vao enum group_type
-- ============================================================

ALTER TABLE `relatives`
    MODIFY COLUMN `group_type` ENUM('GIA_DINH','VO_CHONG','CON_CAI','BAN_BE','ANH_CHI_EM') NOT NULL;

ALTER TABLE `relatives`
    ADD COLUMN `notes` TEXT DEFAULT NULL AFTER `hobbies`;
