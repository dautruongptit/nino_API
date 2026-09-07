package com.app.nino.security;

import com.app.nino.model.entity.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
public class JwtTokenProvider {

    @Value("${JWT_SECRET}")
    private String jwtSecret;

    @Value("${JWT_EXPIRATION:86400000}")
    private long jwtExpiration;

    @Value("${JWT_REFRESH_EXPIRATION:604800000}")
    private long refreshExpiration;

    @org.springframework.beans.factory.annotation.Autowired
    private TokenBlacklistService tokenBlacklistService;

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes());
    }

    public String generateAccessToken(Long userId, Set<Role> roles) {
        List<String> roleNames = roles.stream().map(Role::getName).collect(Collectors.toList());

        String token = Jwts.builder()
            .subject(String.valueOf(userId))
            .claim("roles", roleNames)
            .claim("type", "access")
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + jwtExpiration))
            .signWith(getSigningKey())
            .compact();
        log.debug("[JWT] Tao access token: userId={} roles={} expiresInMs={}", userId, roleNames, jwtExpiration);
        return token;
    }

    public String generateRefreshToken(Long userId) {
        String token = Jwts.builder()
            .subject(String.valueOf(userId))
            .claim("type", "refresh")
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + refreshExpiration))
            .signWith(getSigningKey())
            .compact();
        log.debug("[JWT] Tao refresh token: userId={} expiresInMs={}", userId, refreshExpiration);
        return token;
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(token);
        } catch (ExpiredJwtException e) {
            log.warn("[JWT] Token da het han: subject={}", e.getClaims().getSubject());
            return false;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("[JWT] Token khong hop le: {}", e.getMessage());
            return false;
        }
        if (tokenBlacklistService.isBlacklisted(token)) {
            log.warn("[JWT] Token da bi thu hoi (logout)");
            return false;
        }
        return true;
    }

    /** Thoi gian con lai truoc khi token tu het han — dung de dat TTL blacklist khi logout. */
    public java.time.Duration getRemainingValidity(String token) {
        Claims claims = Jwts.parser().verifyWith(getSigningKey()).build()
            .parseSignedClaims(token).getPayload();
        long remainingMs = claims.getExpiration().getTime() - System.currentTimeMillis();
        return java.time.Duration.ofMillis(Math.max(remainingMs, 0));
    }

    public Long getUserId(String token) {
        Claims claims = Jwts.parser().verifyWith(getSigningKey()).build()
            .parseSignedClaims(token).getPayload();
        return Long.valueOf(claims.getSubject());
    }

    @SuppressWarnings("unchecked")
    public List<String> getRoles(String token) {
        Claims claims = Jwts.parser().verifyWith(getSigningKey()).build()
            .parseSignedClaims(token).getPayload();
        return (List<String>) claims.get("roles");
    }

    public String getTokenType(String token) {
        Claims claims = Jwts.parser().verifyWith(getSigningKey()).build()
            .parseSignedClaims(token).getPayload();
        return (String) claims.get("type");
    }
}
