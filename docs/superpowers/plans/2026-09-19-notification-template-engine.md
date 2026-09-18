# Notification Template Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `ReminderScheduler`'s hardcoded notification title/body with a `NotificationTemplateService` that derives wording from `EventCategory`'s existing `displayName`/`icon` data and the reminder's actual timing offset, so notification text is consistent across all 7 system categories and automatically extends to future custom categories.

**Architecture:** New standalone `NotificationTemplateService` (no dependencies, pure logic) produces `(title, body)` from an `Event` + `EventReminder`. `ReminderScheduler.fireReminder()` calls it instead of building strings inline. No DB, entity, or API changes — `Notification`/`NotificationResponse`/`FcmService` are untouched, since title/body still flow through exactly the same fields.

**Tech Stack:** Spring Boot 3.3, JUnit 5 + Mockito (`nino-api`).

**Spec:** `docs/superpowers/specs/2026-09-19-notification-template-engine-design.md`

## Global Constraints

- Title is always `"{emoji} {category.displayName} {sắp tới|sắp đến}"` — never hand-write per-category wording; the emoji and tier word are the only variable parts, both derived (icon→emoji map, days-scale→"sắp tới" else "sắp đến").
- Body's subject is `event.getTitle()` for every category except `SINH_NHAT`, which uses `"Sinh nhật " + relative.getName()` when `event.getRelative() != null`, else falls back to `event.getTitle()` **without** re-prepending "Sinh nhật " (the auto-created relative-birthday event's title is already `"Sinh nhật " + displayName` — see `RelativeService.java:271`).
- Timing phrase priority order matches `EventReminder.computeTriggerTime`'s own priority (days, then hours, then minutes): `remindDaysBefore` checked first, then `remindHoursBefore` (where `== 0` is mobile's "30 phút trước" convention), then `remindMinutesBefore`, else "Đúng giờ" → `"Hôm nay"`.
- Run `./mvnw -q test` (repo root `nino-api`) after every task and confirm all green before moving on.

---

## File Structure

| File | Responsibility |
|---|---|
| `src/main/java/com/app/nino/service/NotificationTemplateService.java` | Create: title/body derivation |
| `src/test/java/com/app/nino/service/NotificationTemplateServiceTest.java` | Create: tests for every branch |
| `src/main/java/com/app/nino/scheduler/ReminderScheduler.java` | Modify: `fireReminder()` calls the new service; `buildBody()` deleted |
| `src/test/java/com/app/nino/scheduler/ReminderSchedulerTest.java` | Modify: fixture needs a real `NotificationTemplateService` + a category on test events |

---

## Task 1: `NotificationTemplateService`

**Files:**
- Create: `src/main/java/com/app/nino/service/NotificationTemplateService.java`
- Test: `src/test/java/com/app/nino/service/NotificationTemplateServiceTest.java`

**Interfaces:**
- Produces: `NotificationTemplateService.NotificationContent` (record: `title(): String`, `body(): String`), `NotificationTemplateService.build(Event event, EventReminder reminder): NotificationContent`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/app/nino/service/NotificationTemplateServiceTest.java`:

```java
package com.app.nino.service;

import com.app.nino.model.entity.Event;
import com.app.nino.model.entity.EventCategory;
import com.app.nino.model.entity.EventReminder;
import com.app.nino.model.entity.Relative;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NotificationTemplateServiceTest {

    private final NotificationTemplateService service = new NotificationTemplateService();

    private EventCategory category(String code, String displayName, String icon) {
        return EventCategory.builder().code(code).displayName(displayName).icon(icon).build();
    }

    private Event.EventBuilder baseEvent(EventCategory category) {
        return Event.builder().id(1L).title("Họp nhóm").category(category);
    }

    private EventReminder.EventReminderBuilder baseReminder() {
        return EventReminder.builder().id(1L);
    }

    // ── Title: icon->emoji + sắp tới/sắp đến theo timing ────────────────────

    @Test
    void build_daysBucket_titleUsesSapToi() {
        Event event = baseEvent(category("KHAC", "Khác", "more_horiz")).build();
        EventReminder reminder = baseReminder().remindDaysBefore(3).build();

        var content = service.build(event, reminder);

        assertEquals("🔔 Khác sắp tới", content.title());
    }

    @Test
    void build_hoursBucket_titleUsesSapDen() {
        Event event = baseEvent(category("KHAC", "Khác", "more_horiz")).build();
        EventReminder reminder = baseReminder().remindHoursBefore(1).build();

        var content = service.build(event, reminder);

        assertEquals("🔔 Khác sắp đến", content.title());
    }

    @Test
    void build_exactBucket_titleUsesSapDen() {
        Event event = baseEvent(category("KHAC", "Khác", "more_horiz")).build();
        EventReminder reminder = baseReminder().build();

        var content = service.build(event, reminder);

        assertEquals("🔔 Khác sắp đến", content.title());
    }

    @Test
    void build_sinhNhat_mapsCakeIcon() {
        Event event = baseEvent(category("SINH_NHAT", "Sinh nhật", "cake")).build();
        EventReminder reminder = baseReminder().remindDaysBefore(1).build();

        var content = service.build(event, reminder);

        assertEquals("🎂 Sinh nhật sắp tới", content.title());
    }

    @Test
    void build_kyNiem_mapsFavoriteIcon() {
        Event event = baseEvent(category("KY_NIEM", "Kỷ niệm", "favorite")).build();
        EventReminder reminder = baseReminder().remindDaysBefore(1).build();

        var content = service.build(event, reminder);

        assertEquals("💝 Kỷ niệm sắp tới", content.title());
    }

    @Test
    void build_le_mapsCardGiftcardIcon() {
        Event event = baseEvent(category("LE", "Lễ/Tết", "card_giftcard")).build();
        var content = service.build(event, baseReminder().remindDaysBefore(1).build());
        assertEquals("🎁 Lễ/Tết sắp tới", content.title());
    }

    @Test
    void build_nhaO_mapsHomeIcon() {
        Event event = baseEvent(category("NHA_O", "Nhà ở", "home")).build();
        var content = service.build(event, baseReminder().remindDaysBefore(1).build());
        assertEquals("🏠 Nhà ở sắp tới", content.title());
    }

    @Test
    void build_hoaDon_mapsBoltIcon() {
        Event event = baseEvent(category("HOA_DON", "Hóa đơn", "bolt")).build();
        var content = service.build(event, baseReminder().remindDaysBefore(1).build());
        assertEquals("⚡ Hóa đơn sắp tới", content.title());
    }

    @Test
    void build_muaSam_mapsShoppingBagIcon() {
        Event event = baseEvent(category("MUA_SAM", "Mua sắm", "shopping_bag")).build();
        var content = service.build(event, baseReminder().remindDaysBefore(1).build());
        assertEquals("🛍️ Mua sắm sắp tới", content.title());
    }

    @Test
    void build_unrecognizedIcon_fallsBackToBellEmoji() {
        // Danh mục tuỳ tạo trong tương lai có thể chọn icon bất kỳ.
        Event event = baseEvent(category("CUSTOM_1_123", "Du lịch", "flight")).build();
        var content = service.build(event, baseReminder().remindDaysBefore(1).build());
        assertEquals("🔔 Du lịch sắp tới", content.title());
    }

    // ── Body: subject theo category + timing phrase ─────────────────────────

    @Test
    void build_nonBirthdayCategory_bodyUsesEventTitle() {
        Event event = baseEvent(category("KHAC", "Khác", "more_horiz")).build();
        var content = service.build(event, baseReminder().remindDaysBefore(1).build());
        assertEquals("Họp nhóm · Ngày mai", content.body());
    }

    @Test
    void build_birthdayWithRelative_bodyUsesRelativeName() {
        Relative relative = Relative.builder().id(5L).name("Mẹ").build();
        Event event = Event.builder().id(1L).title("Sinh nhật Mẹ")
            .category(category("SINH_NHAT", "Sinh nhật", "cake")).relative(relative).build();

        var content = service.build(event, baseReminder().remindDaysBefore(1).build());

        assertEquals("Sinh nhật Mẹ · Ngày mai", content.body());
    }

    @Test
    void build_birthdayWithoutRelative_bodyUsesEventTitleWithoutDoublingPrefix() {
        // event.getTitle() đã là "Sinh nhật của tôi" — KHÔNG được cộng thêm
        // "Sinh nhật " lần nữa.
        Event event = baseEvent(category("SINH_NHAT", "Sinh nhật", "cake"))
            .title("Sinh nhật của tôi").build();

        var content = service.build(event, baseReminder().remindDaysBefore(3).build());

        assertEquals("Sinh nhật của tôi · Còn 3 ngày", content.body());
    }

    // ── Timing phrase: từng nhánh ─────────────────────────────────────────

    @Test
    void timingPhrase_oneDayBefore_returnsNgayMai() {
        Event event = baseEvent(category("KHAC", "Khác", "more_horiz")).build();
        var content = service.build(event, baseReminder().remindDaysBefore(1).build());
        assertEquals("Họp nhóm · Ngày mai", content.body());
    }

    @Test
    void timingPhrase_multipleDaysBefore_returnsConNNgay() {
        Event event = baseEvent(category("KHAC", "Khác", "more_horiz")).build();
        var content = service.build(event, baseReminder().remindDaysBefore(7).build());
        assertEquals("Họp nhóm · Còn 7 ngày", content.body());
    }

    @Test
    void timingPhrase_hoursBeforeZero_returnsSau30Phut() {
        // Quy ước mobile: hoursBefore=0 nghĩa là "30 phút trước".
        Event event = baseEvent(category("KHAC", "Khác", "more_horiz")).build();
        var content = service.build(event, baseReminder().remindHoursBefore(0).build());
        assertEquals("Họp nhóm · Sau 30 phút", content.body());
    }

    @Test
    void timingPhrase_hoursBeforePositive_returnsSauNGio() {
        Event event = baseEvent(category("KHAC", "Khác", "more_horiz")).build();
        var content = service.build(event, baseReminder().remindHoursBefore(3).build());
        assertEquals("Họp nhóm · Sau 3 giờ", content.body());
    }

    @Test
    void timingPhrase_minutesBeforePositive_returnsSauNPhut() {
        Event event = baseEvent(category("KHAC", "Khác", "more_horiz")).build();
        var content = service.build(event, baseReminder().remindMinutesBefore(15).build());
        assertEquals("Họp nhóm · Sau 15 phút", content.body());
    }

    @Test
    void timingPhrase_noOffsets_returnsHomNay() {
        Event event = baseEvent(category("KHAC", "Khác", "more_horiz")).build();
        var content = service.build(event, baseReminder().build());
        assertEquals("Họp nhóm · Hôm nay", content.body());
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q test -Dtest=NotificationTemplateServiceTest`
Expected: FAIL to compile — `NotificationTemplateService` doesn't exist yet.

- [ ] **Step 3: Implement the service**

Create `src/main/java/com/app/nino/service/NotificationTemplateService.java`:

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

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -q test -Dtest=NotificationTemplateServiceTest`
Expected: PASS (all 18 tests).

- [ ] **Step 5: Run the full suite**

Run: `./mvnw -q test`
Expected: all green (this class isn't wired into anything yet, so nothing else can be affected).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/app/nino/service/NotificationTemplateService.java src/test/java/com/app/nino/service/NotificationTemplateServiceTest.java
git commit -m "$(cat <<'EOF'
feat: add NotificationTemplateService for category+timing notification text

Derives title from EventCategory's existing displayName/icon (no
per-category hardcoding, so future custom categories work without
code changes) and body from event title + a timing phrase bucketed by
days/hours/minutes with the actual offset interpolated. Not wired
into ReminderScheduler yet (next commit).

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Wire into `ReminderScheduler`

**Files:**
- Modify: `src/main/java/com/app/nino/scheduler/ReminderScheduler.java`
- Modify: `src/test/java/com/app/nino/scheduler/ReminderSchedulerTest.java`

**Interfaces:**
- Consumes: `NotificationTemplateService.build(Event, EventReminder): NotificationContent` (Task 1)

No test-first step for the scheduler wiring itself (it's a mechanical replacement of two lines with one call — the actual template logic is already fully tested in Task 1). The test-file change here is a required fixture update (adding a category to test events, since `NotificationTemplateService.build` calls `event.getCategory()` and the existing `eventOn()` helper builds events with no category set, which would NPE the moment `fireReminder()` runs after this wiring) — apply it, then verify green.

- [ ] **Step 1: Wire the service into `fireReminder()`**

In `src/main/java/com/app/nino/scheduler/ReminderScheduler.java`, add the field next to the other `private final` fields:

```java
    private final NotificationTemplateService templateService;
```

Add the import:

```java
import com.app.nino.service.NotificationTemplateService;
```

Change `fireReminder()` from:

```java
    private void fireReminder(EventReminder reminder, Event event, LocalDateTime now) {
        String title = "Nhắc nhở: " + event.getTitle();
        String body  = buildBody(event);
```

to:

```java
    private void fireReminder(EventReminder reminder, Event event, LocalDateTime now) {
        NotificationTemplateService.NotificationContent content = templateService.build(event, reminder);
        String title = content.title();
        String body  = content.body();
```

Delete the now-unused `buildBody` method at the end of the class:

```java
    private String buildBody(Event event) {
        return String.format("Sự kiện '%s' diễn ra vào ngày %s",
            event.getTitle(), event.getEventDate());
    }
```

- [ ] **Step 2: Run the scheduler test to see it fail on the fixture gap**

Run: `./mvnw -q test -Dtest=ReminderSchedulerTest`
Expected: FAIL — `NullPointerException` from `event.getCategory()` being null in any test that reaches `fireReminder()` (e.g. `checkReminders_dueReminder_firesAndSavesNotification`-style tests — check the actual test names in the file), and a compile error if `@InjectMocks` can't resolve the new `NotificationTemplateService` constructor parameter (Mockito leaves unmatched constructor params `null` rather than failing to compile, so expect the NPE at runtime, not a compile failure).

- [ ] **Step 3: Fix the test fixture**

In `src/test/java/com/app/nino/scheduler/ReminderSchedulerTest.java`, add the import:

```java
import com.app.nino.model.entity.EventCategory;
import com.app.nino.service.NotificationTemplateService;
import org.mockito.Spy;
```

Add a `@Spy` field next to the other `@Mock` fields — a real `NotificationTemplateService` instance (it has no dependencies, so this exercises the actual Task 1 logic rather than needing to stub every possible title/body):

```java
    @Spy
    private NotificationTemplateService templateService = new NotificationTemplateService();
```

Update the `eventOn()` helper to set a category (any valid one — tests in this file don't assert on notification text content, only on *whether* one fires, so the exact category choice doesn't matter):

```java
    private Event eventOn(LocalDate date, LocalTime time) {
        return Event.builder()
            .id(9L)
            .user(user())
            .title("Họp nhóm")
            .category(EventCategory.builder().code("KHAC").displayName("Khác").icon("more_horiz").build())
            .eventDate(date)
            .eventTime(time)
            .isActive(true)
            .build();
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -q test -Dtest=ReminderSchedulerTest`
Expected: PASS.

- [ ] **Step 5: Run the full suite and commit**

Run: `./mvnw -q test`
Expected: all green.

```bash
git add src/main/java/com/app/nino/scheduler/ReminderScheduler.java src/test/java/com/app/nino/scheduler/ReminderSchedulerTest.java
git commit -m "$(cat <<'EOF'
feat: use NotificationTemplateService in ReminderScheduler

fireReminder() now builds title/body via the template engine instead
of the old hardcoded "Nhắc nhở: " + title / buildBody() strings.
Notification content now varies by event category and actual reminder
timing instead of being identical for every reminder.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review

**Spec coverage:**
- `NotificationTemplateService` (title derivation, body derivation, timing phrase table) → Task 1. Covered.
- Wiring into `ReminderScheduler`, deleting `buildBody` → Task 2. Covered.
- Worked examples table from the spec (7 categories + custom-category fallback) → all reproduced as Task 1 test cases with matching expected strings.
- Out-of-scope items (push format spec, Notification Center enrichment, deep-link handling, custom categories UI) — no tasks added, as intended.

**Placeholder scan:** every step has literal, complete code; no TBD/TODO.

**Type consistency:** `NotificationTemplateService.NotificationContent` (record, Task 1) — `.title()`/`.body()` accessors match their use in Task 2's `fireReminder()`. `NotificationTemplateService.build(Event, EventReminder)` signature (Task 1) matches its call site in Task 2 exactly. Test fixture's `EventCategory.builder()...build()` (Task 2) matches the entity's actual `@Builder` fields (`code`, `displayName`, `icon`) used throughout Task 1's tests too — consistent construction pattern.

**Scope check:** 2 tasks, both independently compilable-and-green, both single-repo (`nino-api` only, no mobile changes). Small and tightly-scoped relative to prior plans in this project (`2026-09-18-pin-reset-otp.md` had 5 cross-repo tasks) — appropriate, since this sub-project is one internal service plus one call-site swap, with the harder design decisions already resolved in the spec. No further decomposition needed.
