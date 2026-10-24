package com.holidaybooking.backend.identity.domain;

import com.holidaybooking.backend.shared.domain.BaseEntity;
import jakarta.persistence.*;
import java.util.Locale;
import lombok.*;

/** 账户身份与管理角色。付费会员权益单独存储，注册时不接受客户端指定角色。 */
@Getter
@Entity
@Table(name = "user_account")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAccount extends BaseEntity {
    public enum Role {
        CUSTOMER,
        STAFF,
        ADMIN
    }

    @Column(nullable = false, unique = true, length = 254)
    private String email;

    @Column(nullable = false, length = 100)
    private String passwordHash;

    @Column(nullable = false, length = 80)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(nullable = false)
    private boolean enabled = true;

    public UserAccount(String email, String passwordHash, String displayName, Role role) {
        this.email = normalize(email);
        this.passwordHash = passwordHash;
        this.displayName = displayName.strip();
        this.role = role;
    }

    public static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    public void changeRole(Role role) {
        this.role = role;
    }
}
