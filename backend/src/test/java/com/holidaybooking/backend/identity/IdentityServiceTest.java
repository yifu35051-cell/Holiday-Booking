package com.holidaybooking.backend.identity;

import static com.holidaybooking.backend.support.TestEvidence.*;
import static org.assertj.core.api.Assertions.*;

import com.holidaybooking.backend.identity.domain.IdentityRepository;
import com.holidaybooking.backend.shared.config.BookingProperties;
import com.holidaybooking.backend.shared.domain.DomainException;
import com.holidaybooking.backend.support.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 账户数据规则及显式初始化；权限过滤链由 ApiSecurityTest 验证。 */
@Tag("core")
class IdentityServiceTest extends BusinessTestSupport {
    @Autowired IdentityRepository accounts;
    @Autowired PasswordEncoder encoder;
    @Autowired BookingProperties settings;

    @Test
    @CaseSpec(
            input = "大小写混合邮箱、合法测试密码、重复注册和短密码",
            expected = "邮箱归一化、角色 CUSTOMER、密码为 BCrypt 且可匹配；重复邮箱和短密码被拒绝")
    void registrationNormalizesEmailHashesPasswordAndRejectsInvalidAccounts() {
        String email = "Guest-" + UUID.randomUUID() + "@EXAMPLE.COM";
        var registered = identity.register(email, "test-password-123", "Guest");
        var stored = accounts.find(Long.parseLong(registered.id())).orElseThrow();
        assertThat(actual(registered.email())).isEqualTo(email.toLowerCase());
        assertThat(actual(registered.role())).isEqualTo("CUSTOMER");
        assertThat(actual(encoder.matches("test-password-123", stored.getPasswordHash()))).isTrue();
        assertThat(actual(stored.getPasswordHash().startsWith("$2"))).isTrue();
        assertThatThrownBy(
                        expectFailure(
                                () ->
                                        identity.register(
                                                email.toLowerCase(), "test-password-123", "Other")))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(
                        expectFailure(
                                () ->
                                        identity.register(
                                                UUID.randomUUID() + "@example.com",
                                                "short",
                                                "Guest")))
                .isInstanceOf(DomainException.class);
    }

    @Test
    @CaseSpec(
            input = "新的 bootstrap 邮箱和合法密码，连续初始化两次，再将邮箱设为现有普通用户",
            expected = "仅创建一个 ADMIN；重复执行不重设密码；拒绝将已有普通账户提升为管理员")
    void administratorBootstrapIsExplicitAndDoesNotPromoteExistingCustomers() {
        String oldEmail = settings.getBootstrapEmail();
        String oldPassword = settings.getBootstrapPassword();
        String email = UUID.randomUUID() + "@example.com";
        try {
            settings.setBootstrapEmail(email);
            settings.setBootstrapPassword("bootstrap-test-123");
            identity.run(new DefaultApplicationArguments());
            settings.setBootstrapPassword("different-test-456");
            identity.run(new DefaultApplicationArguments());
            // Repository 的流式查询需要事务；此处直接读取持久化结果进行核验。
            assertThat(
                            actual(
                                    jdbc.sql("select role from user_account where email=?")
                                            .param(email)
                                            .query(String.class)
                                            .single()))
                    .isEqualTo("ADMIN");
            String storedHash =
                    jdbc.sql("select password_hash from user_account where email=?")
                            .param(email)
                            .query(String.class)
                            .single();
            assertThat(actual(encoder.matches("bootstrap-test-123", storedHash))).isTrue();
            assertThat(
                            actual(
                                    jdbc.sql("select count(*) from user_account where email=?")
                                            .param(email)
                                            .query(Long.class)
                                            .single()))
                    .isEqualTo(1);
            long customer = user();
            settings.setBootstrapEmail(identity.me(customer).email());
            assertThatThrownBy(expectFailure(() -> identity.run(new DefaultApplicationArguments())))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(actual(identity.me(customer).role())).isEqualTo("CUSTOMER");
        } finally {
            settings.setBootstrapEmail(oldEmail);
            settings.setBootstrapPassword(oldPassword);
        }
    }
}
