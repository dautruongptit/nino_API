-- ============================================================
-- V14_20260808_migrate_events_category.sql
-- Chuyen events.event_type (ENUM string) -> events.category_id (FK)
-- Chay SAU V13 (da co san 7 danh muc he thong)
-- ============================================================

-- 1. Them cot moi (tam thoi cho phep NULL de migrate du lieu)
ALTER TABLE `events`
    ADD COLUMN `category_id` BIGINT DEFAULT NULL AFTER `event_type`;

-- 2. Gan category_id dua theo event_type cu (khop code)
UPDATE `events` e
JOIN `event_categories` c ON c.code = e.event_type
SET e.category_id = c.id;

-- 3. Kiem tra khong con dong nao NULL truoc khi bat NOT NULL
--    (neu co du lieu la, se bao loi o buoc 4 — kiem tra thu cong)

-- 4. Bat buoc NOT NULL + them FK
ALTER TABLE `events`
    MODIFY COLUMN `category_id` BIGINT NOT NULL;

ALTER TABLE `events`
    ADD CONSTRAINT `fk_events_category`
        FOREIGN KEY (`category_id`) REFERENCES `event_categories` (`id`);

ALTER TABLE `events`
    ADD KEY `idx_events_category` (`category_id`);

-- 5. Xoa cot event_type cu — KHONG con dung nua
ALTER TABLE `events`
    DROP COLUMN `event_type`;
