package com.app.nino.service;

import com.app.nino.model.entity.Event;
import com.app.nino.model.entity.EventCategory;
import com.app.nino.model.entity.EventReminder;
import org.springframework.stereotype.Service;

import java.util.Map;

/** Sinh title/body cho Notification, thay cho chuoi hardcode truoc day o
 *  ReminderScheduler. Title suy ra tu du lieu category co san (displayName +
 *  icon) — khong hardcode rieng tung category — de category user tu tao sau
 *  nay (chua co UI, nhung schema da ho tro) tu dong co template hop ly ma
 *  khong can sua code. Body chi co 1 quy tac rieng cho SINH_NHAT (goi ten
 *  nguoi than), con lai deu dung event.getTitle(). */
@Service
public class NotificationTemplateService {

    public record NotificationContent(String title, String body) {}

    private static final String DEFAULT_ICON_EMOJI = "🔔";
    private static final Map<String, String> ICON_EMOJI = Map.of(
        "cake", "🎂",
        "favorite", "💝",
        "card_giftcard", "🎁",
        "home", "🏠",
        "bolt", "⚡",
        "shopping_bag", "🛍️",
        "more_horiz", "🔔"
    );

    private static final String BIRTHDAY_CATEGORY_CODE = "SINH_NHAT";

    public NotificationContent build(Event event, EventReminder reminder) {
        EventCategory category = event.getCategory();
        String emoji = ICON_EMOJI.getOrDefault(category.getIcon(), DEFAULT_ICON_EMOJI);
        boolean daysScale = isDaysScale(reminder);
        String title = emoji + " " + category.getDisplayName() + (daysScale ? " sắp tới" : " sắp đến");
        String body = subject(event, category) + " · " + timingPhrase(reminder);
        return new NotificationContent(title, body);
    }

    private boolean isDaysScale(EventReminder reminder) {
        return reminder.getRemindDaysBefore() != null && reminder.getRemindDaysBefore() > 0;
    }

    private String subject(Event event, EventCategory category) {
        if (!BIRTHDAY_CATEGORY_CODE.equals(category.getCode())) {
            return event.getTitle();
        }
        // RelativeService tao Event "Sinh nhat" voi title da la "Sinh nhat " +
        // displayName (xem RelativeService.java:271) — khong duoc cong them
        // "Sinh nhat " lan nua o day khi khong co relative, se bi lap chu.
        return event.getRelative() != null
            ? "Sinh nhật " + event.getRelative().getName()
            : event.getTitle();
    }

    private String timingPhrase(EventReminder reminder) {
        Integer days = reminder.getRemindDaysBefore();
        Integer hours = reminder.getRemindHoursBefore();
        Integer minutes = reminder.getRemindMinutesBefore();

        if (days != null && days > 0) {
            return days == 1 ? "Ngày mai" : "Còn " + days + " ngày";
        }
        if (hours != null && hours == 0) {
            return "Sau 30 phút";
        }
        if (hours != null && hours > 0) {
            return "Sau " + hours + " giờ";
        }
        if (minutes != null && minutes > 0) {
            return "Sau " + minutes + " phút";
        }
        return "Hôm nay";
    }
}
