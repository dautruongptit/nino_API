package com.app.nino.repository;

import com.app.nino.model.entity.EventCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EventCategoryRepository extends JpaRepository<EventCategory, Long> {

    // Danh mục hệ thống (isSystem=true), sắp theo sortOrder — dùng cho
    // GET /events/categories (picker "Danh mục" ở Flutter, thay vì hardcode).
    List<EventCategory> findByIsSystemTrueOrderBySortOrderAsc();
}
