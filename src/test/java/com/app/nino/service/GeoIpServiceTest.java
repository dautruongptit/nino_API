package com.app.nino.service;

import com.app.nino.model.entity.LoginHistory;
import com.app.nino.repository.LoginHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GeoIpServiceTest {

    @Mock private RestTemplate geoIpRestTemplate;
    @Mock private LoginHistoryRepository loginHistoryRepo;

    @InjectMocks
    private GeoIpService service;

    @Test
    void lookupAndUpdate_privateIp_skipsLookupEntirely() {
        service.lookupAndUpdate(1L, "192.168.1.5");

        verifyNoInteractions(geoIpRestTemplate);
        verifyNoInteractions(loginHistoryRepo);
    }

    @Test
    void lookupAndUpdate_loopbackIp_skipsLookupEntirely() {
        service.lookupAndUpdate(1L, "127.0.0.1");

        verifyNoInteractions(geoIpRestTemplate);
        verifyNoInteractions(loginHistoryRepo);
    }

    @Test
    void lookupAndUpdate_publicIpSuccess_savesCountryAndCityOnHistory() {
        GeoIpService.GeoIpResponse resp = new GeoIpService.GeoIpResponse();
        resp.setStatus("success");
        resp.setCountry("Việt Nam");
        resp.setCity("Hà Nội");
        when(geoIpRestTemplate.getForObject(anyString(), eq(GeoIpService.GeoIpResponse.class)))
            .thenReturn(resp);
        LoginHistory history = LoginHistory.builder().id(1L).build();
        when(loginHistoryRepo.findById(1L)).thenReturn(Optional.of(history));

        service.lookupAndUpdate(1L, "8.8.8.8");

        ArgumentCaptor<LoginHistory> saved = ArgumentCaptor.forClass(LoginHistory.class);
        verify(loginHistoryRepo).save(saved.capture());
        assertEquals("Việt Nam", saved.getValue().getCountry());
        assertEquals("Hà Nội", saved.getValue().getCity());
    }

    @Test
    void lookupAndUpdate_apiReturnsFailStatus_doesNotSave() {
        GeoIpService.GeoIpResponse resp = new GeoIpService.GeoIpResponse();
        resp.setStatus("fail");
        resp.setMessage("private range");
        when(geoIpRestTemplate.getForObject(anyString(), eq(GeoIpService.GeoIpResponse.class)))
            .thenReturn(resp);

        service.lookupAndUpdate(1L, "8.8.8.8");

        verify(loginHistoryRepo, never()).save(any());
    }

    @Test
    void lookupAndUpdate_restClientThrows_doesNotPropagate() {
        when(geoIpRestTemplate.getForObject(anyString(), eq(GeoIpService.GeoIpResponse.class)))
            .thenThrow(new RestClientException("timeout"));

        assertDoesNotThrow(() -> service.lookupAndUpdate(1L, "8.8.8.8"));
        verify(loginHistoryRepo, never()).save(any());
    }

    @Test
    void lookupAndUpdate_historyNotFound_doesNotThrow() {
        GeoIpService.GeoIpResponse resp = new GeoIpService.GeoIpResponse();
        resp.setStatus("success");
        resp.setCountry("Việt Nam");
        resp.setCity("Hà Nội");
        when(geoIpRestTemplate.getForObject(anyString(), eq(GeoIpService.GeoIpResponse.class)))
            .thenReturn(resp);
        when(loginHistoryRepo.findById(anyLong())).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> service.lookupAndUpdate(1L, "8.8.8.8"));
        verify(loginHistoryRepo, never()).save(any());
    }
}
