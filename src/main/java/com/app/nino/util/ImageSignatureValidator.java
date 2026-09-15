package com.app.nino.util;

/**
 * Nhan dien content-type THAT cua 1 file anh dua tren magic byte (file
 * signature) o dau file, KHONG dua vao Content-Type header client tu khai
 * bao trong multipart request — header nay client gui gi cung duoc, hoan
 * toan co the gia mao (vd set "image/png" nhung noi dung thuc te la file
 * khac). Dung truoc khi ghi avatar xuong dia, xem AuthService.uploadAvatar.
 *
 * Chi nhan dien dung 4 dinh dang nam trong whitelist avatar hien tai
 * (AuthService.ALLOWED_AVATAR_TYPES) — KHONG nhan SVG (co the chua
 * script) hay bat ky dinh dang nao khac, du co dung magic byte that.
 */
public final class ImageSignatureValidator {

    private ImageSignatureValidator() {}

    /** @return content-type chuan (vd "image/png") neu [bytes] khop 1 trong 4
     *  signature duoc ho tro, null neu khong khop dinh dang nao. */
    public static String detect(byte[] bytes) {
        if (bytes == null) return null;

        if (matches(bytes, 0, 0xFF, 0xD8, 0xFF)) {
            return "image/jpeg";
        }
        if (matches(bytes, 0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "image/png";
        }
        // "GIF87a" hoac "GIF89a"
        if (matches(bytes, 0, 'G', 'I', 'F', '8') && (matches(bytes, 4, '7', 'a') || matches(bytes, 4, '9', 'a'))) {
            return "image/gif";
        }
        // RIFF <4 byte kich thuoc> WEBP — byte 4-7 la do dai file nen bo qua, chi kiem tra 0-3 va 8-11
        if (matches(bytes, 0, 'R', 'I', 'F', 'F') && matches(bytes, 8, 'W', 'E', 'B', 'P')) {
            return "image/webp";
        }
        return null;
    }

    private static boolean matches(byte[] data, int offset, int... expected) {
        if (data.length < offset + expected.length) return false;
        for (int i = 0; i < expected.length; i++) {
            if ((data[offset + i] & 0xFF) != (expected[i] & 0xFF)) return false;
        }
        return true;
    }
}
