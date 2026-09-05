package com.app.nino.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * KHONG dung @Component — SecurityConfig tu tao instance bang "new"
 * de tranh circular dependency (SecurityConfig can JwtTokenProvider,
 * JwtTokenProvider khong duoc phep phu thuoc nguoc lai SecurityConfig).
 */
@Slf4j
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final CustomUserDetailsService userDetailsService;

    public JwtAuthFilter(JwtTokenProvider jwtTokenProvider,
                          CustomUserDetailsService userDetailsService) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {

        String header = request.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);

            if (jwtTokenProvider.validateToken(token)
                    && "access".equals(jwtTokenProvider.getTokenType(token))) {

                Long userId = jwtTokenProvider.getUserId(token);
                List<String> roles = jwtTokenProvider.getRoles(token);

                List<GrantedAuthority> authorities = roles.stream()
                    .map(SimpleGrantedAuthority::new)
                    .map(a -> (GrantedAuthority) a)
                    .toList();

                // Principal la userId (Long) truc tiep — khong query DB moi request,
                // giup @AuthenticationPrincipal Long userId hoat dong o moi Controller
                UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userId, null, authorities);

                SecurityContextHolder.getContext().setAuthentication(authentication);

                // Luu vao request attribute (khong bi Spring Security clear khi
                // SecurityContext bi xoa cuoi request) de RequestLoggingFilter -
                // filter nam TRUOC ca chain security - doc lai duoc userId sau khi
                // filterChain.doFilter() da chay xong.
                request.setAttribute("authUserId", userId);

                log.debug("[JwtAuthFilter] Xac thuc thanh cong: userId={} roles={} uri={}",
                    userId, roles, request.getRequestURI());
            } else {
                log.debug("[JwtAuthFilter] Token khong hop le hoac sai loai: uri={}", request.getRequestURI());
            }
        }

        filterChain.doFilter(request, response);
    }
}
