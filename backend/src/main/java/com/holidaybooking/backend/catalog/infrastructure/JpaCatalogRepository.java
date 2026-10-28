package com.holidaybooking.backend.catalog.infrastructure;

import com.holidaybooking.backend.catalog.domain.*;
import com.holidaybooking.backend.shared.domain.DomainException;
import com.holidaybooking.backend.shared.infrastructure.JdbcRows;
import com.holidaybooking.backend.shared.infrastructure.JdbcTime;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** 房型与库存适配器：JPA 管理实体，JDBC 维护日期价格和房间每晚占用。 */
@Repository
@RequiredArgsConstructor
public class JpaCatalogRepository implements CatalogRepository {
    @PersistenceContext private EntityManager entityManager;
    private final JdbcClient jdbc;

    public List<RoomType> types(boolean activeOnly) {
        return entityManager
                .createQuery(
                        "from RoomType" + (activeOnly ? " where active=true" : "") + " order by id",
                        RoomType.class)
                .getResultList();
    }

    public RoomType type(long id, boolean lock) {
        RoomType result =
                lock
                        ? entityManager.find(RoomType.class, id, LockModeType.PESSIMISTIC_WRITE)
                        : entityManager.find(RoomType.class, id);
        if (result == null) {
            throw DomainException.missing();
        }
        return result;
    }

    public RoomType save(RoomType type) {
        entityManager.persist(type);
        entityManager.flush();
        return type;
    }

    public Room room(long id, boolean lock) {
        Room result =
                lock
                        ? entityManager.find(Room.class, id, LockModeType.PESSIMISTIC_WRITE)
                        : entityManager.find(Room.class, id);
        if (result == null) {
            throw DomainException.missing();
        }
        return result;
    }

    public Room save(Room room) {
        entityManager.persist(room);
        entityManager.flush();
        return room;
    }

    public List<Room> rooms(long typeId, boolean lock) {
        var query =
                entityManager
                        .createQuery("from Room where roomTypeId=:type order by id", Room.class)
                        .setParameter("type", typeId);
        if (lock) {
            query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
        }
        return query.getResultList();
    }

    public Map<LocalDate, BigDecimal> rates(long typeId, LocalDate start, LocalDate end) {
        Map<LocalDate, BigDecimal> result = new HashMap<>();
        jdbc.sql(
                        "select stay_date, price from room_rate where room_type_id=? and"
                                + " stay_date>=? and stay_date<?")
                .params(typeId, start, end)
                .query(
                        (rs, n) -> {
                            result.put(rs.getDate(1).toLocalDate(), rs.getBigDecimal(2));
                            return 0;
                        })
                .list();
        return result;
    }

    public void rate(long typeId, LocalDate date, BigDecimal price) {
        jdbc.sql("delete from room_rate where room_type_id=? and stay_date=?")
                .params(typeId, date)
                .update();
        jdbc.sql("insert into room_rate(room_type_id,stay_date,price) values(?,?,?)")
                .params(typeId, date, price)
                .update();
    }

    public List<Long> available(long typeId, LocalDate start, LocalDate end) {
        return jdbc.sql(
                        """
                        select r.id from room r where r.room_type_id=? and r.active=true
                        and not exists(select 1 from room_night n where n.room_id=r.id and n.stay_date>=? and n.stay_date<?)
                        order by r.id
                        """)
                .params(typeId, start, end)
                .query(Long.class)
                .list();
    }

    public boolean hasFutureOccupancy(long roomId, LocalDate today) {
        return jdbc.sql("select count(*) from room_night where room_id=? and stay_date>=?")
                        .params(roomId, today)
                        .query(Long.class)
                        .single()
                > 0;
    }

    public List<Long> availableForChange(
            long typeId, LocalDate start, LocalDate end, long bookingId) {
        return jdbc.sql(
                        """
                select r.id from room r where r.room_type_id=? and r.active=true
                and not exists(select 1 from room_night n where n.room_id=r.id
                    and n.stay_date>=? and n.stay_date<?
                    and (n.booking_id is null or n.booking_id<>? or n.state<>'BOOKED'))
                order by r.id
                """)
                .params(typeId, start, end, bookingId)
                .query(Long.class)
                .list();
    }

    public void hold(long roomId, List<LocalDate> dates, long bookingId) {
        for (LocalDate date : dates) {
            jdbc.sql(
                            "insert into room_night(room_id,stay_date,state,booking_id)"
                                    + " values(?,?,'HELD',?)")
                    .params(roomId, date, bookingId)
                    .update();
        }
    }

    public void confirm(long bookingId) {
        jdbc.sql("update room_night set state='BOOKED' where booking_id=? and state='HELD'")
                .param(bookingId)
                .update();
    }

    public void release(long bookingId) {
        jdbc.sql("delete from room_night where booking_id=? and state='HELD'")
                .param(bookingId)
                .update();
    }

    public long block(
            long roomId, LocalDate start, LocalDate end, String reason, long actor, Instant now) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql(
                        "insert into"
                                + " room_block(room_id,start_date,end_date,reason,created_by,created_at)"
                                + " values(?,?,?,?,?,?)")
                .params(roomId, start, end, reason, actor, JdbcTime.write(now))
                .update(keys, "id");
        long id = Objects.requireNonNull(keys.getKey()).longValue();
        start.datesUntil(end)
                .forEach(
                        date ->
                                jdbc.sql(
                                                "insert into"
                                                        + " room_night(room_id,stay_date,state,block_id)"
                                                        + " values(?,?,'BLOCKED',?)")
                                        .params(roomId, date, id)
                                        .update());
        return id;
    }

    public void unblock(long id) {
        Long roomId =
                jdbc.sql("select room_id from room_block where id=?")
                        .param(id)
                        .query(Long.class)
                        .optional()
                        .orElseThrow(DomainException::missing);
        Room snapshot = room(roomId, false);
        type(snapshot.getRoomTypeId(), true);
        room(roomId, true);
        jdbc.sql("delete from room_night where block_id=?").param(id).update();
        jdbc.sql("update room_block set released=true where id=?").param(id).update();
    }

    public List<Map<String, Object>> blocks(long roomId) {
        return jdbc
                .sql(
                        "select id, start_date, end_date, reason, released from room_block where"
                                + " room_id=? order by id desc")
                .param(roomId)
                .query()
                .listOfRows()
                .stream()
                .map(JdbcRows::publicValues)
                .toList();
    }
}
