-- V33__20260911_add_login_history_session_tracking.sql
-- login_histories la audit log thuan tuy, khong luu token/session nao — khong
-- co cach thu hoi tu xa 1 phien dang nhap tu dong nay. Them 3 cot de lam
-- duoc: session_id (claim "sid" cua JWT phien do, giu nguyen qua moi lan
-- refresh — xem JwtTokenProvider), refresh_expires_at (han refresh token
-- HIEN TAI cua phien, cap nhat lai moi lan refresh vi cua so 7 ngay truot
-- toi), revoked_at (thoi diem phien bi thu hoi — tu dang xuat thuong hoac
-- dang xuat tu xa). Dong cu (truoc migration nay) deu NULL o ca 3 cot —
-- khong the revoke duoc vi token cua chung chua tung co claim "sid".

ALTER TABLE `login_histories`
    ADD COLUMN `session_id`         VARCHAR(36) NULL COMMENT 'sid claim cua JWT phien nay, NULL cho dong that bai/truoc migration',
    ADD COLUMN `refresh_expires_at` DATETIME    NULL COMMENT 'Han refresh token hien tai (sau lan refresh gan nhat) cua phien nay',
    ADD COLUMN `revoked_at`         DATETIME    NULL COMMENT 'Thoi diem phien bi thu hoi, NULL = chua thu hoi',
    ADD INDEX `idx_login_histories_session_id` (`session_id`);
