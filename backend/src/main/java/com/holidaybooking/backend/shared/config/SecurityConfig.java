package com.holidaybooking.backend.shared.config;

import com.holidaybooking.backend.identity.domain.IdentityRepository;
import com.holidaybooking.backend.identity.infrastructure.AccountUserDetailsService;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.*;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.session.*;
import org.springframework.security.web.context.*;
import org.springframework.security.web.csrf.*;
import org.springframework.web.filter.OncePerRequestFilter;

/** 统一配置会话认证、CSRF 和权限边界；每次请求重新读取角色，避免旧会话保留过期权限。 */
@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    AuthenticationManager authenticationManager(
            AccountUserDetailsService details, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(details);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        return new HttpSessionCsrfTokenRepository();
    }

    @Bean
    SessionAuthenticationStrategy sessionStrategy(CsrfTokenRepository csrf) {
        return new CompositeSessionAuthenticationStrategy(
                List.of(
                        new ChangeSessionIdAuthenticationStrategy(),
                        new CsrfAuthenticationStrategy(csrf)));
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityContextRepository contexts,
            CsrfTokenRepository csrf,
            IdentityRepository users)
            throws Exception {
        http.securityContext(c -> c.securityContextRepository(contexts).requireExplicitSave(true))
                .csrf(
                        c ->
                                c.csrfTokenRepository(csrf)
                                        .csrfTokenRequestHandler(
                                                new CsrfTokenRequestAttributeHandler())
                                        .ignoringRequestMatchers(
                                                "/api/v1/payment-callbacks/alipay"))
                .authorizeHttpRequests(
                        a ->
                                a.requestMatchers(
                                                HttpMethod.GET,
                                                "/api/v1/auth/csrf",
                                                "/api/v1/room-types/**",
                                                "/api/v1/availability",
                                                "/api/v1/membership/plans",
                                                "/actuator/health",
                                                "/actuator/health/readiness",
                                                "/actuator/health/liveness")
                                        .permitAll()
                                        .requestMatchers(
                                                HttpMethod.POST,
                                                "/api/v1/auth/register",
                                                "/api/v1/auth/login",
                                                "/api/v1/payment-callbacks/alipay")
                                        .permitAll()
                                        .requestMatchers(
                                                "/swagger-ui/**",
                                                "/swagger-ui.html",
                                                "/v3/api-docs/**")
                                        .hasRole("ADMIN")
                                        .requestMatchers("/api/v1/admin/**")
                                        .hasAnyRole("STAFF", "ADMIN")
                                        .anyRequest()
                                        .authenticated())
                .exceptionHandling(
                        e ->
                                e.authenticationEntryPoint(
                                                (r, s, x) ->
                                                        error(s, 401, "UNAUTHENTICATED", "请先登录"))
                                        .accessDeniedHandler(
                                                (r, s, x) ->
                                                        error(
                                                                s,
                                                                403,
                                                                "FORBIDDEN",
                                                                "无权限或 CSRF 校验失败")))
                .logout(
                        l ->
                                l.logoutUrl("/api/v1/auth/logout")
                                        .logoutSuccessHandler((r, s, a) -> s.setStatus(204)));
        // Roles may change while a session exists. Refresh authorization from the DB on each
        // request.
        http.addFilterBefore(
                new OncePerRequestFilter() {
                    @Override
                    protected void doFilterInternal(
                            HttpServletRequest request,
                            HttpServletResponse response,
                            jakarta.servlet.FilterChain chain)
                            throws jakarta.servlet.ServletException, IOException {
                        var auth = SecurityContextHolder.getContext().getAuthentication();
                        if (auth != null
                                && auth.isAuthenticated()
                                && auth.getName().matches("[0-9]+")) {
                            var user = users.find(Long.parseLong(auth.getName())).orElse(null);
                            if (user == null || !user.isEnabled()) {
                                SecurityContextHolder.clearContext();
                                if (request.getSession(false) != null) {
                                    request.getSession(false).invalidate();
                                }
                            } else {
                                var refreshed =
                                        UsernamePasswordAuthenticationToken.authenticated(
                                                auth.getPrincipal(),
                                                null,
                                                List.of(
                                                        new org.springframework.security.core
                                                                .authority.SimpleGrantedAuthority(
                                                                "ROLE_" + user.getRole())));
                                SecurityContextHolder.getContext().setAuthentication(refreshed);
                            }
                        }
                        chain.doFilter(request, response);
                    }
                },
                AnonymousAuthenticationFilter.class);
        return http.build();
    }

    private static void error(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter()
                .write(
                        "{\"code\":\""
                                + code
                                + "\",\"message\":\""
                                + message
                                + "\",\"traceId\":\""
                                + org.slf4j.MDC.get("traceId")
                                + "\"}");
    }
}
