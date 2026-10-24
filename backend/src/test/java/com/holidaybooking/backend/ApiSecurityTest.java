package com.holidaybooking.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.holidaybooking.backend.identity.application.IdentityService;
import com.holidaybooking.backend.identity.domain.IdentityRepository;
import com.holidaybooking.backend.identity.domain.UserAccount;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** 使用真实过滤器链验证会话持久化、CSRF 以及角色变更后的权限。 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ApiSecurityTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private IdentityService identity;
    @Autowired private IdentityRepository accounts;
    @Autowired private TransactionTemplate transactions;

    @Test
    void loginRotatesSessionAndCsrfThenLogoutInvalidatesAuthentication() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        var registered = identity.register(email, "test-password-123", "Session Guest");

        var initial = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        Cookie initialSession = initial.getResponse().getCookie("SESSION");
        assertThat(initialSession).isNotNull();
        String token =
                json.readTree(initial.getResponse().getContentAsString())
                        .at("/data/token")
                        .asText();

        var login =
                mvc.perform(
                                post("/api/v1/auth/login")
                                        .cookie(initialSession)
                                        .header("X-CSRF-TOKEN", token)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                json.writeValueAsString(
                                                        java.util.Map.of(
                                                                "email",
                                                                email,
                                                                "password",
                                                                "test-password-123"))))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.id").value(registered.id()))
                        .andReturn();

        Cookie authenticatedSession = login.getResponse().getCookie("SESSION");
        assertThat(authenticatedSession).isNotNull();
        assertThat(authenticatedSession.getValue()).isNotEqualTo(initialSession.getValue());
        mvc.perform(get("/api/v1/auth/me").cookie(authenticatedSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("CUSTOMER"));

        // 登录后旧 token 已失效，客户端必须重新获取。
        mvc.perform(
                        post("/api/v1/auth/logout")
                                .cookie(authenticatedSession)
                                .header("X-CSRF-TOKEN", token))
                .andExpect(status().isForbidden());
        var refreshed =
                mvc.perform(get("/api/v1/auth/csrf").cookie(authenticatedSession)).andReturn();
        String refreshedToken =
                json.readTree(refreshed.getResponse().getContentAsString())
                        .at("/data/token")
                        .asText();
        mvc.perform(
                        post("/api/v1/auth/logout")
                                .cookie(authenticatedSession)
                                .header("X-CSRF-TOKEN", refreshedToken))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").cookie(authenticatedSession))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousAndCustomerRequestsCannotReachManagement() throws Exception {
        mvc.perform(get("/api/v1/admin/bookings")).andExpect(status().isUnauthorized());
        var customer =
                identity.register(
                        UUID.randomUUID() + "@example.com", "test-password-123", "Customer");
        // 即使请求中的旧认证携带 ADMIN，数据库中的实际角色仍为 CUSTOMER。
        mvc.perform(get("/api/v1/admin/bookings").with(user(customer.id()).roles("ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/v1/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void staffCanReadBookingsButCannotChangePrices() throws Exception {
        var staff =
                identity.register(UUID.randomUUID() + "@example.com", "test-password-123", "Staff");
        transactions.executeWithoutResult(
                status ->
                        accounts.lock(Long.parseLong(staff.id()))
                                .changeRole(UserAccount.Role.STAFF));
        mvc.perform(get("/api/v1/admin/bookings").with(user(staff.id()).roles("STAFF")))
                .andExpect(status().isOk());
        mvc.perform(
                        post("/api/v1/admin/room-types")
                                .with(user(staff.id()).roles("STAFF"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"name":"Suite","description":"Test suite","capacity":2,"basePrice":"100.00","active":true}
                                        """))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidCredentialsAndMalformedInputHaveStableErrors() throws Exception {
        mvc.perform(
                        post("/api/v1/auth/login")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"email":"nobody@example.com","password":"incorrect-password"}
                                        """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
        mvc.perform(
                        get("/api/v1/availability")
                                .param("checkIn", "not-a-date")
                                .param("checkOut", "2026-11-12"))
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Trace-Id"));
    }
}
