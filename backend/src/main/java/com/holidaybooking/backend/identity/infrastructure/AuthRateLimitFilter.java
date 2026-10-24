package com.holidaybooking.backend.identity.infrastructure;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bounded, single-instance throttle. A production reverse proxy can add a shared limit later. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AuthRateLimitFilter extends OncePerRequestFilter {
    private record Window(long minute, int count) {}

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod())
                || !(request.getRequestURI().equals("/api/v1/auth/login")
                        || request.getRequestURI().equals("/api/v1/auth/register"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long minute = System.currentTimeMillis() / 60000;
        if (windows.size() > 5000) {
            windows.entrySet().removeIf(e -> e.getValue().minute() < minute);
        }
        String address =
                request.getRemoteAddr(); // Never trust an arbitrary X-Forwarded-For header.
        if (windows.size() > 5000 && !windows.containsKey(address)) {
            reject(response);
            return;
        }
        var window =
                windows.compute(
                        address,
                        (key, old) ->
                                old == null || old.minute() != minute
                                        ? new Window(minute, 1)
                                        : new Window(minute, old.count() + 1));
        if (window.count() > 20) {
            reject(response);
            return;
        }
        chain.doFilter(request, response);
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setHeader("Retry-After", "60");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"RATE_LIMITED\",\"message\":\"操作过于频繁，请稍后重试\"}");
    }
}
