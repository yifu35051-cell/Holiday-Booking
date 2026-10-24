package com.holidaybooking.backend.membership.application;

import com.holidaybooking.backend.identity.domain.IdentityRepository;
import com.holidaybooking.backend.membership.domain.*;
import com.holidaybooking.backend.payment.application.PaymentLedger;
import com.holidaybooking.backend.payment.domain.PaymentRepository;
import com.holidaybooking.backend.shared.application.RequestFingerprint;
import com.holidaybooking.backend.shared.config.BookingProperties;
import com.holidaybooking.backend.shared.domain.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 会员购买与权益激活用例。仅由核验成功的支付流程激活权益。 */
@Service
@RequiredArgsConstructor
public class MembershipService {
    private final MembershipRepository memberships;
    private final IdentityRepository users;
    private final PaymentLedger ledger;
    private final PaymentRepository payments;
    private final OperationStore operations;
    private final BookingProperties settings;
    private final Clock clock;

    public record PlanView(
            String id, String name, String fee, String discountRatio, int durationDays) {}

    public record MemberView(boolean active, String discountRatio, Instant validUntil) {}

    public record PurchaseView(
            String id,
            String status,
            String fee,
            String discountRatio,
            int durationDays,
            Instant expiresAt,
            String paymentOrderId) {}

    private static PlanView view(MembershipPlan plan) {
        return new PlanView(
                plan.getId().toString(),
                plan.getName(),
                plan.getFee().toPlainString(),
                plan.getDiscountRatio().toPlainString(),
                plan.getDurationDays());
    }

    @Transactional(readOnly = true)
    public List<PlanView> plans() {
        return memberships.plans().stream().map(MembershipService::view).toList();
    }

    @Transactional(readOnly = true)
    public MemberView me(long user) {
        return memberships
                .membership(user)
                .map(
                        membership ->
                                new MemberView(
                                        membership.active(clock.instant()),
                                        membership.active(clock.instant())
                                                ? membership.getDiscountRatio().toPlainString()
                                                : "1.0000",
                                        membership.getValidUntil()))
                .orElse(new MemberView(false, "1.0000", null));
    }

    @Transactional(readOnly = true)
    public BigDecimal ratio(long user) {
        return memberships
                .membership(user)
                .filter(membership -> membership.active(clock.instant()))
                .map(Membership::getDiscountRatio)
                .orElse(BigDecimal.ONE.setScale(4));
    }

    @Transactional
    public PurchaseView purchase(long user, long requestedPlan, String key) {
        String hash = RequestFingerprint.hash(key, Long.toString(requestedPlan));
        users.lock(user);
        Long prior = operations.replay(user, "MEMBERSHIP_PURCHASE", key, hash);
        if (prior != null) {
            return purchaseView(memberships.purchase(prior));
        }
        // Renew an active membership on its original immutable plan; a lapsed member selects
        // today's plan.
        var member = memberships.membership(user);
        var plan =
                member.filter(membership -> membership.active(clock.instant()))
                        .map(membership -> memberships.plan(membership.getPlanId()))
                        .orElseGet(() -> memberships.plan(requestedPlan));
        if (member.filter(membership -> membership.active(clock.instant())).isEmpty()
                && !plan.isActive()) {
            throw DomainException.conflict("PLAN_RETIRED", "会员方案已停用");
        }
        var purchase =
                memberships.save(
                        new MembershipPurchase(
                                user, plan, clock.instant().plus(settings.getHoldTtl())));
        ledger.create(user, null, purchase.getId(), purchase.getFee(), purchase.getExpiresAt());
        operations.remember(
                user, "MEMBERSHIP_PURCHASE", key, hash, purchase.getId(), clock.instant());
        return purchaseView(purchase);
    }

    @Transactional(readOnly = true)
    public PurchaseView purchase(long user, long id) {
        var purchase = memberships.purchase(id);
        if (purchase.getUserId() != user) {
            throw DomainException.missing();
        }
        return purchaseView(purchase);
    }

    private PurchaseView purchaseView(MembershipPurchase purchase) {
        return new PurchaseView(
                purchase.getId().toString(),
                purchase.getStatus().name(),
                purchase.getFee().toPlainString(),
                purchase.getDiscountRatio().toPlainString(),
                purchase.getDurationDays(),
                purchase.getExpiresAt(),
                payments.forPurchase(purchase.getId()).getId().toString());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void activate(long purchaseId) {
        var purchase = memberships.purchase(purchaseId);
        purchase.activate();
        var membership =
                memberships
                        .membership(purchase.getUserId())
                        .orElseGet(() -> new Membership(purchase.getUserId()));
        membership.activate(purchase, clock.instant());
        if (membership.getId() == null) {
            memberships.save(membership);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void expire(long id) {
        memberships.purchase(id).expire();
    }

    @Transactional
    public PlanView createPlan(
            long actor, String name, BigDecimal fee, BigDecimal ratio, int days) {
        var plan = memberships.save(new MembershipPlan(name, fee, ratio, days));
        operations.audit(
                actor, "CREATE_PLAN", "MEMBERSHIP_PLAN", plan.getId(), "", clock.instant());
        return view(plan);
    }

    @Transactional
    public void retirePlan(long actor, long id) {
        memberships.plan(id).retire();
        operations.audit(actor, "RETIRE_PLAN", "MEMBERSHIP_PLAN", id, "", clock.instant());
    }
}
