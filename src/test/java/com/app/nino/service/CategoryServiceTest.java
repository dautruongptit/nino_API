package com.app.nino.service;

import com.app.nino.exception.BadRequestException;
import com.app.nino.exception.ForbiddenException;
import com.app.nino.exception.ResourceNotFoundException;
import com.app.nino.model.dto.request.CreateCategoryRequest;
import com.app.nino.model.dto.response.EventCategoryResponse;
import com.app.nino.model.entity.EventCategory;
import com.app.nino.model.entity.User;
import com.app.nino.repository.EventCategoryRepository;
import com.app.nino.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock private EventCategoryRepository categoryRepo;
    @Mock private UserRepository userRepo;

    @InjectMocks
    private CategoryService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id(1L).build();
        lenient().when(userRepo.findById(1L)).thenReturn(Optional.of(user));
    }

    private CreateCategoryRequest baseRequest() {
        CreateCategoryRequest req = new CreateCategoryRequest();
        req.setDisplayName("Du lich");
        req.setIcon("plane");
        req.setColorHex("#1ABC9C");
        return req;
    }

    // ── GET ALL (system + user custom) ─────────────────────────────────

    @Test
    void getCategories_returnsBothSystemAndUserCategories() {
        var sys1 = EventCategory.builder().id(1L).code("SINH_NHAT").displayName("Sinh nhat")
            .icon("cake").colorHex("#FF6B6B").isSystem(true).sortOrder(1).build();
        var custom1 = EventCategory.builder().id(10L).code("CUSTOM_1_123").displayName("Du lich")
            .icon("plane").colorHex("#1ABC9C").isSystem(false).user(user).sortOrder(0).build();

        when(categoryRepo.findByIsSystemTrueOrderBySortOrderAsc()).thenReturn(List.of(sys1));
        when(categoryRepo.findByIsSystemFalseAndUserIdOrderBySortOrderAsc(1L)).thenReturn(List.of(custom1));

        List<EventCategoryResponse> result = service.getCategories(1L);

        assertEquals(2, result.size());
        // System categories come first
        assertEquals("SINH_NHAT", result.get(0).getCode());
        assertTrue(result.get(0).getIsSystem());
        // Then user custom
        assertEquals("CUSTOM_1_123", result.get(1).getCode());
        assertFalse(result.get(1).getIsSystem());
    }

    // ── CREATE ─────────────────────────────────────────────────────────

    @Test
    void create_withValidRequest_savesCustomCategory() {
        when(categoryRepo.existsByDisplayNameAndUserId("Du lich", 1L)).thenReturn(false);
        when(categoryRepo.save(any(EventCategory.class))).thenAnswer(inv -> {
            EventCategory c = inv.getArgument(0);
            c.setId(10L);
            return c;
        });

        EventCategoryResponse res = service.create(1L, baseRequest());

        assertNotNull(res);
        assertEquals("Du lich", res.getDisplayName());
        assertEquals("plane", res.getIcon());
        assertEquals("#1ABC9C", res.getColorHex());
        assertFalse(res.getIsSystem());

        verify(categoryRepo).save(argThat(c ->
            !c.getIsSystem()
            && c.getUser().getId().equals(1L)
            && c.getCode().startsWith("CUSTOM_1_")
        ));
    }

    @Test
    void create_withDuplicateDisplayName_throwsBadRequest() {
        when(categoryRepo.existsByDisplayNameAndUserId("Du lich", 1L)).thenReturn(true);

        assertThrows(BadRequestException.class, () -> service.create(1L, baseRequest()));
        verify(categoryRepo, never()).save(any());
    }

    // ── UPDATE ─────────────────────────────────────────────────────────

    @Test
    void update_ownCustomCategory_updatesFields() {
        EventCategory existing = EventCategory.builder()
            .id(10L).code("CUSTOM_1_123").displayName("Du lich")
            .icon("plane").colorHex("#1ABC9C").isSystem(false).user(user).sortOrder(0).build();
        when(categoryRepo.findById(10L)).thenReturn(Optional.of(existing));
        when(categoryRepo.existsByDisplayNameAndUserId("Suc khoe", 1L)).thenReturn(false);
        when(categoryRepo.save(any(EventCategory.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateCategoryRequest req = new CreateCategoryRequest();
        req.setDisplayName("Suc khoe");
        req.setIcon("pill");
        req.setColorHex("#E74C3C");

        EventCategoryResponse res = service.update(10L, 1L, req);

        assertEquals("Suc khoe", res.getDisplayName());
        assertEquals("pill", res.getIcon());
        assertEquals("#E74C3C", res.getColorHex());
    }

    @Test
    void update_systemCategory_throwsForbidden() {
        EventCategory system = EventCategory.builder()
            .id(1L).code("SINH_NHAT").displayName("Sinh nhat")
            .icon("cake").colorHex("#FF6B6B").isSystem(true).sortOrder(1).build();
        when(categoryRepo.findById(1L)).thenReturn(Optional.of(system));

        assertThrows(ForbiddenException.class, () -> service.update(1L, 1L, baseRequest()));
        verify(categoryRepo, never()).save(any());
    }

    @Test
    void update_otherUsersCategory_throwsForbidden() {
        User otherUser = User.builder().id(2L).build();
        EventCategory other = EventCategory.builder()
            .id(10L).code("CUSTOM_2_456").displayName("Kham benh")
            .icon("hospital").colorHex("#3498DB").isSystem(false).user(otherUser).sortOrder(0).build();
        when(categoryRepo.findById(10L)).thenReturn(Optional.of(other));

        assertThrows(ForbiddenException.class, () -> service.update(10L, 1L, baseRequest()));
        verify(categoryRepo, never()).save(any());
    }

    @Test
    void update_nonExistentCategory_throwsNotFound() {
        when(categoryRepo.findById(999L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.update(999L, 1L, baseRequest()));
    }

    // ── DELETE ─────────────────────────────────────────────────────────

    @Test
    void delete_ownCustomCategoryWithNoEvents_deletesSuccessfully() {
        EventCategory existing = EventCategory.builder()
            .id(10L).code("CUSTOM_1_123").displayName("Du lich")
            .icon("plane").colorHex("#1ABC9C").isSystem(false).user(user).sortOrder(0).build();
        when(categoryRepo.findById(10L)).thenReturn(Optional.of(existing));
        when(categoryRepo.countActiveEventsByCategoryId(10L)).thenReturn(0L);

        service.delete(10L, 1L);

        verify(categoryRepo).delete(existing);
    }

    @Test
    void delete_categoryWithActiveEvents_throwsBadRequest() {
        EventCategory existing = EventCategory.builder()
            .id(10L).code("CUSTOM_1_123").displayName("Du lich")
            .icon("plane").colorHex("#1ABC9C").isSystem(false).user(user).sortOrder(0).build();
        when(categoryRepo.findById(10L)).thenReturn(Optional.of(existing));
        when(categoryRepo.countActiveEventsByCategoryId(10L)).thenReturn(3L);

        assertThrows(BadRequestException.class, () -> service.delete(10L, 1L));
        verify(categoryRepo, never()).delete(any(EventCategory.class));
    }

    @Test
    void delete_systemCategory_throwsForbidden() {
        EventCategory system = EventCategory.builder()
            .id(1L).code("SINH_NHAT").displayName("Sinh nhat")
            .icon("cake").colorHex("#FF6B6B").isSystem(true).sortOrder(1).build();
        when(categoryRepo.findById(1L)).thenReturn(Optional.of(system));

        assertThrows(ForbiddenException.class, () -> service.delete(1L, 1L));
        verify(categoryRepo, never()).delete(any(EventCategory.class));
    }

    @Test
    void delete_otherUsersCategory_throwsForbidden() {
        User otherUser = User.builder().id(2L).build();
        EventCategory other = EventCategory.builder()
            .id(10L).code("CUSTOM_2_456").displayName("Kham benh")
            .icon("hospital").colorHex("#3498DB").isSystem(false).user(otherUser).sortOrder(0).build();
        when(categoryRepo.findById(10L)).thenReturn(Optional.of(other));

        assertThrows(ForbiddenException.class, () -> service.delete(10L, 1L));
        verify(categoryRepo, never()).delete(any(EventCategory.class));
    }

    @Test
    void delete_nonExistentCategory_throwsNotFound() {
        when(categoryRepo.findById(999L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.delete(999L, 1L));
    }
}
