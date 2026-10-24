package com.holidaybooking.backend.identity.infrastructure;

import com.holidaybooking.backend.identity.domain.*;
import com.holidaybooking.backend.shared.domain.DomainException;
import jakarta.persistence.*;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 账户持久化适配器；所有数据库访问限制在基础设施层。 */
@Repository
public class JpaIdentityRepository implements IdentityRepository {
    @PersistenceContext private EntityManager entityManager;

    public Optional<UserAccount> find(long id) {
        return Optional.ofNullable(entityManager.find(UserAccount.class, id));
    }

    public Optional<UserAccount> byEmail(String email) {
        return entityManager
                .createQuery("from UserAccount where email = :email", UserAccount.class)
                .setParameter("email", email)
                .getResultStream()
                .findFirst();
    }

    public UserAccount lock(long id) {
        UserAccount user =
                entityManager.find(UserAccount.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (user == null) {
            throw DomainException.missing();
        }
        return user;
    }

    public UserAccount save(UserAccount account) {
        entityManager.persist(account);
        entityManager.flush();
        return account;
    }
}
