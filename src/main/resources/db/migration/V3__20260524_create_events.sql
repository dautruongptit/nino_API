-- ============================================================
-- V3_20260524_create_events.sql
-- Tao bang: events, event_reminders
-- FK: events.user_id → users, events.relative_id → relatives
--     event_reminders.event_id → events
-- ============================================================

CREATE TABLE IF NOT EXISTS `events` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`         BIGINT       NOT NULL,
    `relative_id`     BIGINT       DEFAULT NULL,
    `title`           VARCHAR(200) NOT NULL,
    `event_type`      ENUM('SINH_NHAT','KY_NIEM','LE','NHA_O','HOA_DON','MUA_SAM','KHAC') NOT NULL,
    `event_date`      DATE         NOT NULL,
    `event_time`      TIME         DEFAULT NULL,
    `is_recurring`    TINYINT(1)   DEFAULT '0',
    `recurrence_type` ENUM('YEARLY','MONTHLY','WEEKLY') DEFAULT NULL,
    `notes`           TEXT         DEFAULT NULL,
    `is_active`       TINYINT(1)   DEFAULT '1',
    `created_at`      DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `user_id`     (`user_id`),
    KEY `relative_id` (`relative_id`),
    CONSTRAINT `events_ibfk_1`
        FOREIGN KEY (`user_id`)     REFERENCES `users`     (`id`) ON DELETE CASCADE,
    CONSTRAINT `events_ibfk_2`
        FOREIGN KEY (`relative_id`) REFERENCES `relatives` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ─────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS `event_reminders` (
    `id`                  BIGINT     NOT NULL AUTO_INCREMENT,
    `event_id`            BIGINT     NOT NULL,
    `remind_days_before`  INT        DEFAULT NULL,
    `remind_hours_before` INT        DEFAULT NULL,
    `is_enabled`          TINYINT(1) DEFAULT '1',
    `created_at`          DATETIME   DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `event_id` (`event_id`),
    CONSTRAINT `event_reminders_ibfk_1`
        FOREIGN KEY (`event_id`) REFERENCES `events` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
