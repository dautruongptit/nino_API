package com.app.nino.repository;

import com.app.nino.model.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    @Query("SELECT u FROM User u WHERE u.email = :email AND u.status IN ('ACT','VRF')")
    Optional<User> findLoginableByEmail(@Param("email") String email);

    boolean existsByEmail(String email);

    Optional<User> findByGoogleId(String googleId);

    @Modifying
    @Query("UPDATE User u SET u.totalEvents = u.totalEvents + 1 WHERE u.id = :userId")
    void incrementEventCount(@Param("userId") Long userId);

    @Modifying
    @Query("UPDATE User u SET u.totalRelatives = u.totalRelatives + 1 WHERE u.id = :userId")
    void incrementRelativeCount(@Param("userId") Long userId);

    @Modifying
    @Query("UPDATE User u SET u.totalEvents = CASE WHEN u.totalEvents > 0 THEN u.totalEvents - 1 ELSE 0 END WHERE u.id = :userId")
    void decrementEventCount(@Param("userId") Long userId);

    @Modifying
    @Query("UPDATE User u SET u.totalRelatives = CASE WHEN u.totalRelatives > 0 THEN u.totalRelatives - 1 ELSE 0 END WHERE u.id = :userId")
    void decrementRelativeCount(@Param("userId") Long userId);
}
