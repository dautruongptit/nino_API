package com.app.nino.repository;

import com.app.nino.model.entity.Relative;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RelativeRepository extends JpaRepository<Relative, Long> {

    @Query("SELECT r FROM Relative r WHERE r.user.id = :userId"
         + " AND (:groupType IS NULL OR r.groupType = :groupType)"
         + " AND (:search IS NULL OR LOWER(r.name) LIKE LOWER(CONCAT('%', :search, '%')))"
         + " ORDER BY r.name ASC")
    List<Relative> findByFilters(@Param("userId") Long userId,
                                  @Param("groupType") Relative.GroupType groupType,
                                  @Param("search") String search);

    @Query("SELECT r.groupType, COUNT(r) FROM Relative r WHERE r.user.id = :userId GROUP BY r.groupType")
    List<Object[]> countByGroupType(@Param("userId") Long userId);

    @Query("SELECT r FROM Relative r WHERE r.id = :id AND r.user.id = :userId")
    Optional<Relative> findByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    long countByUserId(Long userId);

    @Modifying
    @Query("UPDATE Relative r SET r.totalEvents = r.totalEvents + 1 WHERE r.id = :relativeId")
    void incrementEventCount(@Param("relativeId") Long relativeId);

    @Modifying
    @Query("UPDATE Relative r SET r.totalEvents = CASE WHEN r.totalEvents > 0 THEN r.totalEvents - 1 ELSE 0 END WHERE r.id = :relativeId")
    void decrementRelativeEventCount(@Param("relativeId") Long relativeId);
}
