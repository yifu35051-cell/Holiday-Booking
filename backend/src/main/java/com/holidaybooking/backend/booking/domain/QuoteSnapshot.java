package com.holidaybooking.backend.booking.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Immutable, server-generated price breakdown. Amounts use decimal strings in stored snapshots and
 * APIs.
 */
public record QuoteSnapshot(
        LocalDate checkIn, LocalDate checkOut, String discountRatio, List<RoomPrice> rooms) {
    public record NightPrice(LocalDate date, String basePrice, String payablePrice) {}

    public record RoomPrice(
            String roomTypeId, String roomTypeName, int guests, List<NightPrice> nights) {
        public BigDecimal total() {
            return nights.stream()
                    .map(n -> new BigDecimal(n.payablePrice()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2);
        }
    }

    public BigDecimal total() {
        return rooms.stream()
                .map(RoomPrice::total)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2);
    }

    public Stay stay() {
        return new Stay(checkIn, checkOut);
    }
}
