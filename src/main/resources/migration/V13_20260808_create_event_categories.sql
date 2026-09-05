-- ============================================================
-- V13_20260808_create_event_categories.sql
-- Tao bang event_categories — quan ly danh muc, icon, mau tu DB
-- Thay the ENUM event_type cu (SEC: Danh muc su kien)
-- ============================================================

CREATE TABLE IF NOT EXISTS `event_categories` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `code`         VARCHAR(20)  NOT NULL,
    `display_name` VARCHAR(50)  NOT NULL,
    `icon`         VARCHAR(50)  NOT NULL COMMENT 'Ten icon dung chung Backend + Flutter',
    `color_hex`    VARCHAR(7)   NOT NULL COMMENT 'VD: #FF6B6B',
    `is_system`    TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1=he thong, 0=user tu tao (danh cho tuong lai)',
    `user_id`      BIGINT       DEFAULT NULL COMMENT 'NULL neu la danh muc he thong',
    `sort_order`   INT          NOT NULL DEFAULT 0,
    `created_at`   DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `idx_categories_code` (`code`),
    KEY `idx_categories_user` (`user_id`),
    CONSTRAINT `fk_categories_user`
        FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ── Seed 7 danh muc he thong — khop voi EventType cu + mau tu Figma ──

INSERT IGNORE INTO `event_categories`
    (`id`, `code`, `display_name`, `icon`, `color_hex`, `is_system`, `sort_order`)
VALUES
    (1, 'SINH_NHAT', 'Sinh nhật',   'cake',          '#FF6B6B', 1, 1),
    (2, 'KY_NIEM',   'Kỷ niệm',     'favorite',      '#9B59B6', 1, 2),
    (3, 'LE',        'Lễ/Tết',      'card_giftcard', '#F5A623', 1, 3),
    (4, 'NHA_O',     'Nhà ở',       'home',          '#F48FB1', 1, 4),
    (5, 'HOA_DON',   'Hóa đơn',     'bolt',          '#F5C518', 1, 5),
    (6, 'MUA_SAM',   'Mua sắm',     'shopping_bag',  '#1ABC9C', 1, 6),
    (7, 'KHAC',      'Khác',        'more_horiz',    '#95A5A6', 1, 7);
