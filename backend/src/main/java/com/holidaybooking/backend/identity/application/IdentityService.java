package com.holidaybooking.backend.identity.application;

import com.holidaybooking.backend.identity.domain.*;
import com.holidaybooking.backend.shared.application.PageResult;
import com.holidaybooking.backend.shared.config.BookingProperties;
import com.holidaybooking.backend.shared.domain.*;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 账户注册、管理员初始化及角色维护。公开注册始终创建 CUSTOMER。 */
@Service
@RequiredArgsConstructor
@Order(0)
public class IdentityService implements ApplicationRunner {
    private final IdentityRepository users;
    private final PasswordEncoder passwords;
    private final BookingProperties settings;
    private final OperationStore operations;
    private final Clock clock;

    public record UserView(String id, String email, String displayName, String role) {}

    /** 管理员接口调用；响应不包含密码哈希。 */
    @Transactional(readOnly = true)
    public PageResult<UserView> list(int page, int size) {
        int offset = PageResult.offset(page, size);
        return new PageResult<>(
                users.list(offset, size).stream().map(IdentityService::view).toList(),
                page,
                size,
                users.count());
    }

    public static UserView view(UserAccount user) {
        return new UserView(
                user.getId().toString(),
                user.getEmail(),
                user.getDisplayName(),
                user.getRole().name());
    }

    @Transactional
    public UserView register(String email, String password, String displayName) {
        if (users.byEmail(UserAccount.normalize(email)).isPresent()) {
            throw DomainException.conflict("EMAIL_EXISTS", "该邮箱已注册");
        }
        validatePassword(password);
        return view(
                users.save(
                        new UserAccount(
                                email,
                                passwords.encode(password),
                                displayName,
                                UserAccount.Role.CUSTOMER)));
    }

    @Transactional(readOnly = true)
    public UserView me(long id) {
        return view(users.find(id).orElseThrow(DomainException::missing));
    }

    @Transactional
    public UserView changeRole(long actor, long id, UserAccount.Role role) {
        if (actor == id) {
            throw DomainException.conflict("SELF_ROLE_CHANGE", "不能修改自己的角色");
        }
        UserAccount user = users.lock(id);
        user.changeRole(role);
        operations.audit(actor, "CHANGE_ROLE", "USER", id, role.name(), clock.instant());
        return view(user);
    }

    private static void validatePassword(String password) {
        if (password == null
                || password.length() < 12
                || password.length() > 72
                || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw DomainException.invalid("PASSWORD_LENGTH", "密码需为 12–72 字符且 UTF-8 编码不超过 72 字节");
        }
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (settings.getBootstrapEmail().isBlank() && settings.getBootstrapPassword().isBlank()) {
            return;
        }
        if (settings.getBootstrapEmail().isBlank() || settings.getBootstrapPassword().isBlank()) {
            throw new IllegalStateException(
                    "BOOTSTRAP_ADMIN_EMAIL and BOOTSTRAP_ADMIN_PASSWORD must be set together");
        }
        validatePassword(settings.getBootstrapPassword());
        var existing = users.byEmail(UserAccount.normalize(settings.getBootstrapEmail()));
        if (existing.isPresent()) {
            if (existing.get().getRole() != UserAccount.Role.ADMIN) {
                throw new IllegalStateException(
                        "Bootstrap email already belongs to a non-admin account");
            }
            return;
        }
        if (users.administratorExists()) {
            throw new IllegalStateException(
                    "An administrator already exists; clear bootstrap settings instead of provisioning another account");
        }
        UserAccount admin =
                users.save(
                        new UserAccount(
                                settings.getBootstrapEmail(),
                                passwords.encode(settings.getBootstrapPassword()),
                                "Administrator",
                                UserAccount.Role.ADMIN));
        operations.audit(
                admin.getId(),
                "BOOTSTRAP_ADMIN",
                "USER",
                admin.getId(),
                "Explicit environment bootstrap",
                clock.instant());
    }
}
