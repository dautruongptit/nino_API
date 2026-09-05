package com.app.nino.service;

import com.app.nino.exception.ForbiddenException;
import com.app.nino.exception.ResourceNotFoundException;
import com.app.nino.model.dto.response.NotificationResponse;
import com.app.nino.model.entity.Notification;
import com.app.nino.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationService {

    private final NotificationRepository notifRepo;

    // ── GET LIST (phân trang) ────────────────────────────────────────────
    public Page<NotificationResponse> getNotifications(
            Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return notifRepo
                .findByUserIdOrderBySentAtDesc(userId, pageable)
                .map(this::toResponse);
    }

    // ── COUNT UNREAD ─────────────────────────────────────────────────────
    public long countUnread(Long userId) {
        return notifRepo.countByUserIdAndIsReadFalse(userId);
    }

    // ── MARK ONE AS READ ─────────────────────────────────────────────────
    @Transactional
    public NotificationResponse markAsRead(Long id, Long userId) {
        Notification n = notifRepo.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Thong bao khong ton tai: " + id));

        if (!n.getUser().getId().equals(userId)) {
            log.warn("[Notification] Truy cap bi tu choi: notificationId={} ownerUserId={} requestUserId={}",
                id, n.getUser().getId(), userId);
            throw new ForbiddenException("Ban khong co quyen truy cap thong bao nay");
        }

        n.setIsRead(true);
        NotificationResponse response = toResponse(notifRepo.save(n));
        log.debug("[Notification] Danh dau da doc: notificationId={} userId={}", id, userId);
        return response;
    }

    // ── MARK ALL AS READ ─────────────────────────────────────────────────
    @Transactional
    public void markAllAsRead(Long userId) {
        notifRepo.markAllAsRead(userId);
        log.info("[Notification] Danh dau tat ca da doc: userId={}", userId);
    }

    // ── PRIVATE HELPERS ─────────────────────────────────────────────────
    private NotificationResponse toResponse(Notification n) {
        return NotificationResponse.builder()
                .id(n.getId())
                .title(n.getTitle())
                .body(n.getBody())
                .isRead(n.getIsRead())
                .eventId(n.getEvent() != null ? n.getEvent().getId() : null)
                .eventTitle(n.getEvent() != null ? n.getEvent().getTitle() : null)
                .sentAt(n.getSentAt())
                .build();
    }
}
