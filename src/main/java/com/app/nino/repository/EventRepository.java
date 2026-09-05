package com.app.nino.repository;

import com.app.nino.model.entity.Event;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface EventRepository extends JpaRepository<Event, Long> {

    // ── Sắp tới (Home + màn Upcoming) ───────────────────────────────────────

    @Query("SELECT e FROM Event e WHERE e.user.id = :userId"
         + " AND e.eventDate BETWEEN :from AND :to"
         + " AND e.isActive = true"
         + " ORDER BY e.eventDate ASC")
    List<Event> findUpcoming(@Param("userId") Long userId,
                             @Param("from") LocalDate from,
                             @Param("to") LocalDate to,
                             Pageable pageable);

    /** Sự kiện của bản thân — relative IS NULL */
    @Query("SELECT e FROM Event e WHERE e.user.id = :userId"
         + " AND e.relative IS NULL"
         + " AND e.eventDate >= :today"
         + " AND e.isActive = true"
         + " ORDER BY e.eventDate ASC")
    List<Event> findMyUpcoming(@Param("userId") Long userId,
                                @Param("today") LocalDate today,
                                Pageable pageable);

    // ── Danh sách có filter (SEC: Danh mục sự kiện — dùng categoryId) ───────

    @Query("SELECT e FROM Event e WHERE e.user.id = :userId"
         + " AND (:categoryId IS NULL OR e.category.id = :categoryId)"
         + " AND (:relativeId IS NULL OR e.relative.id = :relativeId)"
         + " AND (:month IS NULL OR MONTH(e.eventDate) = :month)"
         + " AND (:year IS NULL OR YEAR(e.eventDate) = :year)"
         + " AND e.isActive = true"
         + " ORDER BY e.eventDate ASC")
    List<Event> findFiltered(@Param("userId") Long userId,
                              @Param("categoryId") Long categoryId,
                              @Param("relativeId") Long relativeId,
                              @Param("month") Integer month,
                              @Param("year") Integer year);

    // ── Theo người thân ───────────────────────────────────────────────────

    List<Event> findByRelativeIdAndIsActiveTrueOrderByEventDateAsc(Long relativeId);

    /**
     * Event "Sinh nhật" đang active gắn với 1 người thân — dùng để đồng bộ
     * 1-chiều-1 với {@code Relative.dateOfBirth} (RelativeService): mỗi
     * người thân có tối đa 1 Event loại này.
     */
    Optional<Event> findFirstByRelativeIdAndCategory_CodeAndIsActiveTrue(Long relativeId, String code);

    // ── Ownership check ──────────────────────────────────────────────────

    @Query("SELECT e FROM Event e WHERE e.id = :id AND e.user.id = :userId")
    Optional<Event> findByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    // ── Sự kiện lặp lại theo Âm lịch (SEC: LUNAR_YEARLY) ────────────────────

    List<Event> findByRecurrenceTypeAndIsActiveTrue(Event.RecurrenceType recurrenceType);
}
