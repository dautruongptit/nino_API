-- ============================================================
-- V11_20260807_create_event_participants.sql
-- Bang phu: ho tro su kien gan NHIEU nguoi than (tuy chon)
-- KHONG thay the relative_id trong events — chi bo sung khi can
-- ============================================================

CREATE TABLE IF NOT EXISTS `event_participants` (
    `event_id`    BIGINT NOT NULL,
    `relative_id` BIGINT NOT NULL,
    `created_at`  DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`event_id`, `relative_id`),
    KEY `idx_participants_relative` (`relative_id`),
    CONSTRAINT `fk_participants_event`
        FOREIGN KEY (`event_id`) REFERENCES `events` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_participants_relative`
        FOREIGN KEY (`relative_id`) REFERENCES `relatives` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ── Migrate du lieu da co: "Ky niem ngay cuoi" dang gan relative_id=3 (Vo) ──
-- Chuyen sang dung participants, giu relative_id de tuong thich nguoc

INSERT IGNORE INTO `event_participants` (`event_id`, `relative_id`)
SELECT id, relative_id FROM `events`
WHERE id = 5 AND relative_id IS NOT NULL;   -- event "Ky niem ngay cuoi"
