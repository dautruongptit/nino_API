package com.app.nino.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Audit log cua 1 HTTP request/response, kem thong tin nguoi goi.
 * userId luu truc tiep (khong @ManyToOne, khong FK) — giong cach
 * JwtAuthFilter dung principal la Long truc tiep, va de log van giu
 * nguyen duoc ke ca khi request la anonymous (login/register) hoac
 * user sau nay bi xoa.
 */
@Entity
@Table(name = "request_logs")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RequestLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", length = 36)
    private String requestId;

    @Column(name = "http_method", nullable = false, length = 10)
    private String httpMethod;

    @Column(name = "uri", nullable = false, length = 500)
    private String uri;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "request_body", columnDefinition = "TEXT")
    private String requestBody;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "status_code")
    private Integer statusCode;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) this.createdAt = LocalDateTime.now();
    }
}
