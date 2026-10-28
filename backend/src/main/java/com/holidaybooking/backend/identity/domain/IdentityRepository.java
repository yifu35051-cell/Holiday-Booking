package com.holidaybooking.backend.identity.domain;

import java.util.Optional;

/** 账户持久化端口。lock 用于串行化同一用户的预订、会员和支付写操作。 */
public interface IdentityRepository {
    java.util.List<UserAccount> list(int offset, int size);

    long count();

    boolean administratorExists();

    Optional<UserAccount> find(long id);

    Optional<UserAccount> byEmail(String email);

    UserAccount lock(long id);

    UserAccount save(UserAccount account);
}
