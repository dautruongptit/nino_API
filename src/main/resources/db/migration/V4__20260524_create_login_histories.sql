-- ============================================================
-- V4_20260524_create_login_histories.sql
-- Tao bang: login_histories
-- FK: user_id → users(id)
-- ============================================================

CREATE TABLE IF NOT EXISTS `login_histories` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`        BIGINT       NOT NULL,
    `ip_address`     VARCHAR(45)  DEFAULT NULL,
    `user_agent`     VARCHAR(500) DEFAULT NULL,
    `device_type`    VARCHAR(30)  DEFAULT NULL,
    `os`             VARCHAR(100) DEFAULT NULL,
    `browser`        VARCHAR(100) DEFAULT NULL,
    `country`        VARCHAR(100) DEFAULT NULL,
    `is_success`     TINYINT(1)   NOT NULL DEFAULT '0',
    `failure_reason` VARCHAR(200) DEFAULT NULL,
    `login_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_lh_user_time` (`user_id`, `login_at` DESC),
    KEY `idx_lh_ip`        (`ip_address`),
    KEY `idx_lh_success`   (`user_id`, `is_success`),
    CONSTRAINT `fk_lh_user`
        FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
