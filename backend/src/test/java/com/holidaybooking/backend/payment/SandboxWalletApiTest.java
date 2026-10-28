package com.holidaybooking.backend.payment;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.holidaybooking.backend.support.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@Tag("channels")
class SandboxWalletApiTest extends BusinessTestSupport {
    @Autowired private MockMvc mvc;

    @Test
    @CaseSpec(
            input = "匿名GET钱包；登录后不带CSRF设置PIN；登录且带CSRF调用旧succeed路由",
            expected = "分别401、403、404；不存在无密码支付入口")
    void authenticationCsrfAndRemovedBypassAreEnforced() throws Exception {
        long id = user();
        mvc.perform(get("/api/v1/sandbox/wallet"))
                .andDo(TestEvidence::http)
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/api/v1/sandbox/wallet/setup")
                                .with(SecurityMockMvcRequestPostProcessors.user(Long.toString(id)))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andDo(TestEvidence::http)
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/v1/dev/payment-attempts/1/succeed")
                                .with(SecurityMockMvcRequestPostProcessors.user(Long.toString(id)))
                                .with(csrf()))
                .andDo(TestEvidence::http)
                .andExpect(status().isNotFound());
    }

    @Test
    @CaseSpec(
            input = "用登录密码test-password-123设置pin=824631、confirmation=824631，再GET钱包",
            expected = "200；configured=true，balance=0.00，currency=CNY；响应无pin、pinHash或loginPassword")
    void walletResponseNeverContainsCredentials() throws Exception {
        long id = user();
        mvc.perform(
                        post("/api/v1/sandbox/wallet/setup")
                                .with(SecurityMockMvcRequestPostProcessors.user(Long.toString(id)))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"loginPassword\":\"test-password-123\",\"pin\":\"824631\",\"confirmation\":\"824631\"}"))
                .andDo(TestEvidence::http)
                .andExpect(status().isOk());
        mvc.perform(
                        get("/api/v1/sandbox/wallet")
                                .with(SecurityMockMvcRequestPostProcessors.user(Long.toString(id))))
                .andDo(TestEvidence::http)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(true))
                .andExpect(jsonPath("$.data.balance").value("0.00"))
                .andExpect(jsonPath("$.data.currency").value("CNY"))
                .andExpect(jsonPath("$.data.pin").doesNotExist())
                .andExpect(jsonPath("$.data.pinHash").doesNotExist())
                .andExpect(jsonPath("$.data.loginPassword").doesNotExist());
    }
}
