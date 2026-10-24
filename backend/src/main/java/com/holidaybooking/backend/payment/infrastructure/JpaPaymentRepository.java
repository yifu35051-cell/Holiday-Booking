package com.holidaybooking.backend.payment.infrastructure;

import com.holidaybooking.backend.payment.domain.*;
import com.holidaybooking.backend.shared.domain.DomainException;
import com.holidaybooking.backend.shared.infrastructure.JdbcRows;
import com.holidaybooking.backend.shared.infrastructure.JdbcTime;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 支付持久化适配器。保存异常入账证据，支持后台人工复核与审计。 */
@Repository
@RequiredArgsConstructor
public class JpaPaymentRepository implements PaymentRepository {
    @PersistenceContext private EntityManager entityManager;
    private final JdbcClient jdbc;

    public PaymentOrder order(long id) {
        var payment = entityManager.find(PaymentOrder.class, id);
        if (payment == null) {
            throw DomainException.missing();
        }
        return payment;
    }

    public PaymentOrder save(PaymentOrder payment) {
        entityManager.persist(payment);
        entityManager.flush();
        return payment;
    }

    public PaymentOrder forBooking(long id) {
        return entityManager
                .createQuery("from PaymentOrder where bookingId=:id", PaymentOrder.class)
                .setParameter("id", id)
                .getSingleResult();
    }

    public PaymentOrder forPurchase(long id) {
        return entityManager
                .createQuery("from PaymentOrder where membershipPurchaseId=:id", PaymentOrder.class)
                .setParameter("id", id)
                .getSingleResult();
    }

    public PaymentAttempt attempt(long id) {
        var payment = entityManager.find(PaymentAttempt.class, id);
        if (payment == null) {
            throw DomainException.missing();
        }
        return payment;
    }

    public PaymentAttempt byOutTradeNo(String no) {
        return entityManager
                .createQuery("from PaymentAttempt where outTradeNo=:no", PaymentAttempt.class)
                .setParameter("no", no)
                .getResultStream()
                .findFirst()
                .orElseThrow(DomainException::missing);
    }

    public PaymentAttempt save(PaymentAttempt payment) {
        entityManager.persist(payment);
        entityManager.flush();
        return payment;
    }

    public List<PaymentAttempt> attempts(long order) {
        return entityManager
                .createQuery(
                        "from PaymentAttempt where paymentOrderId=:id order by id desc",
                        PaymentAttempt.class)
                .setParameter("id", order)
                .getResultList();
    }

    public List<Long> pendingOrders(long afterId, int limit) {
        return entityManager
                .createQuery(
                        "select id from PaymentOrder where status=:status and id>:after order by"
                                + " id",
                        Long.class)
                .setParameter("status", PaymentOrder.Status.PENDING)
                .setParameter("after", afterId)
                .setMaxResults(limit)
                .getResultList();
    }

    public void exception(
            long order, long attempt, String reason, PaymentGateway.Result received, Instant now) {
        if (jdbc.sql("select count(*) from payment_exception where attempt_id=?")
                        .param(attempt)
                        .query(Long.class)
                        .single()
                == 0) {
            jdbc.sql(
                            "insert into"
                                    + " payment_exception(payment_order_id,attempt_id,reason,received_amount,status,created_at)"
                                    + " values(?,?,?,?,'OPEN',?)")
                    .params(order, attempt, reason, received.amount(), JdbcTime.write(now))
                    .update();
        }
    }

    public List<Map<String, Object>> exceptions(int offset, int size) {
        return jdbc
                .sql("select * from payment_exception order by id desc limit ? offset ?")
                .params(size, offset)
                .query()
                .listOfRows()
                .stream()
                .map(JdbcRows::publicValues)
                .toList();
    }

    public void resolveException(long id, long actor, String note, Instant now) {
        int changed =
                jdbc.sql(
                                "update payment_exception set status='REVIEWED', reviewed_by=?,"
                                        + " review_note=?, reviewed_at=? where id=? and status='OPEN'")
                        .params(actor, note, JdbcTime.write(now), id)
                        .update();
        if (changed == 0) {
            throw DomainException.conflict("EXCEPTION_STATE", "异常不存在或已经处理");
        }
    }
}
