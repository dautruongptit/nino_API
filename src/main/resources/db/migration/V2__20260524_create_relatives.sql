-- ============================================================
-- V2_20260524_create_relatives.sql
-- Tao bang: relatives
-- FK: user_id → users(id)
-- ============================================================

CREATE TABLE IF NOT EXISTS `relatives` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`       BIGINT       NOT NULL,
    `name`          VARCHAR(100) NOT NULL,
    `nickname`      VARCHAR(50)  DEFAULT NULL,
    `group_type`    ENUM('GIA_DINH','VO_CHONG','CON_CAI','BAN_BE') NOT NULL,
    `gender`        ENUM('MALE','FEMALE','OTHER')                   DEFAULT NULL,
    `date_of_birth` DATE         DEFAULT NULL,
    `location`      VARCHAR(200) DEFAULT NULL,
    `height_cm`     DECIMAL(5,1) DEFAULT NULL,
    `weight_kg`     DECIMAL(5,1) DEFAULT NULL,
    `hobbies`       TEXT         DEFAULT NULL,
    `avatar_url`    VARCHAR(255) DEFAULT NULL,
    `total_events`  INT          DEFAULT '0',
    `created_at`    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `user_id` (`user_id`),
    CONSTRAINT `relatives_ibfk_1`
        FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
