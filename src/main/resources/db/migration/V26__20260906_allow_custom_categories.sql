-- ============================================================
-- V26_20260906_allow_custom_categories.sql
-- Cho phep user tu tao danh muc su kien rieng (is_system=0).
-- UNIQUE(code) cu chan moi user dung cung code — doi thanh
-- UNIQUE(code, user_id) de tach biet code he thong vs user.
-- ============================================================

-- Bo UNIQUE cu tren code (ten index la idx_categories_code tu V13)
ALTER TABLE `event_categories` DROP INDEX `idx_categories_code`;

-- Tao UNIQUE moi: code + user_id (NULL = he thong, distinct per user)
-- MySQL coi NULL != NULL trong UNIQUE => danh muc he thong (user_id=NULL)
-- van cho phep nhieu dong co code khac nhau ma khong xung dot.
ALTER TABLE `event_categories`
    ADD UNIQUE INDEX `idx_categories_code_user` (`code`, `user_id`);
