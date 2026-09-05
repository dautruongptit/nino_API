package com.app.nino.repository;

import com.app.nino.model.entity.UserDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserDeviceRepository extends JpaRepository<UserDevice, Long> {

    List<UserDevice> findByUserId(Long userId);

    Optional<UserDevice> findByFcmToken(String fcmToken);

    boolean existsByFcmToken(String fcmToken);

    @Modifying
    @Query("DELETE FROM UserDevice d WHERE d.fcmToken = :fcmToken AND d.user.id = :userId")
    void deleteByFcmTokenAndUserId(@Param("fcmToken") String fcmToken, @Param("userId") Long userId);

    @Modifying
    @Query("DELETE FROM UserDevice d WHERE d.fcmToken IN :tokens")
    void deleteAllByFcmTokenIn(@Param("tokens") List<String> tokens);
}
