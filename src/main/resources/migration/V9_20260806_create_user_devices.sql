-- ============================================================
-- V9_20260806_create_user_devices.sql
-- Tao bang user_devices — luu FCM token theo tung thiet bi
-- Ho tro nhieu thiet bi/user (dien thoai + tablet cung dang nhap)
-- ============================================================

CREATE TABLE IF NOT EXISTS `user_devices` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`    BIGINT       NOT NULL,
    `fcm_token`  VARCHAR(255) NOT NULL,
    `platform`   VARCHAR(10)  NOT NULL COMMENT 'ANDROID, IOS, WEB',
    `device_name` VARCHAR(100) DEFAULT NULL COMMENT 'VD: iPhone 15, Samsung S23',
    `created_at` DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `idx_devices_token` (`fcm_token`),
    KEY `idx_devices_user` (`user_id`),
    CONSTRAINT `fk_devices_user`
        FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
    CONSTRAINT `chk_devices_platform`
        CHECK (`platform` IN ('ANDROID','IOS','WEB'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
