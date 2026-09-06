-- ============================================================
-- V20_20260830_create_request_logs.sql
-- Tao bang: request_logs
-- Luu lai request/response (dang JSON, da mask du lieu nhay cam)
-- cua moi API call cung voi nguoi tao request, phuc vu audit.
-- user_id KHONG co FK (giong cach JwtAuthFilter dung principal la
-- Long truc tiep) vi request co the la anonymous (login/register)
-- hoac user sau nay bi xoa nhung log van phai giu nguyen.
-- ============================================================

CREATE TABLE IF NOT EXISTS `request_logs` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `request_id`    VARCHAR(36)  DEFAULT NULL,
    `http_method`   VARCHAR(10)  NOT NULL,
    `uri`           VARCHAR(500) NOT NULL,
    `user_id`       BIGINT       DEFAULT NULL,
    `request_body`  TEXT         DEFAULT NULL,
    `response_body` TEXT         DEFAULT NULL,
    `status_code`   INT          DEFAULT NULL,
    `ip_address`    VARCHAR(45)  DEFAULT NULL,
    `duration_ms`   BIGINT       DEFAULT NULL,
    `created_at`    DATETIME(6)  NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_request_logs_user`       (`user_id`),
    KEY `idx_request_logs_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
