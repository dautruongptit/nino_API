package com.app.nino.service;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Ngày sinh (Relative.dateOfBirth) và Event "Sinh nhật" liên kết phải luôn
 * khớp nhau — xem thiết kế đã duyệt: "2 trường này là 1". Các test dưới đây
 * phủ chiều Relative -> Event (create/update/xoá ngày sinh).
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

    private CreateRelativeRequest baseRequest(LocalDate dob) {
        return baseRequest(dob, null);
    }

    private CreateRelativeRequest baseRequest(LocalDate dob, Boolean dateOfBirthYearKnown) {
        CreateRelativeRequest req = new CreateRelativeRequest();
        req.setName("Mẹ");
        req.setGroupType("ME");
        req.setDateOfBirth(dob);
        req.setDateOfBirthYearKnown(dateOfBirthYearKnown);
        return req;
    }

    // ── CREATE ──────────────────────────────────────────────────────────

    @Test
    void create_withDateOfBirth_createsLinkedBirthdayEvent() {
        // event_date của Event Sinh nhật (recurrence YEARLY) là NGÀY LẦN TỚI
        // sắp diễn ra (năm nay/năm sau) — không phải gán thẳng dateOfBirth —
        // vì backend không có scheduler roll-forward năm cho YEARLY thường
        // (khác LunarRecurrenceScheduler dành cho âm lịch), và
        // EventService.toResponse tính daysUntil = hiệu số ngày thô. Gán
        // thẳng dateOfBirth (năm sinh thật) sẽ khiến Event mới tạo luôn có
        // daysUntil âm khổng lồ -> hiện "ĐÃ QUA" ngay khi vừa tạo.
        LocalDate dob = LocalDate.of(1970, 5, 20);
        when(categoryRepo.findByCode("SINH_NHAT")).thenReturn(Optional.of(birthdayCategory));

        service.create(1L, baseRequest(dob));

        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventRepo).save(captor.capture());
        Event saved = captor.getValue();
        assertEquals("SINH_NHAT", saved.getCategory().getCode());
        assertEquals(RelativeService.nextBirthdayOccurrence(dob, LocalDate.now()), saved.getEventDate());
        assertEquals(50L, saved.getRelative().getId());
        assertEquals(Event.RecurrenceType.YEARLY, saved.getRecurrenceType());
        verify(relativeRepo).incrementEventCount(50L);
        verify(userRepo).incrementEventCount(1L);
    }

    @Test
    void create_withoutDateOfBirth_doesNotCreateBirthdayEvent() {
        service.create(1L, baseRequest(null));

        verify(eventRepo, never()).save(any());
        verifyNoInteractions(categoryRepo);
    }

    // ── UPDATE ──────────────────────────────────────────────────────────

    @Test
    void update_addingDateOfBirthWhenNoneExisted_createsBirthdayEvent() {
        LocalDate dob = LocalDate.of(1970, 5, 20);
        Relative existing = Relative.builder().id(50L).user(User.builder().id(1L).build())
            .name("Mẹ").groupType(Relative.GroupType.ME).dateOfBirth(null).build();
        when(relativeRepo.findByIdAndUserId(50L, 1L)).thenReturn(Optional.of(existing));
        when(eventRepo.findFirstByRelativeIdAndCategory_CodeAndIsActiveTrue(50L, "SINH_NHAT"))
            .thenReturn(Optional.empty());
        when(categoryRepo.findByCode("SINH_NHAT")).thenReturn(Optional.of(birthdayCategory));

        service.update(50L, 1L, baseRequest(dob));

        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventRepo).save(captor.capture());
        assertEquals(RelativeService.nextBirthdayOccurrence(dob, LocalDate.now()), captor.getValue().getEventDate());
    }

    @Test
    void update_changingDateOfBirth_updatesExistingBirthdayEventInPlace() {
        LocalDate oldDob = LocalDate.of(1970, 5, 20);
        LocalDate newDob = LocalDate.of(1970, 6, 21);
        Relative existing = Relative.builder().id(50L).user(User.builder().id(1L).build())
            .name("Mẹ").groupType(Relative.GroupType.ME).dateOfBirth(oldDob).build();
        // Event đang đúng chuẩn theo ngày sinh CŨ (ngày lần tới, không phải năm sinh thật).
        Event linkedEvent = Event.builder().id(77L).category(birthdayCategory)
            .eventDate(RelativeService.nextBirthdayOccurrence(oldDob, LocalDate.now())).isActive(true).build();
        when(relativeRepo.findByIdAndUserId(50L, 1L)).thenReturn(Optional.of(existing));
        when(eventRepo.findFirstByRelativeIdAndCategory_CodeAndIsActiveTrue(50L, "SINH_NHAT"))
            .thenReturn(Optional.of(linkedEvent));

        service.update(50L, 1L, baseRequest(newDob));

        assertEquals(RelativeService.nextBirthdayOccurrence(newDob, LocalDate.now()), linkedEvent.getEventDate());
        verify(eventRepo).save(linkedEvent);
        // Không tạo Event mới — chỉ có 1 lần save cho đúng event đã có sẵn.
        verify(eventRepo, times(1)).save(any());
        verifyNoInteractions(categoryRepo);
    }

    @Test
    void update_dateOfBirthUnchanged_doesNotTouchLinkedBirthdayEvent() {
        LocalDate dob = LocalDate.of(1970, 5, 20);
        Relative existing = Relative.builder().id(50L).user(User.builder().id(1L).build())
            .name("Mẹ").groupType(Relative.GroupType.ME).dateOfBirth(dob).build();
        // Event đã ở đúng ngày lần tới ứng với dob hiện tại -> không phải sửa lại.
        Event linkedEvent = Event.builder().id(77L).category(birthdayCategory)
            .eventDate(RelativeService.nextBirthdayOccurrence(dob, LocalDate.now())).isActive(true).build();
        when(relativeRepo.findByIdAndUserId(50L, 1L)).thenReturn(Optional.of(existing));
        when(eventRepo.findFirstByRelativeIdAndCategory_CodeAndIsActiveTrue(50L, "SINH_NHAT"))
            .thenReturn(Optional.of(linkedEvent));

        service.update(50L, 1L, baseRequest(dob));

        verify(eventRepo, never()).save(any());
    }

    @Test
    void update_clearingDateOfBirth_deactivatesLinkedBirthdayEvent() {
        Relative existing = Relative.builder().id(50L).user(User.builder().id(1L).build())
            .name("Mẹ").groupType(Relative.GroupType.ME).dateOfBirth(LocalDate.of(1970, 5, 20)).build();
        Event linkedEvent = Event.builder().id(77L).category(birthdayCategory)
            .eventDate(LocalDate.of(1970, 5, 20)).isActive(true).build();
        when(relativeRepo.findByIdAndUserId(50L, 1L)).thenReturn(Optional.of(existing));
        when(eventRepo.findFirstByRelativeIdAndCategory_CodeAndIsActiveTrue(50L, "SINH_NHAT"))
            .thenReturn(Optional.of(linkedEvent));

        service.update(50L, 1L, baseRequest(null));

        assertFalse(linkedEvent.getIsActive());
        verify(eventRepo).save(linkedEvent);
        verify(userRepo).decrementEventCount(1L);
        verify(relativeRepo).decrementRelativeEventCount(50L);
    }

    // ── SYNC NGƯỢC TỪ EVENT (gọi bởi EventService khi sửa Event Sinh nhật) ──

    @Test
    void syncDateOfBirthFromEvent_keepsBirthYear_onlyAdoptsMonthAndDayFromEvent() {
        // eventDate mang năm "lần tới" (2027), KHÔNG phải năm sinh thật —
        // sửa Event Sinh nhật chỉ đổi tháng/ngày sinh, giữ nguyên năm sinh
        // 1970 vốn dùng để tính tuổi (RelativeDetailResponse.age).
        Relative existing = Relative.builder().id(50L).user(User.builder().id(1L).build())
            .name("Mẹ").groupType(Relative.GroupType.ME).dateOfBirth(LocalDate.of(1970, 5, 20)).build();
        when(relativeRepo.findByIdAndUserId(50L, 1L)).thenReturn(Optional.of(existing));

        service.syncDateOfBirthFromEvent(50L, 1L, LocalDate.of(2027, 6, 21));

        assertEquals(LocalDate.of(1970, 6, 21), existing.getDateOfBirth());
        verify(relativeRepo).save(existing);
    }

    @Test
    void syncDateOfBirthFromEvent_whenMonthDayUnchanged_doesNotSave() {
        // Năm trên Event khác năm sinh là chuyện BÌNH THƯỜNG (event luôn
        // mang năm lần tới) -> không được coi là "có thay đổi".
        Relative existing = Relative.builder().id(50L).user(User.builder().id(1L).build())
            .name("Mẹ").groupType(Relative.GroupType.ME).dateOfBirth(LocalDate.of(1970, 5, 20)).build();
        when(relativeRepo.findByIdAndUserId(50L, 1L)).thenReturn(Optional.of(existing));

        service.syncDateOfBirthFromEvent(50L, 1L, LocalDate.of(2026, 5, 20));

        verify(relativeRepo, never()).save(any());
    }

    @Test
    void syncDateOfBirthFromEvent_whenNoDateOfBirthYet_adoptsEventDateAsIs() {
        // Trường hợp phòng hờ: relative chưa từng có dateOfBirth (không nên
        // xảy ra nếu đã có Event liên kết, nhưng cứ xử lý an toàn) -> lấy
        // nguyên eventDate làm dateOfBirth thay vì NPE khi đọc year().
        Relative existing = Relative.builder().id(50L).user(User.builder().id(1L).build())
            .name("Mẹ").groupType(Relative.GroupType.ME).dateOfBirth(null).build();
        when(relativeRepo.findByIdAndUserId(50L, 1L)).thenReturn(Optional.of(existing));

        service.syncDateOfBirthFromEvent(50L, 1L, LocalDate.of(2027, 6, 21));

        assertEquals(LocalDate.of(2027, 6, 21), existing.getDateOfBirth());
        verify(relativeRepo).save(existing);
    }

    // ── KHÔNG NHỚ NĂM SINH — dateOfBirthYearKnown ──────────────────────────
    // Người dùng có thể không nhớ chính xác năm sinh của người thân. Field
    // này cho phép dateOfBirth chỉ đáng tin ở phần Tháng/Ngày (năm là giá
    // trị đại diện mobile tự điền, VD 1900) — không ảnh hưởng tới việc tính
    // "sinh nhật lần tới" (nextBirthdayOccurrence chỉ dùng tháng/ngày), chỉ
    // ảnh hưởng tới tuổi hiển thị ở RelativeDetailResponse.

    @Test
    void create_withDateOfBirthYearKnownOmitted_defaultsToTrue() {
        when(categoryRepo.findByCode("SINH_NHAT")).thenReturn(Optional.of(birthdayCategory));

        service.create(1L, baseRequest(LocalDate.of(1900, 5, 20), null));

        ArgumentCaptor<Relative> captor = ArgumentCaptor.forClass(Relative.class);
        verify(relativeRepo, atLeastOnce()).save(captor.capture());
        assertTrue(captor.getValue().getDateOfBirthYearKnown());
    }

    @Test
    void create_withDateOfBirthYearKnownFalse_persistsFalse() {
        when(categoryRepo.findByCode("SINH_NHAT")).thenReturn(Optional.of(birthdayCategory));

        service.create(1L, baseRequest(LocalDate.of(1900, 5, 20), false));

        ArgumentCaptor<Relative> captor = ArgumentCaptor.forClass(Relative.class);
        verify(relativeRepo, atLeastOnce()).save(captor.capture());
        assertFalse(captor.getValue().getDateOfBirthYearKnown());
    }

    @Test
    void update_changingDateOfBirthYearKnownToFalse_persistsFalse() {
        Relative existing = Relative.builder().id(50L).user(User.builder().id(1L).build())
            .name("Mẹ").groupType(Relative.GroupType.ME).dateOfBirth(LocalDate.of(1970, 5, 20))
            .dateOfBirthYearKnown(true).build();
        when(relativeRepo.findByIdAndUserId(50L, 1L)).thenReturn(Optional.of(existing));
        when(eventRepo.findFirstByRelativeIdAndCategory_CodeAndIsActiveTrue(50L, "SINH_NHAT"))
            .thenReturn(Optional.empty());
        when(categoryRepo.findByCode("SINH_NHAT")).thenReturn(Optional.of(birthdayCategory));

        service.update(50L, 1L, baseRequest(LocalDate.of(1900, 5, 20), false));

        assertFalse(existing.getDateOfBirthYearKnown());
    }

    @Test
    void getDetail_whenYearUnknown_returnsNullAge() {
        Relative existing = Relative.builder().id(50L).user(User.builder().id(1L).build())
            .name("Mẹ").groupType(Relative.GroupType.ME)
            .dateOfBirth(LocalDate.of(1900, 5, 20)).dateOfBirthYearKnown(false).build();
        when(relativeRepo.findByIdAndUserId(50L, 1L)).thenReturn(Optional.of(existing));
        when(eventRepo.findByRelativeIdAndIsActiveTrueOrderByEventDateAsc(50L)).thenReturn(List.of());

        RelativeDetailResponse response = service.getDetail(50L, 1L);

        assertNull(response.getAge());
    }

    @Test
    void getDetail_whenYearKnown_returnsRealAge() {
        Relative existing = Relative.builder().id(50L).user(User.builder().id(1L).build())
            .name("Mẹ").groupType(Relative.GroupType.ME)
            .dateOfBirth(LocalDate.now().minusYears(30)).dateOfBirthYearKnown(true).build();
        when(relativeRepo.findByIdAndUserId(50L, 1L)).thenReturn(Optional.of(existing));
        when(eventRepo.findByRelativeIdAndIsActiveTrueOrderByEventDateAsc(50L)).thenReturn(List.of());

        RelativeDetailResponse response = service.getDetail(50L, 1L);

        assertEquals(30, response.getAge());
    }

    // ── DELETE — Event Sinh nhật liên kết không được mồ côi ────────────────

    @Test
    void delete_deactivatesLinkedBirthdayEvent() {
        Relative existing = Relative.builder().id(50L).user(User.builder().id(1L).build())
            .name("Mẹ").groupType(Relative.GroupType.ME).dateOfBirth(LocalDate.of(1970, 5, 20)).build();
        Event linkedEvent = Event.builder().id(77L).category(birthdayCategory)
            .eventDate(LocalDate.of(1970, 5, 20)).isActive(true).build();
        when(relativeRepo.findByIdAndUserId(50L, 1L)).thenReturn(Optional.of(existing));
        when(eventRepo.findFirstByRelativeIdAndCategory_CodeAndIsActiveTrue(50L, "SINH_NHAT"))
            .thenReturn(Optional.of(linkedEvent));

        service.delete(50L, 1L);

        assertFalse(linkedEvent.getIsActive());
        verify(eventRepo).save(linkedEvent);
        verify(relativeRepo).delete(existing);
    }

    // ── nextBirthdayOccurrence — quy tắc "ngày lần tới" dùng cho eventDate ──

    @Test
    void nextBirthdayOccurrence_beforeThisYearsBirthday_returnsThisYear() {
        LocalDate dob = LocalDate.of(1970, 12, 25);
        LocalDate today = LocalDate.of(2026, 9, 6);

        assertEquals(LocalDate.of(2026, 12, 25), RelativeService.nextBirthdayOccurrence(dob, today));
    }

    @Test
    void nextBirthdayOccurrence_afterThisYearsBirthday_rollsToNextYear() {
        LocalDate dob = LocalDate.of(1970, 5, 20);
        LocalDate today = LocalDate.of(2026, 9, 6);

        assertEquals(LocalDate.of(2027, 5, 20), RelativeService.nextBirthdayOccurrence(dob, today));
    }

    @Test
    void nextBirthdayOccurrence_todayIsTheBirthday_returnsToday() {
        LocalDate dob = LocalDate.of(1970, 9, 6);
        LocalDate today = LocalDate.of(2026, 9, 6);

        assertEquals(today, RelativeService.nextBirthdayOccurrence(dob, today));
    }

    @Test
    void nextBirthdayOccurrence_leapDayBirthdayInNonLeapYear_clampsToFeb28() {
        LocalDate dob = LocalDate.of(1972, 2, 29);
        LocalDate today = LocalDate.of(2026, 9, 6); // 2027 không nhuận

        assertEquals(LocalDate.of(2027, 2, 28), RelativeService.nextBirthdayOccurrence(dob, today));
    }

    @Test
    void delete_withoutLinkedBirthdayEvent_doesNotTouchEventRepo() {
        Relative existing = Relative.builder().id(50L).user(User.builder().id(1L).build())
            .name("Mẹ").groupType(Relative.GroupType.ME).dateOfBirth(null).build();
        when(relativeRepo.findByIdAndUserId(50L, 1L)).thenReturn(Optional.of(existing));
        when(eventRepo.findFirstByRelativeIdAndCategory_CodeAndIsActiveTrue(50L, "SINH_NHAT"))
            .thenReturn(Optional.empty());

        service.delete(50L, 1L);

        verify(eventRepo, never()).save(any());
        verify(relativeRepo).delete(existing);
    }
}
