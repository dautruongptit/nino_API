-- ============================================================
-- V24__20260906_split_relative_birth_date.sql
-- Tach date_of_birth (DATE) + date_of_birth_year_known (bool) thanh 3 cot
-- rieng: birth_month, birth_day, birth_year (deu NULLABLE).
--
-- Ly do: date_of_birth_year_known=false truoc day di kem 1 nam DAI DIEN
-- gia (VD 1900) trong date_of_birth vi cot DATE bat buoc phai co nam.
-- Thiet ke moi bo han gia tri dai dien do di — birth_year=NULL nghia la
-- "khong ro nam sinh", khong con so nao can an di o tang UI nua.
--
-- Du lieu cu duoc chuyen doi truoc khi xoa 2 cot cu:
--   - date_of_birth_year_known = 1 -> birth_year = YEAR(date_of_birth)
--   - date_of_birth_year_known = 0 -> birth_year = NULL (bo nam dai dien)
--   - date_of_birth IS NULL        -> ca 3 cot moi deu NULL
-- ============================================================

ALTER TABLE `relatives`
    ADD COLUMN `birth_month` TINYINT NULL COMMENT '1-12, NULL neu chua co ngay sinh' AFTER `date_of_birth_year_known`,
    ADD COLUMN `birth_day`   TINYINT NULL COMMENT '1-31, NULL neu chua co ngay sinh' AFTER `birth_month`,
    ADD COLUMN `birth_year`  INT     NULL COMMENT 'NULL = khong ro nam sinh (khong dung gia tri dai dien nao)' AFTER `birth_day`;

UPDATE `relatives`
SET
    `birth_month` = MONTH(`date_of_birth`),
    `birth_day`   = DAY(`date_of_birth`),
    `birth_year`  = CASE WHEN `date_of_birth_year_known` = 1 THEN YEAR(`date_of_birth`) ELSE NULL END
WHERE `date_of_birth` IS NOT NULL;

ALTER TABLE `relatives`
    DROP COLUMN `date_of_birth`,
    DROP COLUMN `date_of_birth_year_known`;
