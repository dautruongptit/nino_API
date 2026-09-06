package com.app.nino.service;

import com.app.nino.exception.BadRequestException;
import com.app.nino.model.dto.request.CreateRelativeRequest;
import com.app.nino.model.dto.response.RelativeDetailResponse;
import com.app.nino.model.entity.Event;
import com.app.nino.model.entity.EventCategory;
import com.app.nino.model.entity.Relative;
import com.app.nino.model.entity.User;
import com.app.nino.repository.EventCategoryRepository;
import com.app.nino.repository.EventRepository;
import com.app.nino.repository.RelativeRepository;
import com.app.nino.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Ngày sinh tách 3 phần (birthMonth/birthDay/birthYear — xem
 * Relative.java) và Event "Sinh nhật" liên kết phải luôn khớp nhau. Không
 * còn giá trị năm đại diện nào (birthYear null = thực sự không rõ năm).
 */
@ExtendWith(MockitoExtension.class)
class RelativeServiceTest {

    @Mock private RelativeRepository relativeRepo;
    @Mock private UserRepository userRepo;
    @Mock private EventRepository eventRepo;
    @Mock private EventCategoryRepository categoryRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private RelativeService service;

    private final EventCategory birthdayCategory = EventCategory.builder()
        .id(9L).code("SINH_NHAT").displayName("Sinh nhật").icon("cake").colorHex("#FF6B6B").build();

    @BeforeEach
    void setUp() {
        lenient().when(userRepo.findById(1L)).thenReturn(Optional.of(User.builder().id(1L).build()));
        lenient().when(relativeRepo.save(any(Relative.class))).thenAnswer(inv -> {
            Relative r = inv.getArgument(0);
            if (r.getId() == null) r.setId(50L);
            return r;
        });
    }

    private CreateRelativeRequest baseRequest(Integer month, Integer day, Integer year) {
        CreateRelativeRequest req = new CreateRelativeRequest();
        req.setName("Mẹ");
        req.setGroupType("ME");
        req.setBirthMonth(month);
        req.setBirthDay(day);
        req.setBirthYear(year);
        return req;
    }

    private CreateRelativeRequest baseRequestNoBirthday() {
        return baseRequest(null, null, null);
    }

    // ── CREATE ──────────────────────────────────────────────────────────

    @Test
    void create_withKnownBirthYear_createsLinkedBirthdayEvent() {
        // event_date của Event Sinh nhật (recurrence YEARLY) là NGÀY LẦN TỚI
        // sắp diễn ra (năm nay/năm sau) — không phải gán thẳng birthYear —
        // vì backend không có scheduler roll-forward năm cho YEARLY thường
        // (khác LunarRecurrenceScheduler dành cho âm lịch), và
        // EventService.toResponse tính daysUntil = hiệu số ngày thô.
        when(categoryRepo.findByCode("SINH_NHAT")).thenReturn(Optional.of(birthdayCategory));

        service.create(1L, baseRequest(5, 20, 1970));

        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventRepo).save(captor.capture());
        Event saved = captor.getValue();
        assertEquals("SINH_NHAT", saved.getCategory().getCode());
        assertEquals(RelativeService.nextBirthdayOccurrence(5, 20, LocalDate.now()), saved.getEventDate());
        assertEquals(50L, saved.getRelative().getId());
        assertEquals(Event.RecurrenceType.YEARLY, saved.getRecurrenceType());
        verify(relativeRepo).incrementEventCount(50L);
        verify(userRepo).incrementEventCount(1L);
    }

    @Test
    void create_withUnknownBirthYear_stillCreatesLinkedBirthdayEvent() {
        // birthYear null (không rõ năm) — vẫn phải tạo Event Sinh nhật bình
        // thường, vì chỉ tháng/ngày mới cần cho việc đếm ngược.
        when(categoryRepo.findByCode("SINH_NHAT")).thenReturn(Optional.of(birthdayCategory));

        service.create(1L, baseRequest(10, 10, null));

        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventRepo).save(captor.capture());
        assertEquals(RelativeService.nextBirthdayOccurrence(10, 10, LocalDate.now()), captor.getValue().getEventDate());
    }

    @Test
    void create_withoutBirthday_doesNotCreateBirthdayEvent() {
        service.create(1L, baseRequestNoBirthday());

        verify(eventRepo, never()).save(any());
        verifyNoInteractions(categoryRepo);
    }

    @Test
    void create_withBirthDayButNoMonth_throwsBadRequest() {
        assertThrows(BadRequestException.class, () -> service.create(1L, baseRequest(null, 20, null)));
    }

    @Test
    void create_withBirthYearButNoMonthDay_throwsBadRequest() {
        assertThrows(BadRequestException.class, () -> service.create(1L, baseRequest(null, null, 1970)));
    }

    // ── UPDATE ──────────────────────────────────────────────────────────

    @Test
    void update_addingBirthdayWhenNoneExisted_createsBirthdayEvent() {
        Relative existing = Relative.builder().id(50L).user(User.builder().id(1L).build())
            .name("Mẹ").groupType(Relative.GroupType.ME).build();
        when(relativeRepo.findByIdAndUserId(50L, 1L)).thenReturn(Optional.of(existing));
        when(eventRepo.findFirstByRelativeIdAndCategory_CodeAndIsActiveTrue(50L, "SINH_NHAT"))
            .thenReturn(Optional.empty());
        when(categoryRepo.findByCode("SINH_NHAT")).thenReturn(Optional.of(birthdayCategory));

        service.update(50L, 1L, baseRequest(5, 20, 1970));

        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventRepo).save(captor.capture());
        assertEquals(RelativeService.nextBirthdayOccurrence(5, 20, LocalDate.now()), captor.getValue().getEventDate());
    }

    @Test
    void update_changingBirthMonthDay_updatesExistingBirthdayEventInPlace() {
        Relative existing = Relative.builder().id(50L).user(User.builder().id(1L).build())
            .name("Mẹ").groupType(Relative.GroupType.ME)
            .birthMonth(5).birthDay(20).birthYear(1970).build();
        // Event đang đúng chuẩn theo ngày sinh CŨ (ngày lần tới, không phải năm sinh thật).
        Event linkedEvent = Event.builder().id(77L).category(birthdayCategory)
            .eventDate(RelativeService.nextBirthdayOccurrence(5, 20, LocalDate.now())).isActive(true).build();
        when(relativeRepo.findByIdAndUserId(50L, 1L)).thenReturn(Optional.of(existing));
        when(eventRepo.findFirstByRelativeIdAndCategory_CodeAndIsActiveTrue(50L, "SINH_NHAT"))
            .thenReturn(Optional.of(linkedEvent));

        service.update(50L, 1L, baseRequest(6, 21, 1970));

        assertEquals(RelativeService.nextBirthdayOccurrence(6, 21, LocalDate.now()), linkedEvent.getEventDate());
        verify(eventRepo).save(linkedEvent);
        // Không tạo Event mới — chỉ có 1 lần save cho đúng event đã có sẵn.
        verify(eventRepo, times(1)).save(any());
        verifyNoInteractions(categoryRepo);
    }

    @Test
    void update_birthMonthDayUnchanged_doesNotTouchLinkedBirthdayEvent() {
        Relative existing = Relative.builder().id(50L).user(User.builder().id(1L).build())
            .name("Mẹ").groupType(Relative.GroupType.ME)
            .birthMonth(5).birthDay(20).birthYear(1970).build();
        // Event đã ở đúng ngày lần tới ứng với birthday hiện tại -> không phải sửa lại.
        Event linkedEvent = Event.builder().id(77L).category(birthdayCategory)
            .eventDate(RelativeService.nextBirthdayOccurrence(5, 20, LocalDate.now())).isActive(true).build();
        when(relativeRepo.findByIdAndUserId(50L, 1L)).thenReturn(Optional.of(existing));
        when(eventRepo.findFirstByRelativeIdAndCategory_CodeAndIsActiveTrue(50L, "SINH_NHAT"))
            .thenReturn(Optional.of(linkedEvent));

        service.update(50L, 1L, baseRequest(5, 20, 1970));

        verify(eventRepo, never()).save(any());
    }

    @Test
    void update_onlyBirthYearChanges_doesNotTouchLinkedBirthdayEvent() {
        // Sửa/bổ sung birthYear (VD từ không rõ -> biết năm) không đổi ngày
        // lần tới -> Event Sinh nhật không cần đụng vào.
        Relative existing = Relative.builder().id(50L).user(User.builder().id(1L).build())
            .name("Mẹ").groupType(Relative.GroupType.ME)
            .birthMonth(5).birthDay(20).birthYear(null).build();
        Event linkedEvent = Event.builder().id(77L).category(birthdayCategory)
            .eventDate(RelativeService.nextBirthdayOccurrence(5, 20, LocalDate.now())).isActive(true).build();
        when(relativeRepo.findByIdAndUserId(50L, 1L)).thenReturn(Optional.of(existing));
        when(eventRepo.findFirstByRelativeIdAndCategory_CodeAndIsActiveTrue(50L, "SINH_NHAT"))
            .thenReturn(Optional.of(linkedEvent));

        service.update(50L, 1L, baseRequest(5, 20, 1970));

        verify(eventRepo, never()).save(any());
    }

    @Test
    void update_clearingBirthday_deactivatesLinkedBirthdayEvent() {
        Relative existing = Relative.builder().id(50L).user(User.builder().id(1L).build())
            .name("Mẹ").groupType(Relative.GroupType.ME)
            .birthMonth(5).birthDay(20).birthYear(1970).build();
        Event linkedEvent = Event.builder().id(77L).category(birthdayCategory)
            .eventDate(LocalDate.of(2026, 5, 20)).isActive(true).build();
        when(relativeRepo.findByIdAndUserId(50L, 1L)).thenReturn(Optional.of(existing));
        when(eventRepo.findFirstByRelativeIdAndCategory_CodeAndIsActiveTrue(50L, "SINH_NHAT"))
            .thenReturn(Optional.of(linkedEvent));

        service.update(50L, 1L, baseRequestNoBirthday());

        assertFalse(linkedEvent.getIsActive());
        verify(eventRepo).save(linkedEvent);
        verify(userRepo).decrementEventCount(1L);
        verify(relativeRepo).decrementRelativeEventCount(50L);
    }

    // ── SYNC NGƯỢC TỪ EVENT (gọi bởi EventService khi sửa Event Sinh nhật) ──

    @Test
    void syncDateOfBirthFromEvent_keepsBirthYear_onlyAdoptsMonthAndDayFromEvent() {
        // eventDate mang năm "lần tới" (2027), KHÔNG phải năm sinh thật —
        // sửa Event Sinh nhật chỉ đổi tháng/ngày sinh, giữ nguyên birthYear
        // 1970 vốn dùng để tính tuổi (RelativeDetailResponse.age).
        Relative existing = Relative.builder().id(50L).user(User.builder().id(1L).build())
            .name("Mẹ").groupType(Relative.GroupType.ME)
            .birthMonth(5).birthDay(20).birthYear(1970).build();
        when(relativeRepo.findByIdAndUserId(50L, 1L)).thenReturn(Optional.of(existing));

        service.syncDateOfBirthFromEvent(50L, 1L, LocalDate.of(2027, 6, 21));

        assertEquals(6, existing.getBirthMonth());
        assertEquals(21, existing.getBirthDay());
        assertEquals(1970, existing.getBirthYear());
        verify(relativeRepo).save(existing);
    }

    @Test
    void syncDateOfBirthFromEvent_whenMonthDayUnchanged_doesNotSave() {
        // Năm trên Event khác birthYear là chuyện BÌNH THƯỜNG (event luôn
        // mang năm lần tới) -> không được coi là "có thay đổi".
        Relative existing = Relative.builder().id(50L).user(User.builder().id(1L).build())
            .name("Mẹ").groupType(Relative.GroupType.ME)
            .birthMonth(5).birthDay(20).birthYear(1970).build();
        when(relativeRepo.findByIdAndUserId(50L, 1L)).thenReturn(Optional.of(existing));

        service.syncDateOfBirthFromEvent(50L, 1L, LocalDate.of(2026, 5, 20));

        verify(relativeRepo, never()).save(any());
    }

    @Test
    void syncDateOfBirthFromEvent_keepsBirthYearNull_whenUnknown() {
        // Người thân đang KHÔNG rõ năm sinh (birthYear null) — sửa Event chỉ
        // đổi tháng/ngày, birthYear vẫn giữ null (không tự điền năm nào).
        Relative existing = Relative.builder().id(50L).user(User.builder().id(1L).build())
            .name("Mẹ").groupType(Relative.GroupType.ME)
            .birthMonth(5).birthDay(20).birthYear(null).build();
        when(relativeRepo.findByIdAndUserId(50L, 1L)).thenReturn(Optional.of(existing));

        service.syncDateOfBirthFromEvent(50L, 1L, LocalDate.of(2027, 6, 21));

        assertEquals(6, existing.getBirthMonth());
        assertEquals(21, existing.getBirthDay());
        assertNull(existing.getBirthYear());
        verify(relativeRepo).save(existing);
    }

    // ── KHÔNG RÕ NĂM SINH — birthYear = null ────────────────────────────────

    @Test
    void create_withBirthYear_persistsIt() {
        when(categoryRepo.findByCode("SINH_NHAT")).thenReturn(Optional.of(birthdayCategory));

        service.create(1L, baseRequest(5, 20, 1970));

        ArgumentCaptor<Relative> captor = ArgumentCaptor.forClass(Relative.class);
        verify(relativeRepo, atLeastOnce()).save(captor.capture());
        assertEquals(1970, captor.getValue().getBirthYear());
    }

    @Test
    void create_withoutBirthYear_persistsNull() {
        when(categoryRepo.findByCode("SINH_NHAT")).thenReturn(Optional.of(birthdayCategory));

        service.create(1L, baseRequest(10, 10, null));

        ArgumentCaptor<Relative> captor = ArgumentCaptor.forClass(Relative.class);
        verify(relativeRepo, atLeastOnce()).save(captor.capture());
        assertNull(captor.getValue().getBirthYear());
        assertEquals(10, captor.getValue().getBirthMonth());
        assertEquals(10, captor.getValue().getBirthDay());
    }

    @Test
    void update_clearingBirthYear_persistsNull() {
        Relative existing = Relative.builder().id(50L).user(User.builder().id(1L).build())
            .name("Mẹ").groupType(Relative.GroupType.ME)
            .birthMonth(5).birthDay(20).birthYear(1970).build();
        when(relativeRepo.findByIdAndUserId(50L, 1L)).thenReturn(Optional.of(existing));
        when(eventRepo.findFirstByRelativeIdAndCategory_CodeAndIsActiveTrue(50L, "SINH_NHAT"))
            .thenReturn(Optional.empty());
        when(categoryRepo.findByCode("SINH_NHAT")).thenReturn(Optional.of(birthdayCategory));

        service.update(50L, 1L, baseRequest(5, 20, null));

        assertNull(existing.getBirthYear());
    }

    @Test
    void getDetail_whenYearUnknown_returnsNullAge() {
        Relative existing = Relative.builder().id(50L).user(User.builder().id(1L).build())
            .name("Mẹ").groupType(Relative.GroupType.ME)
            .birthMonth(5).birthDay(20).birthYear(null).build();
        when(relativeRepo.findByIdAndUserId(50L, 1L)).thenReturn(Optional.of(existing));
        when(eventRepo.findByRelativeIdAndIsActiveTrueOrderByEventDateAsc(50L)).thenReturn(List.of());

        RelativeDetailResponse response = service.getDetail(50L, 1L);

        assertNull(response.getAge());
    }

    @Test
    void getDetail_whenYearKnown_returnsRealAge() {
        LocalDate birthday30YearsAgo = LocalDate.now().minusYears(30);
        Relative existing = Relative.builder().id(50L).user(User.builder().id(1L).build())
            .name("Mẹ").groupType(Relative.GroupType.ME)
            .birthMonth(birthday30YearsAgo.getMonthValue())
            .birthDay(birthday30YearsAgo.getDayOfMonth())
            .birthYear(birthday30YearsAgo.getYear()).build();
        when(relativeRepo.findByIdAndUserId(50L, 1L)).thenReturn(Optional.of(existing));
        when(eventRepo.findByRelativeIdAndIsActiveTrueOrderByEventDateAsc(50L)).thenReturn(List.of());

        RelativeDetailResponse response = service.getDetail(50L, 1L);

        assertEquals(30, response.getAge());
    }

    @Test
    void getDetail_withoutBirthday_returnsNullAge() {
        Relative existing = Relative.builder().id(50L).user(User.builder().id(1L).build())
            .name("Mẹ").groupType(Relative.GroupType.ME).build();
        when(relativeRepo.findByIdAndUserId(50L, 1L)).thenReturn(Optional.of(existing));
        when(eventRepo.findByRelativeIdAndIsActiveTrueOrderByEventDateAsc(50L)).thenReturn(List.of());

        RelativeDetailResponse response = service.getDetail(50L, 1L);

        assertNull(response.getAge());
    }

    // ── DELETE — Event Sinh nhật liên kết không được mồ côi ────────────────

    @Test
    void delete_deactivatesLinkedBirthdayEvent() {
        Relative existing = Relative.builder().id(50L).user(User.builder().id(1L).build())
            .name("Mẹ").groupType(Relative.GroupType.ME)
            .birthMonth(5).birthDay(20).birthYear(1970).build();
        Event linkedEvent = Event.builder().id(77L).category(birthdayCategory)
            .eventDate(LocalDate.of(2026, 5, 20)).isActive(true).build();
        when(relativeRepo.findByIdAndUserId(50L, 1L)).thenReturn(Optional.of(existing));
        when(eventRepo.findFirstByRelativeIdAndCategory_CodeAndIsActiveTrue(50L, "SINH_NHAT"))
            .thenReturn(Optional.of(linkedEvent));

        service.delete(50L, 1L);

        assertFalse(linkedEvent.getIsActive());
        verify(eventRepo).save(linkedEvent);
        verify(relativeRepo).delete(existing);
    }

    @Test
    void delete_withoutLinkedBirthdayEvent_doesNotTouchEventRepo() {
        Relative existing = Relative.builder().id(50L).user(User.builder().id(1L).build())
            .name("Mẹ").groupType(Relative.GroupType.ME).build();
        when(relativeRepo.findByIdAndUserId(50L, 1L)).thenReturn(Optional.of(existing));
        when(eventRepo.findFirstByRelativeIdAndCategory_CodeAndIsActiveTrue(50L, "SINH_NHAT"))
            .thenReturn(Optional.empty());

        service.delete(50L, 1L);

        verify(eventRepo, never()).save(any());
        verify(relativeRepo).delete(existing);
    }

    // ── nextBirthdayOccurrence — quy tắc "ngày lần tới" dùng cho eventDate ──

    @Test
    void nextBirthdayOccurrence_beforeThisYearsBirthday_returnsThisYear() {
        LocalDate today = LocalDate.of(2026, 9, 6);

        assertEquals(LocalDate.of(2026, 12, 25), RelativeService.nextBirthdayOccurrence(12, 25, today));
    }

    @Test
    void nextBirthdayOccurrence_afterThisYearsBirthday_rollsToNextYear() {
        LocalDate today = LocalDate.of(2026, 9, 6);

        assertEquals(LocalDate.of(2027, 5, 20), RelativeService.nextBirthdayOccurrence(5, 20, today));
    }

    @Test
    void nextBirthdayOccurrence_todayIsTheBirthday_returnsToday() {
        LocalDate today = LocalDate.of(2026, 9, 6);

        assertEquals(today, RelativeService.nextBirthdayOccurrence(9, 6, today));
    }

    @Test
    void nextBirthdayOccurrence_leapDayBirthdayInNonLeapYear_clampsToFeb28() {
        LocalDate today = LocalDate.of(2026, 9, 6); // 2027 không nhuận

        assertEquals(LocalDate.of(2027, 2, 28), RelativeService.nextBirthdayOccurrence(2, 29, today));
    }
}
