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
}
