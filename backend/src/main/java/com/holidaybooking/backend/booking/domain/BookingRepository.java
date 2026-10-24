package com.holidaybooking.backend.booking.domain;

import java.time.LocalDate;
import java.util.List;

/** 报价、预订及不可变版本的持久化端口，避免应用层直接操作 SQL。 */
public interface BookingRepository {
    BookingQuote quote(long id);

    BookingQuote save(BookingQuote quote);

    Booking booking(long id);

    Booking save(Booking booking);

    record AllocatedRoom(long roomId, String roomNumber, QuoteSnapshot.RoomPrice price) {}

    record BookingRoom(String roomId, String roomNumber, QuoteSnapshot.RoomPrice price) {}

    void revision(Booking booking, QuoteSnapshot snapshot, List<AllocatedRoom> rooms);

    List<BookingRoom> rooms(long bookingId);

    List<Booking> list(
            Long userId, Booking.Status status, LocalDate from, LocalDate to, int offset, int size);

    long count(Long userId, Booking.Status status, LocalDate from, LocalDate to);
}
