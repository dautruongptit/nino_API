-- V29_20260907_add_login_history_device_name.sql
-- login_histories chi co deviceType/os/browser suy tu User-Agent (thuong la
-- "Unknown" voi HTTP client cua app mobile). UserDevice.deviceName da co san
-- ten thiet bi de doc duoc (VD "Pixel 8") nhung tu 1 API dang ky rieng, goi
-- SAU login -> khong gan duoc vao dung dong lich su dang nhap. Them cot nay
-- de client gui thang deviceName ngay luc login.

ALTER TABLE `login_histories`
    ADD COLUMN `device_name` VARCHAR(100) DEFAULT NULL AFTER `device_type`;
