package com.holidaybooking.backend.catalog.application;

import com.holidaybooking.backend.booking.domain.Stay;
import com.holidaybooking.backend.catalog.domain.*;
import com.holidaybooking.backend.shared.config.BookingProperties;
import com.holidaybooking.backend.shared.domain.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 客房查询与维护用例。检查连续可用性，并保护已有预订不受停用或容量调整影响。 */
@Service
@RequiredArgsConstructor
public class CatalogService {
    private final CatalogRepository catalog;
    private final BookingProperties settings;
    private final OperationStore operations;
    private final Clock clock;

    public record TypeView(
            String id,
            String name,
            String description,
            int capacity,
            String basePrice,
            boolean active) {}

    public record RoomView(String id, String roomTypeId, String number, boolean active) {}

    public record Availability(
            TypeView roomType, int availableRooms, int nights, String totalPerRoom) {}

    public static TypeView view(RoomType roomType) {
        return new TypeView(
                roomType.getId().toString(),
                roomType.getName(),
                roomType.getDescription(),
                roomType.getCapacity(),
                roomType.getBasePrice().toPlainString(),
                roomType.isActive());
    }

    public static RoomView view(Room room) {
        return new RoomView(
                room.getId().toString(),
                room.getRoomTypeId().toString(),
                room.getNumber(),
                room.isActive());
    }

    @Transactional(readOnly = true)
    public List<TypeView> types(boolean all) {
        return catalog.types(!all).stream().map(CatalogService::view).toList();
    }

    @Transactional(readOnly = true)
    public TypeView detail(long id) {
        var roomType = catalog.type(id, false);
        if (!roomType.isActive()) {
            throw DomainException.missing();
        }
        return view(roomType);
    }

    public void validate(Stay stay) {
        stay.validate(
                clock,
                settings.getZone(),
                settings.getCheckInTime(),
                settings.getMaxNights(),
                settings.getAdvanceDays());
    }

    @Transactional(readOnly = true)
    public List<Availability> availability(Stay stay, int guests, int rooms) {
        validate(stay);
        if (guests < 1 || rooms < 1 || rooms > settings.getMaxRooms()) {
            throw DomainException.invalid("INVALID_PARTY", "人数或间数不合法");
        }
        return catalog.types(true).stream()
                .filter(roomType -> roomType.getCapacity() >= guests)
                .map(
                        roomType -> {
                            int count =
                                    catalog.available(
                                                    roomType.getId(),
                                                    stay.checkIn(),
                                                    stay.checkOut())
                                            .size();
                            var rates =
                                    catalog.rates(
                                            roomType.getId(), stay.checkIn(), stay.checkOut());
                            BigDecimal total =
                                    stay.dates().stream()
                                            .map(
                                                    d ->
                                                            rates.getOrDefault(
                                                                    d, roomType.getBasePrice()))
                                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                            return new Availability(
                                    view(roomType),
                                    count,
                                    stay.nights(),
                                    total.setScale(2).toPlainString());
                        })
                .filter(availability -> availability.availableRooms() >= rooms)
                .toList();
    }

    @Transactional
    public TypeView createType(
            long actor, String name, String description, int capacity, BigDecimal price) {
        var roomType = catalog.save(new RoomType(name, description, capacity, price));
        audit(actor, "CREATE_TYPE", "ROOM_TYPE", roomType.getId());
        return view(roomType);
    }

    @Transactional
    public TypeView updateType(
            long actor,
            long id,
            String name,
            String description,
            int capacity,
            BigDecimal price,
            boolean active) {
        var roomType = catalog.type(id, true);
        if (capacity < roomType.getCapacity() || !active) {
            for (var room : catalog.rooms(id, true)) {
                if (catalog.hasFutureOccupancy(room.getId(), today())) {
                    throw DomainException.conflict("OCCUPIED", "有未来占用，不能减少容量或停用房型");
                }
            }
        }
        roomType.revise(name, description, capacity, price);
        roomType.setActive(active);
        audit(actor, "UPDATE_TYPE", "ROOM_TYPE", id);
        return view(roomType);
    }

    @Transactional
    public void rates(long actor, long id, Stay range, BigDecimal price) {
        if (range.nights() > 366 || range.checkIn().isBefore(today())) {
            throw DomainException.invalid("RATE_RANGE", "价格日期范围无效");
        }
        catalog.type(id, true);
        Money amount = new Money(price);
        if (amount.amount().signum() == 0) {
            throw DomainException.invalid("PRICE", "价格必须大于零");
        }
        range.dates().forEach(d -> catalog.rate(id, d, amount.amount()));
        audit(actor, "SET_RATES", "ROOM_TYPE", id);
    }

    @Transactional
    public RoomView createRoom(long actor, long typeId, String number) {
        catalog.type(typeId, true);
        var room = catalog.save(new Room(typeId, number));
        audit(actor, "CREATE_ROOM", "ROOM", room.getId());
        return view(room);
    }

    @Transactional(readOnly = true)
    public List<RoomView> rooms(long typeId) {
        catalog.type(typeId, false);
        return catalog.rooms(typeId, false).stream().map(CatalogService::view).toList();
    }

    @Transactional
    public RoomView activate(long actor, long id, boolean active) {
        var old = catalog.room(id, false);
        catalog.type(old.getRoomTypeId(), true);
        var room = catalog.room(id, true);
        if (!active && catalog.hasFutureOccupancy(id, today())) {
            throw DomainException.conflict("OCCUPIED", "存在未来占用，不能停用房间");
        }
        room.setActive(active);
        audit(actor, "ROOM_ACTIVE_" + active, "ROOM", id);
        return view(room);
    }

    @Transactional
    public String block(long actor, long id, Stay range, String reason) {
        if (range.nights() > 366 || range.checkIn().isBefore(today())) {
            throw DomainException.invalid("BLOCK_RANGE", "停用日期范围无效");
        }
        var old = catalog.room(id, false);
        catalog.type(old.getRoomTypeId(), true);
        catalog.room(id, true);
        // The unique room/date key is the final guard against overlapping bookings and blocks.
        long block =
                catalog.block(
                        id, range.checkIn(), range.checkOut(), reason, actor, clock.instant());
        audit(actor, "BLOCK_ROOM", "ROOM", id);
        return Long.toString(block);
    }

    @Transactional
    public void unblock(long actor, long id) {
        catalog.unblock(id);
        audit(actor, "UNBLOCK_ROOM", "ROOM_BLOCK", id);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> blocks(long roomId) {
        catalog.room(roomId, false);
        return catalog.blocks(roomId);
    }

    private LocalDate today() {
        return clock.instant().atZone(settings.getZone()).toLocalDate();
    }

    private void audit(long actor, String action, String type, long id) {
        operations.audit(actor, action, type, id, "", clock.instant());
    }
}
