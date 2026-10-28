package com.holidaybooking.backend.membership.domain;

import java.util.*;

/** 会员方案、购买单与有效权益的持久化端口。 */
public interface MembershipRepository {
    List<MembershipPurchase> purchases(long user, int offset, int size);

    long countPurchases(long user);

    List<MembershipPlan> plans();

    MembershipPlan plan(long id);

    MembershipPlan save(MembershipPlan plan);

    Optional<Membership> membership(long userId);

    Membership save(Membership membership);

    MembershipPurchase purchase(long id);

    MembershipPurchase save(MembershipPurchase purchase);
}
