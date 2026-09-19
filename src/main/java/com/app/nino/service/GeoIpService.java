package com.app.nino.service;

import com.app.nino.model.entity.LoginHistory;
import com.app.nino.repository.LoginHistoryRepository;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Tra vi tri (quoc gia/thanh pho) tu IP dang nhap qua ip-api.com — chay bat
 * dong bo SAU KHI da luu LoginHistory va tra response cho client, nen loi
 * hoac cham o day khong bao gio anh huong toi flow dang nhap.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GeoIpService {

    private static final String API_URL = "http://ip-api.com/json/%s?fields=status,message,country,city";

    @Qualifier("geoIpRestTemplate")
    private final RestTemplate geoIpRestTemplate;
    private final LoginHistoryRepository loginHistoryRepo;

    @Async("geoIpExecutor")
    public void lookupAndUpdate(Long historyId, String ip) {
        if (isPrivateOrLoopback(ip)) {
            log.debug("[GeoIp] Bo qua IP private/loopback: ip={} historyId={}", ip, historyId);
            return;
        }

        GeoIpResponse resp;
        try {
            resp = geoIpRestTemplate.getForObject(String.format(API_URL, ip), GeoIpResponse.class);
        } catch (RestClientException e) {
            log.warn("[GeoIp] Goi ip-api that bai: ip={} error={}", ip, e.getMessage());
            return;
        }

        if (resp == null || !"success".equals(resp.getStatus())) {
            log.debug("[GeoIp] Tra cuu khong thanh cong: ip={} message={}",
                ip, resp != null ? resp.getMessage() : null);
            return;
        }

        loginHistoryRepo.findById(historyId).ifPresent(history -> {
            history.setCountry(resp.getCountry());
            history.setCity(resp.getCity());
            loginHistoryRepo.save(history);
        });
    }

    /** IP null/rong/loopback/private/link-local deu khong tra duoc vi tri that —
     *  bo qua som de khong ton request goi ip-api (gioi han ~45 req/phut). */
    static boolean isPrivateOrLoopback(String ip) {
        if (ip == null || ip.isBlank()) return true;
        try {
            InetAddress addr = InetAddress.getByName(ip);
            return addr.isLoopbackAddress() || addr.isSiteLocalAddress()
                || addr.isAnyLocalAddress() || addr.isLinkLocalAddress();
        } catch (UnknownHostException e) {
            return true;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GeoIpResponse {
        private String status;
        private String message;
        private String country;
        private String city;
    }
}
