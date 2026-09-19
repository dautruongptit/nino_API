-- V36__20260919_add_login_history_city.sql
-- Cot `country` co san nhung chua bao gio duoc ghi (GeoIpService moi them —
-- xem AuthService.saveLoginHistory). Them `city` de hien thi vi tri chi tiet
-- hon ("Thanh pho, Quoc gia") o man Lich su dang nhap thay vi chi Quoc gia.

ALTER TABLE `login_histories`
    ADD COLUMN `city` VARCHAR(100) NULL COMMENT 'Thanh pho tra tu IP qua GeoIpService, NULL neu chua tra/IP private/tra loi' AFTER `country`;
