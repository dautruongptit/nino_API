package com.app.nino.controller;

import com.app.nino.model.dto.request.DeviceTokenRequest;
import com.app.nino.model.entity.User;
import com.app.nino.model.entity.UserDevice;
import com.app.nino.repository.UserDeviceRepository;
import com.app.nino.repository.UserRepository;
import com.app.nino.service.FcmService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeviceControllerTest {

    @Mock private UserDeviceRepository deviceRepo;
    @Mock private UserRepository userRepo;
    @Mock private FcmService fcmService;

    @InjectMocks
    private DeviceController controller;

    @Test
    void registerDevice_doesNotSendTestPush() {
        // Push "Kết nối thành công" trên MỌI lần đăng ký thiết bị — kể cả sau
        // mỗi lần login — chính là thông báo người dùng muốn bỏ. Đăng ký
        // thiết bị vẫn phải hoạt động (để nhận nhắc nhở thật sau này), chỉ
        // không tự bắn push xác nhận nữa.
        when(userRepo.findById(1L)).thenReturn(Optional.of(User.builder().id(1L).build()));
        when(deviceRepo.findByFcmToken("tok-1")).thenReturn(Optional.empty());

        DeviceTokenRequest req = new DeviceTokenRequest();
        req.setFcmToken("tok-1");
        req.setPlatform("ANDROID");
        req.setDeviceName("Pixel 8");

        controller.registerDevice(1L, req);

        verify(deviceRepo).save(any(UserDevice.class));
        verify(fcmService, never()).sendTestPush(anyString());
    }
}
