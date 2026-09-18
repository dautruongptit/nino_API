# Notification Template Engine — Design Spec

**Date:** 2026-09-19

**Goal:** Replace `ReminderScheduler`'s hardcoded notification title/body (`"Nhắc nhở: " + event.getTitle()` / `"Sự kiện '%s' diễn ra vào ngày %s"`) with a template engine keyed on event category × reminder timing, so notification wording is centralized, consistent, and automatically extends to future custom event categories without new code.

**Repo touched:** `nino-api` only (backend). No mobile change — `Notification.title`/`.body` already flow unchanged through `NotificationResponse` and `FcmService.sendToUser`.

**Sub-project of:** a larger notification-quality request (template engine, push format, richer Notification Center content, deep-link/click handling). This spec covers only the template engine (foundation); the other three are separate specs.

---

## Problem

`ReminderScheduler.fireReminder()` builds notification text inline:

```java
String title = "Nhắc nhở: " + event.getTitle();
String body  = buildBody(event); // "Sự kiện '%s' diễn ra vào ngày %s"
```

Every category (sinh nhật, kỷ niệm, hóa đơn, ...) and every reminder timing (1 giờ trước, 3 ngày trước, đúng giờ, ...) gets the exact same generic wording. This is harder to read at a glance in the notification tray, doesn't reflect the category's icon/identity the rest of the app already shows, and has no way to convey urgency (a reminder firing 7 days out reads identically to one firing in 30 minutes).

## Key reconciliation with the original request

The request that motivated this spec assumed an `EventType` enum (`GENERAL`/`BIRTHDAY`/`ANNIVERSARY`/`MEETING`/`REMINDER`) and two fixed timing buckets (`ONE_DAY_BEFORE`/`ONE_HOUR_BEFORE`). Neither matches the current codebase:

- **Category is DB data, not an enum.** `Event.category` is a `EventCategory` entity (`event_categories` table) — 7 system rows (`SINH_NHAT`, `KY_NIEM`, `LE`, `NHA_O`, `HOA_DON`, `MUA_SAM`, `KHAC`), each with its own `displayName`/`icon`/`colorHex`, plus future user-created categories (`is_system=0`, schema already supports it, feature not yet built).
- **Reminder timing is a free integer, not two fixed buckets.** `EventReminder` has `remindDaysBefore`/`remindHoursBefore`/`remindMinutesBefore` — any of days=1/3/7, hours=1/3, the mobile UI's `hoursBefore=0` convention for "30 phút trước" (see `event.dart`'s `ReminderModel.displayText`), or no offset at all ("Đúng giờ").

Confirmed with the requester: template selection keys on `EventCategory.code` (with a generic fallback for custom categories) and on a timing **bucket** (day-scale vs. hour/minute-scale) with the actual number interpolated into the text, not a fixed 2-value enum.

## Approach: derive wording from existing category data, don't hand-write it per category

Rather than hand-authoring a title string for all 7 categories (and needing to guess one for every future custom category), the **title is generated from data the category already carries**:

```
title = "{emoji(category.icon)} {category.displayName} {sắp tới|sắp đến}"
```

- `sắp tới` when the reminder is day-scale (still relatively far out), `sắp đến` when it's hour/minute-scale or exact (imminent).
- `emoji(icon)` maps the category's existing Material icon name (`cake`, `favorite`, ...) to an emoji, with a generic 🔔 fallback for any icon this mapping doesn't recognize — which is exactly what a future custom category (arbitrary icon choice) needs, with no extra fallback logic required.

This reproduces the two fully-specified examples from the original request exactly: `SINH_NHAT` → displayName "Sinh nhật" → `"🎂 Sinh nhật sắp tới"` / `"🎂 Sinh nhật sắp đến"`; `KY_NIEM` → displayName "Kỷ niệm" → `"💝 Kỷ niệm sắp tới"` / `"💝 Kỷ niệm sắp đến"`.

**Body** needs exactly one category-specific rule (birthdays name the person) and one shared timing rule:

```
subject = category.code == "SINH_NHAT"
    ? (event.relative != null ? "Sinh nhật " + relative.getName() : event.getTitle())
    : event.getTitle()
body = subject + " · " + timingPhrase(reminder)
```

The birthday fallback uses `event.getTitle()` **without** prepending "Sinh nhật " again — `RelativeService` auto-creates birthday events titled `"Sinh nhật " + displayName` already (`RelativeService.java:271`), so prepending would double up to "Sinh nhật Sinh nhật Mẹ" for the auto-created case. A manually-created `SINH_NHAT` event with no linked relative also just uses whatever title the user gave it.

### Why not alternatives considered

- **Hand-write a title/body pair per category (14 strings for 7 categories × 2 tiers):** inconsistent wording risk across categories nobody thinks about often (`NHA_O`, `HOA_DON`, `MUA_SAM`), and gives custom categories nothing — they'd need their own fallback anyway, duplicating the same "generic 5" wording the derived approach produces automatically. Rejected — confirmed with requester.
- **Store templates in the DB (like `EventCategory` itself):** no admin UI exists or was requested to edit them, and every other user-facing string in this codebase is hardcoded Java/Dart with comments, not DB-driven. Adding a DB-editable template table is unused flexibility. Rejected.
- **A `TimingBucket` enum with fixed values (`ONE_DAY_BEFORE`, `ONE_HOUR_BEFORE`, ...):** doesn't cover arbitrary `remindDaysBefore`/`remindHoursBefore` values a user can actually pick (`event_form_screen.dart` offers 30 phút/1/3 giờ/1/3/7 ngày, and the number is free-form in the data model even beyond those chip presets). Rejected in favor of bucketing by *unit* (days vs. hours vs. minutes vs. exact) with the number interpolated into the phrase.

---

## Timing phrase rules

`remindDaysBefore`/`remindHoursBefore`/`remindMinutesBefore` are checked in the same priority order `EventReminder.computeTriggerTime` already uses (days, then hours, then minutes), so the phrase always matches the actual field that determined *when* this reminder fires:

| Condition | Phrase | "Day-scale" for title tier? |
|---|---|---|
| `remindDaysBefore != null && > 0`, value `== 1` | `"Ngày mai"` | Yes |
| `remindDaysBefore != null && > 0`, value `> 1` | `"Còn {n} ngày"` | Yes |
| `remindHoursBefore != null && == 0` (mobile's "30 phút trước" convention) | `"Sau 30 phút"` | No |
| `remindHoursBefore != null && > 0` | `"Sau {n} giờ"` | No |
| `remindMinutesBefore != null && > 0` (not currently sent by mobile, entity supports it) | `"Sau {n} phút"` | No |
| none of the above ("Đúng giờ") | `"Hôm nay"` | No |

---

## `NotificationTemplateService`

New file `nino-api/src/main/java/com/app/nino/service/NotificationTemplateService.java`:

```java
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
```

## `ReminderScheduler` change

`fireReminder()` (`ReminderScheduler.java:163-207`) replaces its inline `title`/`buildBody(event)` lines with:

```java
NotificationTemplateService.NotificationContent content =
    templateService.build(event, reminder);
String title = content.title();
String body = content.body();
```

`buildBody(Event event)` (the old `String.format("Sự kiện '%s' diễn ra vào ngày %s", ...)` method) is deleted — nothing else calls it. `NotificationTemplateService` is injected via the existing `@RequiredArgsConstructor` (add `private final NotificationTemplateService templateService;`).

No other call site changes: `Notification` entity, `NotificationResponse`, `FcmService.sendToUser`'s `data` map (`eventId`/`type`) — all unchanged, since title/body still flow through exactly the same fields as before.

---

## Worked examples

| Category | Reminder | Title | Body |
|---|---|---|---|
| SINH_NHAT (relative "Mẹ") | 1 ngày trước | `🎂 Sinh nhật sắp tới` | `Sinh nhật Mẹ · Ngày mai` |
| SINH_NHAT (relative "Mẹ") | 1 giờ trước | `🎂 Sinh nhật sắp đến` | `Sinh nhật Mẹ · Sau 1 giờ` |
| SINH_NHAT (no relative, title "Sinh nhật của tôi") | 3 ngày trước | `🎂 Sinh nhật sắp tới` | `Sinh nhật của tôi · Còn 3 ngày` |
| KY_NIEM (title "Kỷ niệm ngày cưới") | 7 ngày trước | `💝 Kỷ niệm sắp tới` | `Kỷ niệm ngày cưới · Còn 7 ngày` |
| HOA_DON (title "Đóng tiền điện") | 30 phút trước | `⚡ Hóa đơn sắp đến` | `Đóng tiền điện · Sau 30 phút` |
| KHAC (title "Họp nhóm") | Đúng giờ | `🔔 Khác sắp đến` | `Họp nhóm · Hôm nay` |
| Custom category "Du lịch" (icon `"flight"`, unrecognized) | 1 ngày trước | `🔔 Du lịch sắp tới` | `Đi Đà Lạt · Ngày mai` |

## Testing

- **`NotificationTemplateServiceTest`** (new): title derivation for all 7 system categories (icon→emoji mapping correct, "sắp tới" vs "sắp đến" tier correct for each timing shape); an unrecognized icon falls back to 🔔; `SINH_NHAT` with a linked relative uses the relative's name; `SINH_NHAT` without a relative falls back to the event title with no double "Sinh nhật " prefix; every branch of the timing-phrase table (days==1, days>1, hours==0, hours>0, minutes>0, none/"Đúng giờ").
- **`ReminderSchedulerTest`**: existing tests that assert on `Notification.title`/`.body` content (if any do — check at plan time) update to match the new format; tests that only assert *whether* a notification fires (most of the suite) are unaffected since this change doesn't touch firing logic, only text content.
- **No migration, no new endpoint, no mobile change** — verified by `./mvnw -q test` (full suite) staying green.

## Out of scope

- Push notification format standardization (separate spec — but note this template's output already produces short "{icon} {category} {tier}" titles and "{subject} · {timing}" bodies, which happens to already satisfy the "[ICON] [EVENT/PERSON] · [TIME]" push-brevity goal from the original request; the push spec should confirm this rather than re-deriving it).
- Richer Notification Center content (icon as a separate field, event date/time, person, `createdAt` — separate spec, layers on top of `Notification`/`NotificationResponse` without needing this spec's template logic to change).
- Deep-link/click handling and the "event deleted" graceful-fallback UI (separate spec, mobile-side, independent of wording).
- User-created custom categories themselves (not yet built — this spec only ensures the template engine won't need code changes when that feature ships, via the icon-emoji fallback and displayName-derived title).
