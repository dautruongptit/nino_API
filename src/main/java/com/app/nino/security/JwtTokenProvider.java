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

    public String generateAccessToken(Long userId, Set<Role> roles, String sid) {
        List<String> roleNames = roles.stream().map(Role::getName).collect(Collectors.toList());

        String token = Jwts.builder()
            .subject(String.valueOf(userId))
            .claim("roles", roleNames)
            .claim("type", "access")
            .claim("sid", sid)
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + jwtExpiration))
            .signWith(getSigningKey())
            .compact();
        log.debug("[JWT] Tao access token: userId={} roles={} sid={} expiresInMs={}", userId, roleNames, sid, jwtExpiration);
        return token;
    }

    public String generateRefreshToken(Long userId, String sid) {
        String token = Jwts.builder()
            .subject(String.valueOf(userId))
            .claim("type", "refresh")
            .claim("sid", sid)
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + refreshExpiration))
            .signWith(getSigningKey())
            .compact();
        log.debug("[JWT] Tao refresh token: userId={} sid={} expiresInMs={}", userId, sid, refreshExpiration);
        return token;
    }

    public boolean validateToken(String token) {
        Claims claims;
        try {
            claims = Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(token).getPayload();
        } catch (ExpiredJwtException e) {
            log.warn("[JWT] Token da het han: subject={}", e.getClaims().getSubject());
            return false;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("[JWT] Token khong hop le: {}", e.getMessage());
            return false;
        }
        String sid = claims.get("sid", String.class);
        if (sid != null) {
            if (tokenBlacklistService.isBlacklisted(sid)) {
                log.warn("[JWT] Phien da bi thu hoi: sid={}", sid);
                return false;
            }
        } else if (tokenBlacklistService.isBlacklisted(token)) {
            // Token tao truoc khi co claim "sid" — van phai ton trong cac muc
            // blacklist CU (chan theo dung chuoi token) de khong lam "song lai"
            // token da bi nguoi dung chu dong thu hoi truoc khi trien khai sid.
            log.warn("[JWT] Token cu (khong co sid) da bi thu hoi truoc do");
            return false;
        }
        return true;
    }

    /** Claim "sid" — dinh danh phien dang nhap, giu nguyen qua moi lan
     *  refresh (khac voi token thay doi moi lan cap). Dung de blacklist ca
     *  access lan refresh token cua 1 phien chi bang 1 lan ghi. */
    public String getSid(String token) {
        Claims claims = Jwts.parser().verifyWith(getSigningKey()).build()
            .parseSignedClaims(token).getPayload();
        return claims.get("sid", String.class);
    }

    /** De AuthService/GoogleAuthService tinh refreshExpiresAt luc luu
     *  LoginHistory ma khong phai tu khai bao lai @Value nay o noi khac. */
    public long getRefreshExpirationMs() {
        return refreshExpiration;
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
