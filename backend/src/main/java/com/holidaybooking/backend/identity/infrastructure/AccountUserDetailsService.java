package com.holidaybooking.backend.identity.infrastructure;

import com.holidaybooking.backend.identity.domain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 将数据库账户转换为 Spring Security 身份；会话中的用户名使用稳定的用户 ID。 */
@Service
@RequiredArgsConstructor
public class AccountUserDetailsService implements UserDetailsService {
    private final IdentityRepository users;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) {
        UserAccount user =
                users.byEmail(UserAccount.normalize(email))
                        .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
        // Store only a scalar ID in the session; never serialize a JPA entity.
        return User.withUsername(user.getId().toString())
                .password(user.getPasswordHash())
                .roles(user.getRole().name())
                .disabled(!user.isEnabled())
                .build();
    }
}
