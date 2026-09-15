package com.app.nino.service;

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

        // Tra ve loi "khong ton tai" giong het truong hop id sai — khong lo cho
        // ke tan cong (do ID tuan tu) biet dong nay co ton tai nhung thuoc ve
        // user khac (chong doan ID / IDOR enumeration), giong pattern da ap
        // dung o RelativeService/AuthService.revokeLoginHistorySession.
        if (!n.getUser().getId().equals(userId)) {
            log.warn("[Notification] Truy cap bi tu choi: notificationId={} ownerUserId={} requestUserId={}",
                id, n.getUser().getId(), userId);
            throw new ResourceNotFoundException("Thong bao khong ton tai: " + id);
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

    // ── DELETE ONE ────────────────────────────────────────────────────────
    @Transactional
    public void delete(Long id, Long userId) {
        Notification n = notifRepo.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Thong bao khong ton tai: " + id));

        // Xem giai thich chong doan ID o markAsRead() ben tren.
        if (!n.getUser().getId().equals(userId)) {
            log.warn("[Notification] Xoa bi tu choi: notificationId={} ownerUserId={} requestUserId={}",
                id, n.getUser().getId(), userId);
            throw new ResourceNotFoundException("Thong bao khong ton tai: " + id);
        }

        notifRepo.delete(n);
        log.info("[Notification] Da xoa: notificationId={} userId={}", id, userId);
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
