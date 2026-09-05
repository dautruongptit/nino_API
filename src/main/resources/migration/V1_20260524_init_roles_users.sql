-- ============================================================
-- V1_20260524_init_roles_users.sql
-- Tao bang: roles, users, user_roles
-- Thu tu: roles → users → user_roles (theo FK dependency)
-- ============================================================

CREATE TABLE IF NOT EXISTS `roles` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `name`        VARCHAR(50)  NOT NULL,
    `description` VARCHAR(200) DEFAULT NULL,
    `created_at`  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ─────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS `users` (
    `id`                    BIGINT       NOT NULL AUTO_INCREMENT,
    `username`              VARCHAR(100) NOT NULL DEFAULT '',
    `full_name`             VARCHAR(100) NOT NULL,
    `email`                 VARCHAR(150) NOT NULL,
    `password_hash`         VARCHAR(255) NOT NULL,
    `status`                VARCHAR(3)   NOT NULL DEFAULT 'REG'
        COMMENT 'GST=Guest,REG=Registered,VRF=Verified,ACT=Active,INA=Inactive,LCK=Locked,BAN=Banned,DEL=Deleted',
    `avatar_url`            VARCHAR(255) DEFAULT NULL,
    `language`              VARCHAR(10)  DEFAULT 'vi',
    `dark_mode`             TINYINT(1)   DEFAULT '0',
    `total_events`          INT          DEFAULT '0',
    `total_relatives`       INT          DEFAULT '0',
    `google_calendar_token` TEXT         DEFAULT NULL,
    `is_active`             TINYINT(1)   DEFAULT '1',
    `created_at`            DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`            DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `failed_login_count`    INT          NOT NULL DEFAULT '0',
    `last_failed_at`        DATETIME     DEFAULT NULL,
    `last_login_at`         DATETIME     DEFAULT NULL,
    `total_login_count`     INT          NOT NULL DEFAULT '0',
    `locked_until`          DATETIME     DEFAULT NULL,
    `last_login_ip`         VARCHAR(45)  DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `email`              (`email`),
    UNIQUE KEY `idx_users_username` (`username`),
    KEY        `idx_users_status`   (`status`),
    CONSTRAINT `chk_user_status`
        CHECK (`status` IN ('GST','REG','VRF','ACT','INA','LCK','BAN','DEL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ─────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS `user_roles` (
    `user_id`     BIGINT   NOT NULL,
    `role_id`     BIGINT   NOT NULL,
    `assigned_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`user_id`, `role_id`),
    KEY `fk_ur_role`          (`role_id`),
    KEY `idx_user_roles_user`  (`user_id`),
    CONSTRAINT `fk_ur_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_ur_role` FOREIGN KEY (`role_id`) REFERENCES `roles` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
