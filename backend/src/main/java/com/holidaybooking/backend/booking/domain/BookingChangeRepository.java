package com.holidaybooking.backend.booking.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 变更报价和库存差集持久化端口；调用方必须先持有预订所有者行锁。 */
public interface BookingChangeRepository {
    record Change(
            long id,
            long bookingId,
            int revision,
            String kind,
            String state,
            Long quoteId,
            BigDecimal delta,
            Instant expiresAt) {}

    Change get(long id);

    List<Long> ids(long booking, int offset, int size);

    long count(long booking);

    long create(
            long booking,
            int revision,
            String kind,
            Long quote,
            BigDecimal delta,
            Instant expires,
            Instant now);

    boolean active(long booking);

    void state(long id, String state);

    Long paymentOrder(long change);

    void reserve(
            Change change, QuoteSnapshot snapshot, List<BookingRepository.AllocatedRoom> rooms);

    List<BookingRepository.AllocatedRoom> allocations(long change, QuoteSnapshot snapshot);

    void applyInventory(
            Change change, QuoteSnapshot snapshot, List<BookingRepository.AllocatedRoom> rooms);

    void releaseAdditional(long change);

    void cancelInventory(long booking);

    void suppressNotifications(long booking);
}
