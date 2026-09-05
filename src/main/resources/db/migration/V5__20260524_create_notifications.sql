-- ============================================================
-- V5_20260524_create_notifications.sql
-- Tao bang: notifications
-- FK: user_id → users, event_id → events
-- ============================================================

CREATE TABLE IF NOT EXISTS `notifications` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`    BIGINT       NOT NULL,
    `event_id`   BIGINT       DEFAULT NULL,
    `title`      VARCHAR(200) NOT NULL,
    `body`       TEXT         NOT NULL,
    `is_read`    TINYINT(1)   DEFAULT '0',
    `sent_at`    DATETIME(6)  NOT NULL,
    `created_at` DATETIME(6)  DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_notif_user`  (`user_id`),
    KEY `idx_notif_read`  (`user_id`, `is_read`),
    KEY `fk_notif_event`  (`event_id`),
    CONSTRAINT `fk_notif_user`
        FOREIGN KEY (`user_id`)  REFERENCES `users`  (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_notif_event`
        FOREIGN KEY (`event_id`) REFERENCES `events` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
