package com.app.nino.service;

import com.app.nino.model.entity.UserDevice;
import com.app.nino.repository.UserDeviceRepository;
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FcmService {

    private final UserDeviceRepository deviceRepo;

    /**
     * Gui push notification toi TAT CA thiet bi cua 1 user.
     * Tu dong don dep token khong con hop le (app bi go, token het han).
     *
     * @param userId  ID user nhan thong bao
     * @param title   Tieu de push
     * @param body    Noi dung push
     * @param data    Du lieu kem theo (VD: eventId de deep-link khi bam vao push)
     */
    @Transactional
    public void sendToUser(Long userId, String title, String body, Map<String, String> data) {

        if (FirebaseApp.getApps().isEmpty()) {
            log.warn("[FCM] FirebaseApp chua duoc khoi tao — bo qua gui push (userId={})", userId);
            return;
        }

        List<UserDevice> devices = deviceRepo.findByUserId(userId);
        if (devices.isEmpty()) {
            log.debug("[FCM] User {} chua dang ky thiet bi nao — bo qua push", userId);
            return;
        }

        List<String> tokens = devices.stream()
            .map(UserDevice::getFcmToken)
            .collect(Collectors.toList());

        MulticastMessage message = MulticastMessage.builder()
            .setNotification(Notification.builder()
                .setTitle(title)
                .setBody(body)
                .build())
            .putAllData(data != null ? data : Map.of())
            .addAllTokens(tokens)
            .setAndroidConfig(AndroidConfig.builder()
                .setPriority(AndroidConfig.Priority.HIGH)
                .setNotification(AndroidNotification.builder()
                    .setSound("default")
                    .setChannelId("event_reminders")
                    .build())
                .build())
            .setApnsConfig(ApnsConfig.builder()
                .setAps(Aps.builder()
                    .setSound("default")
                    .setBadge(1)
                    .build())
                .build())
            .build();

        try {
            BatchResponse response = FirebaseMessaging.getInstance().sendEachForMulticast(message);
            log.info("[FCM] Gui push userId={}: {} thanh cong, {} that bai",
                userId, response.getSuccessCount(), response.getFailureCount());

            if (response.getFailureCount() > 0) {
                cleanupInvalidTokens(tokens, response);
            }
        } catch (FirebaseMessagingException e) {
            log.error("[FCM] Loi gui push userId={}: {}", userId, e.getMessage());
        }
    }

    /**
     * Xoa nhung token khong con hop le (UNREGISTERED, INVALID_ARGUMENT)
     * de tranh gui push that bai lien tuc vao lan sau.
     */
    private void cleanupInvalidTokens(List<String> tokens, BatchResponse response) {
        List<String> invalidTokens = new java.util.ArrayList<>();
        List<SendResponse> responses = response.getResponses();

        for (int i = 0; i < responses.size(); i++) {
            SendResponse sendResponse = responses.get(i);
            if (!sendResponse.isSuccessful()) {
                MessagingErrorCode errorCode = sendResponse.getException().getMessagingErrorCode();
                if (errorCode == MessagingErrorCode.UNREGISTERED
                        || errorCode == MessagingErrorCode.INVALID_ARGUMENT) {
                    invalidTokens.add(tokens.get(i));
                }
            }
        }

        if (!invalidTokens.isEmpty()) {
            deviceRepo.deleteAllByFcmTokenIn(invalidTokens);
            log.info("[FCM] Da xoa {} token khong hop le", invalidTokens.size());
        }
    }

    /**
     * Gui push test (dung khi client vua dang ky token, xac nhan push hoat dong).
     */
    public void sendTestPush(String fcmToken) {
        if (FirebaseApp.getApps().isEmpty()) {
            log.warn("[FCM] FirebaseApp chua khoi tao — khong the gui test push");
            return;
        }

        Message message = Message.builder()
            .setToken(fcmToken)
            .setNotification(Notification.builder()
                .setTitle("Kết nối thành công")
                .setBody("Thiết bị của bạn đã sẵn sàng nhận thông báo từ Nino")
                .build())
            .build();

        try {
            String response = FirebaseMessaging.getInstance().send(message);
            log.info("[FCM] Test push gui thanh cong: {}", response);
        } catch (FirebaseMessagingException e) {
            log.error("[FCM] Test push that bai: {}", e.getMessage());
        }
    }
}
