package com.studyos.shared.security;

import com.studyos.identity.application.IdentityAccess;
import com.studyos.shared.persistence.Json;
import com.studyos.shared.web.ApiErrorHandler;
import com.studyos.shared.web.ApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, JwtTokens tokens, IdentityAccess identities)
            throws Exception {
        return http.csrf(c -> c.disable())
                .cors(c -> {})
                .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        c ->
                                c.requestMatchers(
                                                "/api/v1/auth/**",
                                                "/actuator/health",
                                                "/actuator/health/**",
                                                "/ws/v1/**")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .exceptionHandling(
                        c ->
                                c.authenticationEntryPoint(
                                                (req, res, e) ->
                                                        error(
                                                                res,
                                                                401,
                                                                "UNAUTHENTICATED",
                                                                "Sign in to continue."))
                                        .accessDeniedHandler(
                                                (req, res, e) ->
                                                        error(
                                                                res,
                                                                403,
                                                                "FORBIDDEN",
                                                                "Access denied.")))
                .addFilterBefore(
                        new OncePerRequestFilter() {
                            @Override
                            protected void doFilterInternal(
                                    HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain)
                                    throws ServletException, IOException {
                                String header = req.getHeader("Authorization");
                                if (header != null && header.startsWith("Bearer ")) {
                                    try {
                                        UUID user =
                                                UUID.fromString(
                                                        tokens.verify(
                                                                        header.substring(7),
                                                                        "studyos-web")
                                                                .getSubject());
                                        if (!identities.isActive(user))
                                            throw new ApiException(
                                                    401, "INVALID_TOKEN", "Account unavailable.");
                                        SecurityContextHolder.getContext()
                                                .setAuthentication(
                                                        new UsernamePasswordAuthenticationToken(
                                                                new Actor(user), null, List.of()));
                                    } catch (ApiException ex) {
                                        error(res, ex.status(), ex.code(), ex.getMessage());
                                        return;
                                    }
                                }
                                chain.doFilter(req, res);
                            }
                        },
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    private static void error(HttpServletResponse res, int status, String code, String message)
            throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.getWriter().write(Json.write(ApiErrorHandler.body(code, message)));
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${studyos.cors-origins}") String origins) {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.asList(origins.split(",")));
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(
                List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Request-Id"));
        config.setMaxAge(3600L);
        config.setExposedHeaders(List.of("X-Has-More", "X-Next-Cursor"));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
