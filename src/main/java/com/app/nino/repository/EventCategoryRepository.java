package com.app.nino.repository;

import com.app.nino.model.entity.EventCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EventCategoryRepository extends JpaRepository<EventCategory, Long> {

    // Danh mục hệ thống (isSystem=true), sắp theo sortOrder — dùng cho
    // GET /events/categories (picker "Danh mục" ở Flutter, thay vì hardcode).
    List<EventCategory> findByIsSystemTrueOrderBySortOrderAsc();

    // Tra danh mục theo code (VD "SINH_NHAT") — dùng khi tự tạo Event từ
    // code (RelativeService), không qua picker nên không có categoryId sẵn.
    Optional<EventCategory> findByCode(String code);

    // Danh mục user tự tạo (isSystem=false), sắp theo sortOrder.
    List<EventCategory> findByIsSystemFalseAndUserIdOrderBySortOrderAsc(Long userId);

    // Kiểm tra trùng tên danh mục trong phạm vi 1 user (chặn tạo 2 cái
    // cùng displayName — UX confusing). Không check cross-user hay system.
    boolean existsByDisplayNameAndUserId(String displayName, Long userId);

    // Đếm event đang active dùng danh mục này — nếu > 0 thì không cho xoá.
    @org.springframework.data.jpa.repository.Query(
        "SELECT COUNT(e) FROM Event e WHERE e.category.id = :categoryId AND e.isActive = true")
    long countActiveEventsByCategoryId(@org.springframework.data.repository.query.Param("categoryId") Long categoryId);
}
