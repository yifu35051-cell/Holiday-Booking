package com.holidaybooking.backend.booking.infrastructure;

import com.holidaybooking.backend.booking.domain.*;
import com.holidaybooking.backend.shared.domain.DomainException;
import com.holidaybooking.backend.shared.infrastructure.JdbcTime;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JdbcBookingChangeRepository implements BookingChangeRepository {
    public List<Long> ids(long booking, int offset, int size) {
        return jdbc.sql(
                        "select id from booking_change where booking_id=? order by id desc limit ? offset ?")
                .params(booking, size, offset)
                .query(Long.class)
                .list();
    }

    public long count(long booking) {
        return jdbc.sql("select count(*) from booking_change where booking_id=?")
                .param(booking)
                .query(Long.class)
                .single();
    }

    private final JdbcClient jdbc;

    public Change get(long id) {
        return jdbc.sql("select * from booking_change where id=?")
                .param(id)
                .query(
                        (rs, row) ->
                                new Change(
                                        rs.getLong("id"),
                                        rs.getLong("booking_id"),
                                        rs.getInt("revision_number"),
                                        rs.getString("kind"),
                                        rs.getString("state"),
                                        rs.getObject("quote_id", Long.class),
                                        rs.getBigDecimal("delta"),
                                        JdbcTime.read(rs, "expires_at")))
                .optional()
                .orElseThrow(DomainException::missing);
    }

    public long create(
            long booking,
            int revision,
            String kind,
            Long quote,
            BigDecimal delta,
            Instant expires,
            Instant now) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql(
                        "insert into booking_change(booking_id,revision_number,kind,state,quote_id,delta,expires_at,created_at) values(?,?,?,'QUOTED',?,?,?,?)")
                .params(
                        booking,
                        revision,
                        kind,
                        quote,
                        delta,
                        JdbcTime.write(expires),
                        JdbcTime.write(now))
                .update(keys, "id");
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    public boolean active(long booking) {
        return jdbc.sql(
                                "select count(*) from booking_change where booking_id=? and state='AWAITING_PAYMENT'")
                        .param(booking)
                        .query(Long.class)
                        .single()
                > 0;
    }

    public void state(long id, String state) {
        jdbc.sql("update booking_change set state=? where id=?").params(state, id).update();
    }

    public Long paymentOrder(long change) {
        return jdbc.sql("select id from payment_order where booking_change_id=?")
                .param(change)
                .query(Long.class)
                .optional()
                .orElse(null);
    }

    public void reserve(
            Change change, QuoteSnapshot snapshot, List<BookingRepository.AllocatedRoom> rooms) {
        for (int i = 0; i < rooms.size(); i++) {
            var room = rooms.get(i);
            jdbc.sql(
                            "insert into booking_change_room(change_id,line_number,room_id,room_number) values(?,?,?,?)")
                    .params(change.id(), i, room.roomId(), room.roomNumber())
                    .update();
            for (var date : snapshot.stay().dates()) {
                // 已锁定房型及实体房；本单交集继续 BOOKED，仅新增日期建立 HELD。
                long exists =
                        jdbc.sql("select count(*) from room_night where room_id=? and stay_date=?")
                                .params(room.roomId(), date)
                                .query(Long.class)
                                .single();
                if (exists == 0) {
                    jdbc.sql(
                                    "insert into room_night(room_id,stay_date,state,booking_id,change_id) values(?,?,'HELD',?,?)")
                            .params(room.roomId(), date, change.bookingId(), change.id())
                            .update();
                }
            }
        }
    }

    public List<BookingRepository.AllocatedRoom> allocations(long change, QuoteSnapshot snapshot) {
        return jdbc.sql("select * from booking_change_room where change_id=? order by line_number")
                .param(change)
                .query(
                        (rs, row) ->
                                new BookingRepository.AllocatedRoom(
                                        rs.getLong("room_id"),
                                        rs.getString("room_number"),
                                        snapshot.rooms().get(rs.getInt("line_number"))))
                .list();
    }

    public void applyInventory(
            Change change, QuoteSnapshot snapshot, List<BookingRepository.AllocatedRoom> rooms) {
        // 只移除本单不再使用的日期/房间，不触及其他预订和封房记录。
        jdbc.sql(
                        """
                delete from room_night where booking_id=? and
                (stay_date<? or stay_date>=? or room_id not in
                    (select room_id from booking_change_room where change_id=?))
                """)
                .params(change.bookingId(), snapshot.checkIn(), snapshot.checkOut(), change.id())
                .update();
        jdbc.sql("update room_night set state='BOOKED',change_id=null where booking_id=?")
                .param(change.bookingId())
                .update();
    }

    public void releaseAdditional(long change) {
        jdbc.sql("delete from room_night where change_id=? and state='HELD'")
                .param(change)
                .update();
    }

    public void cancelInventory(long booking) {
        jdbc.sql("delete from room_night where booking_id=?").param(booking).update();
    }

    public void suppressNotifications(long booking) {
        jdbc.sql(
                        "update notification_task set state='SKIPPED',last_error='Booking cancelled or revised' where booking_id=? and state in ('READY','RETRY','FAILED','UNKNOWN')")
                .param(booking)
                .update();
    }
}
