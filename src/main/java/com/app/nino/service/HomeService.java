package com.app.nino.service;

import com.app.nino.exception.ResourceNotFoundException;
import com.app.nino.model.dto.response.EventResponse;
import com.app.nino.model.dto.response.HomeResponse;
import com.app.nino.model.dto.response.RelativeResponse;
import com.app.nino.model.entity.User;
import com.app.nino.repository.EventRepository;
import com.app.nino.repository.RelativeRepository;
import com.app.nino.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HomeService {

    private final EventRepository    eventRepo;
    private final RelativeRepository relativeRepo;
    private final UserRepository     userRepo;
    private final EventService       eventService;

    // ── GET HOME DATA — cache 5 phút ──────────────────────────────────────────
    @Cacheable(value = "home", key = "#userId")
    public HomeResponse getHomeData(Long userId) {
        log.debug("[Home] Cache miss — tinh lai home data: userId={}", userId);
        User user = userRepo.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("Nguoi dung khong ton tai"));
        List<EventResponse> upcomingEvents = eventService.getUpcoming(userId, 5);
        List<EventResponse> myEvents       = getMyEvents(userId);
        List<RelativeResponse> relatives   = relativeRepo
            .findByFilters(userId, null, null)
            .stream()
            .map(RelativeResponse::from)
            .toList();

        return HomeResponse.builder()
            .userName(user.getFullName())
            .avatarUrl(user.getAvatarUrl())
            .googleCalendarConnected(user.getGoogleCalendarToken() != null)
            .upcomingEvents(upcomingEvents)
            .myEvents(myEvents)
            .relatives(relatives)
            .build();
    }

    // ── GET MY EVENTS — cache 5 phút ─────────────────────────────────────────
    @Cacheable(value = "upcoming", key = "#userId + '::myEvents'")
    public List<EventResponse> getMyEvents(Long userId) {
        return eventRepo.findMyUpcoming(userId, LocalDate.now(), PageRequest.of(0, 20))
            .stream()
            .map(e -> eventService.toResponse(e, LocalDate.now()))
            .toList();
    }

    @CacheEvict(value = "home", key = "#userId")
    public void evictHome(Long userId) {
        // trigger evict thủ công nếu cần từ Service khác
    }
}
