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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CategoryService {

    private final EventCategoryRepository categoryRepo;
    private final UserRepository userRepo;

    // ── GET ALL (system + user's custom) ────────────────────────────────
    public List<EventCategoryResponse> getCategories(Long userId) {
        List<EventCategoryResponse> result = new ArrayList<>();

        // System categories first
        categoryRepo.findByIsSystemTrueOrderBySortOrderAsc().stream()
            .map(EventCategoryResponse::from)
            .forEach(result::add);

        // Then user's custom categories
        categoryRepo.findByIsSystemFalseAndUserIdOrderBySortOrderAsc(userId).stream()
            .map(EventCategoryResponse::from)
            .forEach(result::add);

        return result;
    }

    // ── CREATE ──────────────────────────────────────────────────────────
    @Transactional
    public EventCategoryResponse create(Long userId, CreateCategoryRequest req) {
        User user = userRepo.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("Nguoi dung khong ton tai"));

        if (categoryRepo.existsByDisplayNameAndUserId(req.getDisplayName(), userId)) {
            throw new BadRequestException("Da ton tai danh muc voi ten '" + req.getDisplayName() + "'");
        }

        String code = "CUSTOM_" + userId + "_" + System.currentTimeMillis();

        EventCategory category = EventCategory.builder()
            .code(code)
            .displayName(req.getDisplayName())
            .icon(req.getIcon())
            .colorHex(req.getColorHex())
            .isSystem(false)
            .user(user)
            .sortOrder(0)
            .build();

        EventCategory saved = categoryRepo.save(category);
        log.info("[Category] Tao danh muc tu tao: id={} userId={} name={}",
            saved.getId(), userId, saved.getDisplayName());
        return EventCategoryResponse.from(saved);
    }

    // ── UPDATE ──────────────────────────────────────────────────────────
    @Transactional
    public EventCategoryResponse update(Long id, Long userId, CreateCategoryRequest req) {
        EventCategory category = findOwnCustomCategory(id, userId);

        // Check duplicate name (skip if name unchanged)
        if (!category.getDisplayName().equals(req.getDisplayName())
                && categoryRepo.existsByDisplayNameAndUserId(req.getDisplayName(), userId)) {
            throw new BadRequestException("Da ton tai danh muc voi ten '" + req.getDisplayName() + "'");
        }

        category.setDisplayName(req.getDisplayName());
        category.setIcon(req.getIcon());
        category.setColorHex(req.getColorHex());

        EventCategory saved = categoryRepo.save(category);
        log.info("[Category] Cap nhat danh muc: id={} userId={} name={}",
            saved.getId(), userId, saved.getDisplayName());
        return EventCategoryResponse.from(saved);
    }

    // ── DELETE ──────────────────────────────────────────────────────────
    @Transactional
    public void delete(Long id, Long userId) {
        EventCategory category = findOwnCustomCategory(id, userId);

        long eventCount = categoryRepo.countActiveEventsByCategoryId(id);
        if (eventCount > 0) {
            throw new BadRequestException(
                "Khong the xoa danh muc dang co " + eventCount + " su kien. "
                + "Hay chuyen cac su kien sang danh muc khac truoc.");
        }

        categoryRepo.delete(category);
        log.info("[Category] Xoa danh muc: id={} userId={} name={}",
            id, userId, category.getDisplayName());
    }

    // ── PRIVATE ─────────────────────────────────────────────────────────

    /** Find category by ID, verify it's non-system and owned by userId. */
    private EventCategory findOwnCustomCategory(Long id, Long userId) {
        EventCategory category = categoryRepo.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Danh muc khong ton tai: " + id));

        if (category.getIsSystem()) {
            throw new ForbiddenException("Khong the sua/xoa danh muc he thong");
        }
        if (category.getUser() == null || !category.getUser().getId().equals(userId)) {
            throw new ForbiddenException("Ban khong co quyen truy cap danh muc nay");
        }
        return category;
    }
}
