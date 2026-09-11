package com.app.nino.repository;

import com.app.nino.model.entity.LoginHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface LoginHistoryRepository extends JpaRepository<LoginHistory, Long> {

    Page<LoginHistory> findByUserIdOrderByLoginAtDesc(Long userId, Pageable pageable);

    List<LoginHistory> findTop5ByUserIdOrderByLoginAtDesc(Long userId);

    @Query("SELECT COUNT(l) FROM LoginHistory l WHERE l.user.id = :userId"
         + " AND l.isSuccess = false AND l.loginAt >= :since")
    long countFailuresSince(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    List<LoginHistory> findByIpAddressAndUserIdOrderByLoginAtDesc(String ipAddress, Long userId);

    Optional<LoginHistory> findBySessionId(String sessionId);
}
