package com.holidaybooking.backend.membership.infrastructure;

import com.holidaybooking.backend.membership.domain.*;
import com.holidaybooking.backend.shared.domain.DomainException;
import jakarta.persistence.*;
import java.util.*;
import org.springframework.stereotype.Repository;

/** 会员领域的 JPA 适配器；账户锁由应用层持有，避免并发续期丢失有效期。 */
@Repository
public class JpaMembershipRepository implements MembershipRepository {
    @PersistenceContext private EntityManager entityManager;

    public List<MembershipPurchase> purchases(long user, int offset, int size) {
        return entityManager
                .createQuery(
                        "from MembershipPurchase where userId=:user order by id desc",
                        MembershipPurchase.class)
                .setParameter("user", user)
                .setFirstResult(offset)
                .setMaxResults(size)
                .getResultList();
    }

    public long countPurchases(long user) {
        return entityManager
                .createQuery(
                        "select count(p) from MembershipPurchase p where userId=:user", Long.class)
                .setParameter("user", user)
                .getSingleResult();
    }

    public List<MembershipPlan> plans() {
        return entityManager
                .createQuery(
                        "from MembershipPlan where active=true order by id", MembershipPlan.class)
                .getResultList();
    }

    public MembershipPlan plan(long id) {
        var plan = entityManager.find(MembershipPlan.class, id);
        if (plan == null) {
            throw DomainException.missing();
        }
        return plan;
    }

    public MembershipPlan save(MembershipPlan plan) {
        entityManager.persist(plan);
        entityManager.flush();
        return plan;
    }

    public Optional<Membership> membership(long userId) {
        return entityManager
                .createQuery("from Membership where userId=:user", Membership.class)
                .setParameter("user", userId)
                .getResultStream()
                .findFirst();
    }

    public Membership save(Membership membership) {
        entityManager.persist(membership);
        entityManager.flush();
        return membership;
    }

    public MembershipPurchase purchase(long id) {
        var purchase = entityManager.find(MembershipPurchase.class, id);
        if (purchase == null) {
            throw DomainException.missing();
        }
        return purchase;
    }

    public MembershipPurchase save(MembershipPurchase purchase) {
        entityManager.persist(purchase);
        entityManager.flush();
        return purchase;
    }
}
