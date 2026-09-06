package com.app.nino.util;

import java.text.Normalizer;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Tien ich xu ly chuoi tieng Viet — strip dau, so sanh khong phan biet
 * hoa/thuong va co dau/khong dau.
 *
 * Java Normalizer NFD tach thanh dieu ra khoi ky tu goc roi regex loai bo
 * combining marks. Rieng d/D phai thay bang tay vi khong co dang decomposed.
 */
public final class VietnameseUtils {

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");

    private static final Map<Character, Character> SPECIAL = Map.of(
        'đ', 'd',  // đ
        'Đ', 'D'   // Đ
    );

    private VietnameseUtils() {}

    /**
     * Strip toan bo dau tieng Viet + lowercase.
     * VD: "Du lịch" -> "du lich", "Đám cưới" -> "dam cuoi"
     */
    public static String normalize(String input) {
        if (input == null || input.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(input.length());
        for (char c : input.toCharArray()) {
            sb.append(SPECIAL.getOrDefault(c, c));
        }
        String nfd = Normalizer.normalize(sb.toString(), Normalizer.Form.NFD);
        return COMBINING_MARKS.matcher(nfd).replaceAll("").toLowerCase();
    }

    /**
     * So sanh 2 chuoi khong phan biet hoa/thuong va co dau/khong dau.
     */
    public static boolean equalsIgnoreAccent(String a, String b) {
        return normalize(a).equals(normalize(b));
    }
}
