package com.holidaybooking.backend.identity.api;

import com.holidaybooking.backend.identity.application.IdentityService;
import com.holidaybooking.backend.shared.api.ApiResponse;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

/** 认证 API。登录成功后显式保存安全上下文，并更换会话 ID 和 CSRF token。 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {
    private final IdentityService identity;
    private final AuthenticationManager authentication;
    private final SessionAuthenticationStrategy sessions;
    private final SecurityContextRepository contexts;

    public record Register(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 12, max = 72) String password,
            @NotBlank @Size(max = 80) String displayName) {}

    public record Login(@NotBlank @Email String email, @NotBlank @Size(max = 72) String password) {}

    public record CsrfView(String headerName, String token) {}

    @GetMapping("/csrf")
    public ApiResponse<CsrfView> csrf(CsrfToken token) {
        return ApiResponse.of(new CsrfView(token.getHeaderName(), token.getToken()));
    }

    @PostMapping("/register")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ApiResponse<IdentityService.UserView> register(@Valid @RequestBody Register input) {
        return ApiResponse.of(
                identity.register(input.email(), input.password(), input.displayName()));
    }

    @PostMapping("/login")
    public ApiResponse<IdentityService.UserView> login(
            @Valid @RequestBody Login input,
            HttpServletRequest request,
            HttpServletResponse response) {
        Authentication auth =
                authentication.authenticate(
                        UsernamePasswordAuthenticationToken.unauthenticated(
                                input.email(), input.password()));
        sessions.onAuthentication(auth, request, response);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return ApiResponse.of(identity.me(Long.parseLong(auth.getName())));
    }

    @GetMapping("/me")
    public ApiResponse<IdentityService.UserView> me(Authentication auth) {
        return ApiResponse.of(identity.me(Long.parseLong(auth.getName())));
    }
}
