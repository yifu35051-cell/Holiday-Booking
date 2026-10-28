package com.holidaybooking.backend.management;

import static com.holidaybooking.backend.support.TestEvidence.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.holidaybooking.backend.identity.domain.UserAccount;
import com.holidaybooking.backend.management.application.AuditService;
import com.holidaybooking.backend.shared.domain.DomainException;
import com.holidaybooking.backend.support.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 后台 API 与审计；实际权限以数据库中的角色为准。 */
@AutoConfigureMockMvc
@Tag("core")
class ManagementApiTest extends BusinessTestSupport {
    @Autowired MockMvc mvc;
    @Autowired AuditService audit;

    @Test
    @CaseSpec(
            input = "ADMIN和STAFF分别GET /api/v1/admin/users?page=0&size=20；ADMIN再请求size=101",
            expected = "ADMIN得到200分页账户且无密码字段；STAFF为403；非法分页422")
    void userDirectoryRequiresAdminAndDoesNotExposePasswords() throws Exception {
        long admin = user(), staff = user();
        identity.changeRole(staff, admin, UserAccount.Role.ADMIN);
        identity.changeRole(admin, staff, UserAccount.Role.STAFF);
        var adminAuth =
                org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.user(Long.toString(admin));
        mvc.perform(get("/api/v1/admin/users").with(adminAuth))
                .andDo(TestEvidence::http)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items[0].passwordHash").doesNotExist());
        mvc.perform(
                        get("/api/v1/admin/users")
                                .with(
                                        org.springframework.security.test.web.servlet.request
                                                .SecurityMockMvcRequestPostProcessors.user(
                                                Long.toString(staff))))
                .andDo(TestEvidence::http)
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/users?size=101").with(adminAuth))
                .andDo(TestEvidence::http)
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @CaseSpec(
            input = "管理员将测试用户升为 STAFF，再降为 CUSTOMER；测试用户请求后台列表与管理员审计",
            expected = "员工可查后台预订但审计403；降级后旧认证访问后台403；记录本用户的CHANGE_ROLE审计；禁止改自己角色")
    void roleChangesImmediatelyAffectAuthorizationAndAreAudited() throws Exception {
        long admin = user(), staff = user();
        identity.changeRole(staff, admin, UserAccount.Role.ADMIN);
        mvc.perform(
                        put("/api/v1/admin/users/" + staff + "/role")
                                .with(
                                        org.springframework.security.test.web.servlet.request
                                                .SecurityMockMvcRequestPostProcessors.user(
                                                        Long.toString(admin))
                                                .roles("ADMIN"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"role\":\"STAFF\"}"))
                .andDo(TestEvidence::http)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("STAFF"));
        mvc.perform(
                        get("/api/v1/admin/bookings")
                                .with(
                                        org.springframework.security.test.web.servlet.request
                                                .SecurityMockMvcRequestPostProcessors.user(
                                                        Long.toString(staff))
                                                .roles("CUSTOMER")))
                .andDo(TestEvidence::http)
                .andExpect(status().isOk());
        mvc.perform(
                        get("/api/v1/admin/audit-logs")
                                .with(
                                        org.springframework.security.test.web.servlet.request
                                                .SecurityMockMvcRequestPostProcessors.user(
                                                        Long.toString(staff))
                                                .roles("STAFF")))
                .andDo(TestEvidence::http)
                .andExpect(status().isForbidden());
        identity.changeRole(admin, staff, UserAccount.Role.CUSTOMER);
        mvc.perform(
                        get("/api/v1/admin/bookings")
                                .with(
                                        org.springframework.security.test.web.servlet.request
                                                .SecurityMockMvcRequestPostProcessors.user(
                                                        Long.toString(staff))
                                                .roles("STAFF")))
                .andDo(TestEvidence::http)
                .andExpect(status().isForbidden());
        assertThat(
                        actual(
                                audit.list(0, 100).stream()
                                        .filter(
                                                e ->
                                                        e.action().equals("CHANGE_ROLE")
                                                                && e.resourceId()
                                                                        .equals(
                                                                                Long.toString(
                                                                                        staff)))
                                        .count()))
                .isEqualTo(2);
        assertThatThrownBy(
                        expectFailure(
                                () -> identity.changeRole(admin, admin, UserAccount.Role.CUSTOMER)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    @CaseSpec(
            input = "具有数据库ADMIN角色的用户创建房型，再查询房型列表、审计和非法分页",
            expected = "创建200且价格100.00；后台查询200；审计有CREATE_TYPE；size=101返回422和INVALID_PAGINATION")
    void administratorCanMaintainCatalogAndReadAuditWithBoundedPagination() throws Exception {
        long admin = user();
        identity.changeRole(user(), admin, UserAccount.Role.ADMIN);
        mvc.perform(
                        post("/api/v1/admin/room-types")
                                .with(
                                        org.springframework.security.test.web.servlet.request
                                                .SecurityMockMvcRequestPostProcessors.user(
                                                        Long.toString(admin))
                                                .roles("ADMIN"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"name\":\"API Suite\",\"description\":\"Test\",\"capacity\":2,\"basePrice\":\"100.00\"}"))
                .andDo(TestEvidence::http)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.basePrice").value("100.00"));
        mvc.perform(
                        get("/api/v1/admin/room-types")
                                .with(
                                        org.springframework.security.test.web.servlet.request
                                                .SecurityMockMvcRequestPostProcessors.user(
                                                        Long.toString(admin))
                                                .roles("ADMIN")))
                .andDo(TestEvidence::http)
                .andExpect(status().isOk());
        mvc.perform(
                        get("/api/v1/admin/audit-logs")
                                .with(
                                        org.springframework.security.test.web.servlet.request
                                                .SecurityMockMvcRequestPostProcessors.user(
                                                        Long.toString(admin))
                                                .roles("ADMIN")))
                .andDo(TestEvidence::http)
                .andExpect(status().isOk());
        mvc.perform(
                        get("/api/v1/admin/bookings?size=101")
                                .with(
                                        org.springframework.security.test.web.servlet.request
                                                .SecurityMockMvcRequestPostProcessors.user(
                                                        Long.toString(admin))
                                                .roles("ADMIN")))
                .andDo(TestEvidence::http)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_PAGINATION"));
        assertThat(
                        actual(
                                audit.list(0, 100).stream()
                                        .anyMatch(
                                                e ->
                                                        e.actorId().equals(Long.toString(admin))
                                                                && e.action()
                                                                        .equals("CREATE_TYPE"))))
                .isTrue();
    }
}
