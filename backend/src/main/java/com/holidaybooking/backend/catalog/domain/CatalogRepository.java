package com.holidaybooking.backend.catalog.domain;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** 房型、房间与逐晚库存的持久化端口。库存写入前须按房型、房间 ID 顺序加锁。 */
public interface CatalogRepository {
    List<RoomType> types(boolean activeOnly);

    RoomType type(long id, boolean lock);

    RoomType save(RoomType type);

    Room room(long id, boolean lock);

    Room save(Room room);

    List<Room> rooms(long typeId, boolean lock);

    Map<LocalDate, BigDecimal> rates(long typeId, LocalDate start, LocalDate end);

    void rate(long typeId, LocalDate date, BigDecimal price);

    List<Long> available(long typeId, LocalDate start, LocalDate end);

    boolean hasFutureOccupancy(long roomId, LocalDate today);

    void hold(long roomId, List<LocalDate> dates, long bookingId);

    void confirm(long bookingId);

    void release(long bookingId);

    long block(long roomId, LocalDate start, LocalDate end, String reason, long actor, Instant now);

    void unblock(long blockId);

    List<Map<String, Object>> blocks(long roomId);
}
