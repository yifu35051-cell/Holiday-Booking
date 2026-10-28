package com.holidaybooking.backend.booking.infrastructure;

import com.holidaybooking.backend.booking.domain.*;
import com.holidaybooking.backend.shared.domain.DomainException;
import com.holidaybooking.backend.shared.infrastructure.JdbcTime;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** 预订持久化适配器；显式存储版本、实体房间和逐晚价格，保留历史计价依据。 */
@Repository
@RequiredArgsConstructor
public class JpaBookingRepository implements BookingRepository {
    @PersistenceContext private EntityManager entityManager;
    private final JdbcClient jdbc;
    private final Clock clock;

    public BookingQuote quote(long id) {
        var quote = entityManager.find(BookingQuote.class, id);
        if (quote == null) {
            throw DomainException.missing();
        }
        return quote;
    }

    public BookingQuote save(BookingQuote quote) {
        entityManager.persist(quote);
        entityManager.flush();
        return quote;
    }

    public Booking booking(long id) {
        var booking = entityManager.find(Booking.class, id);
        if (booking == null) {
            throw DomainException.missing();
        }
        return booking;
    }

    public Booking save(Booking booking) {
        entityManager.persist(booking);
        entityManager.flush();
        return booking;
    }

    public void revision(Booking booking, QuoteSnapshot snapshot, List<AllocatedRoom> rooms) {
        var revisionKey = new GeneratedKeyHolder();
        jdbc.sql(
                        "insert into"
                                + " booking_revision(booking_id,revision_number,discount_ratio,total_amount,created_at)"
                                + " values(?,?,?,?,?)")
                .params(
                        booking.getId(),
                        booking.getCurrentRevision(),
                        new BigDecimal(snapshot.discountRatio()),
                        snapshot.total(),
                        JdbcTime.write(clock.instant()))
                .update(revisionKey, "id");
        long revision = Objects.requireNonNull(revisionKey.getKey()).longValue();
        for (var room : rooms) {
            var roomKey = new GeneratedKeyHolder();
            jdbc.sql(
                            "insert into"
                                    + " booking_room(revision_id,room_id,room_number,room_type_id,room_type_name,guests,total_amount)"
                                    + " values(?,?,?,?,?,?,?)")
                    .params(
                            revision,
                            room.roomId(),
                            room.roomNumber(),
                            Long.parseLong(room.price().roomTypeId()),
                            room.price().roomTypeName(),
                            room.price().guests(),
                            room.price().total())
                    .update(roomKey, "id");
            long line = Objects.requireNonNull(roomKey.getKey()).longValue();
            for (var night : room.price().nights()) {
                jdbc.sql(
                                "insert into"
                                        + " booking_nightly_price(booking_room_id,stay_date,base_price,payable_price)"
                                        + " values(?,?,?,?)")
                        .params(
                                line,
                                night.date(),
                                new BigDecimal(night.basePrice()),
                                new BigDecimal(night.payablePrice()))
                        .update();
            }
        }
    }

    public List<BookingRoom> rooms(long bookingId) {
        return jdbc.sql(
                        """
                        select br.* from booking_room br join booking_revision rev on br.revision_id=rev.id
                        join booking b on rev.booking_id=b.id and rev.revision_number=b.current_revision where b.id=? order by br.id
                        """)
                .param(bookingId)
                .query(
                        (rs, n) -> {
                            long line = rs.getLong("id");
                            var nights =
                                    jdbc.sql(
                                                    "select * from booking_nightly_price where"
                                                            + " booking_room_id=? order by stay_date")
                                            .param(line)
                                            .query(
                                                    (nightRow, i) ->
                                                            new QuoteSnapshot.NightPrice(
                                                                    nightRow.getDate("stay_date")
                                                                            .toLocalDate(),
                                                                    nightRow.getBigDecimal(
                                                                                    "base_price")
                                                                            .toPlainString(),
                                                                    nightRow.getBigDecimal(
                                                                                    "payable_price")
                                                                            .toPlainString()))
                                            .list();
                            return new BookingRoom(
                                    Long.toString(rs.getLong("room_id")),
                                    rs.getString("room_number"),
                                    new QuoteSnapshot.RoomPrice(
                                            Long.toString(rs.getLong("room_type_id")),
                                            rs.getString("room_type_name"),
                                            rs.getInt("guests"),
                                            nights));
                        })
                .list();
    }

    private String predicate(Long user, Booking.Status status, LocalDate from, LocalDate to) {
        return " where 1=1"
                + (user != null ? " and userId=:user" : "")
                + (status != null ? " and status=:status" : "")
                + (from != null ? " and checkIn>=:from" : "")
                + (to != null ? " and checkIn<=:to" : "");
    }

    private <T> TypedQuery<T> bind(
            TypedQuery<T> query, Long user, Booking.Status status, LocalDate from, LocalDate to) {
        if (user != null) {
            query.setParameter("user", user);
        }
        if (status != null) {
            query.setParameter("status", status);
        }
        if (from != null) {
            query.setParameter("from", from);
        }
        if (to != null) {
            query.setParameter("to", to);
        }
        return query;
    }

    public List<Booking> list(
            Long user, Booking.Status status, LocalDate from, LocalDate to, int offset, int size) {
        return bind(
                        entityManager.createQuery(
                                "from Booking"
                                        + predicate(user, status, from, to)
                                        + " order by id desc",
                                Booking.class),
                        user,
                        status,
                        from,
                        to)
                .setFirstResult(offset)
                .setMaxResults(size)
                .getResultList();
    }

    public long count(Long user, Booking.Status status, LocalDate from, LocalDate to) {
        return bind(
                        entityManager.createQuery(
                                "select count(id) from Booking" + predicate(user, status, from, to),
                                Long.class),
                        user,
                        status,
                        from,
                        to)
                .getSingleResult();
    }
}
