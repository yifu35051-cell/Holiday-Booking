package com.holidaybooking.backend.booking.application;

import com.holidaybooking.backend.booking.domain.*;
import com.holidaybooking.backend.catalog.application.CatalogService;
import com.holidaybooking.backend.catalog.domain.*;
import com.holidaybooking.backend.identity.domain.IdentityRepository;
import com.holidaybooking.backend.membership.application.MembershipService;
import com.holidaybooking.backend.notification.application.NotificationService;
import com.holidaybooking.backend.payment.application.PaymentLedger;
import com.holidaybooking.backend.payment.domain.PaymentRepository;
import com.holidaybooking.backend.shared.application.*;
import com.holidaybooking.backend.shared.config.BookingProperties;
import com.holidaybooking.backend.shared.domain.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 报价与预订用例。创建预订时原子写入占房、价格快照和应付单，任何一步失败均回滚。 */
@Service
@RequiredArgsConstructor
public class BookingService {
    private final BookingRepository bookings;
    private final CatalogRepository catalog;
    private final CatalogService catalogService;
    private final IdentityRepository users;
    private final MembershipService memberships;
    private final PaymentLedger ledger;
    private final PaymentRepository payments;
    private final NotificationService notifications;
    private final OperationStore operations;
    private final SnapshotCodec codec;
    private final BookingProperties settings;
    private final Clock clock;

    public record Selection(long roomTypeId, int rooms, int guestsPerRoom) {}

    public record QuoteView(
            String id,
            Instant expiresAt,
            String totalAmount,
            String currency,
            QuoteSnapshot snapshot) {}

    public record BookingView(
            String id,
            String userId,
            String status,
            LocalDate checkIn,
            LocalDate checkOut,
            String totalAmount,
            String currency,
            String contactName,
            String contactEmail,
            String contactPhone,
            Instant expiresAt,
            Instant confirmedAt,
            int revision,
            String paymentOrderId,
            List<BookingRepository.BookingRoom> rooms) {}

    /** 报价只保存价格与权益快照，不锁定库存；创建预订时必须重新校验可用性。 */
    @Transactional
    public QuoteView quote(long user, Stay stay, List<Selection> selections) {
        users.lock(user);
        catalogService.validate(stay);
        if (selections == null
                || selections.isEmpty()
                || selections.size() > settings.getMaxRooms()) {
            throw DomainException.invalid("SELECTION", "请选择房型和间数");
        }
        int roomCount = 0;
        Set<Long> seen = new HashSet<>();
        List<QuoteSnapshot.RoomPrice> lines = new ArrayList<>();
        BigDecimal ratio = memberships.ratio(user);
        for (var selection :
                selections.stream()
                        .sorted(Comparator.comparingLong(Selection::roomTypeId))
                        .toList()) {
            if (!seen.add(selection.roomTypeId())
                    || selection.rooms() < 1
                    || selection.rooms() > settings.getMaxRooms()) {
                throw DomainException.invalid("SELECTION", "房型不能重复，间数必须为正数");
            }
            roomCount += selection.rooms();
            if (roomCount > settings.getMaxRooms()) {
                throw DomainException.invalid("ROOM_LIMIT", "超出单笔预订间数限制");
            }
            var type = catalog.type(selection.roomTypeId(), true);
            if (!type.isActive()
                    || selection.guestsPerRoom() < 1
                    || selection.guestsPerRoom() > type.getCapacity()) {
                throw DomainException.invalid("CAPACITY", "房型不可订或每间人数超出容量");
            }
            if (catalog.available(type.getId(), stay.checkIn(), stay.checkOut()).size()
                    < selection.rooms()) {
                throw DomainException.conflict("NO_AVAILABILITY", "所选日期没有足够的连续可住房间");
            }
            var rates = catalog.rates(type.getId(), stay.checkIn(), stay.checkOut());
            var nights =
                    stay.dates().stream()
                            .map(
                                    date -> {
                                        Money base =
                                                new Money(
                                                        rates.getOrDefault(
                                                                date, type.getBasePrice()));
                                        Money payable = base.discounted(ratio);
                                        if (payable.amount().signum() == 0) {
                                            throw DomainException.invalid(
                                                    "PRICE_TOO_LOW", "折扣后的每晚金额必须至少为 0.01 元");
                                        }
                                        return new QuoteSnapshot.NightPrice(
                                                date, base.text(), payable.text());
                                    })
                            .toList();
            for (int i = 0; i < selection.rooms(); i++) {
                lines.add(
                        new QuoteSnapshot.RoomPrice(
                                type.getId().toString(),
                                type.getName(),
                                selection.guestsPerRoom(),
                                nights));
            }
        }
        var snapshot =
                new QuoteSnapshot(
                        stay.checkIn(), stay.checkOut(), ratio.toPlainString(), List.copyOf(lines));
        // 在写入数据库之前检查总金额上限，避免数据库截断或溢出。
        new Money(snapshot.total());
        var quote =
                bookings.save(
                        new BookingQuote(
                                user,
                                codec.encode(snapshot),
                                clock.instant().plus(settings.getQuoteTtl())));
        return new QuoteView(
                quote.getId().toString(),
                quote.getExpiresAt(),
                snapshot.total().toPlainString(),
                "CNY",
                snapshot);
    }

    /** 在一个事务内消费报价、分配房间、记录逐晚价格并生成应付单。 用户行锁使幂等检查与写入成为同一串行操作，重复提交不会产生第二笔订单。 */
    @Transactional
    public BookingView create(
            long user, long quoteId, String name, String email, String phone, String key) {
        String hash =
                RequestFingerprint.hash(key, quoteId + "\n" + name + "\n" + email + "\n" + phone);
        users.lock(user);
        Long prior = operations.replay(user, "CREATE_BOOKING", key, hash);
        if (prior != null) {
            return view(bookings.booking(prior));
        }
        var quote = bookings.quote(quoteId);
        if (quote.getUserId() != user) {
            throw DomainException.missing();
        }
        // 消费标记也参与本事务；后续库存不足时，此标记会随事务一起回滚。
        quote.consume(clock.instant());
        var snapshot = codec.decode(quote.getSnapshot());
        catalogService.validate(snapshot.stay());
        if (memberships.ratio(user).compareTo(new BigDecimal(snapshot.discountRatio())) != 0) {
            throw DomainException.conflict("MEMBERSHIP_CHANGED", "会员权益已变化，请重新报价");
        }
        var allocations = lockAndAllocateRooms(snapshot);

        // 当日临近入住时间创建的预订，不允许付款窗口延伸到入住截止时间之后。
        Instant holdDeadline = clock.instant().plus(settings.getHoldTtl());
        Instant arrivalDeadline =
                snapshot.checkIn()
                        .atTime(settings.getCheckInTime())
                        .atZone(settings.getZone())
                        .toInstant();
        if (arrivalDeadline.isBefore(holdDeadline)) {
            holdDeadline = arrivalDeadline;
        }
        var booking =
                bookings.save(
                        new Booking(user, quoteId, snapshot, name, email, phone, holdDeadline));
        bookings.revision(booking, snapshot, allocations);
        for (var room : allocations) {
            catalog.hold(room.roomId(), snapshot.stay().dates(), booking.getId());
        }
        ledger.create(
                user, booking.getId(), null, booking.getTotalAmount(), booking.getExpiresAt());
        operations.remember(user, "CREATE_BOOKING", key, hash, booking.getId(), clock.instant());
        return view(booking);
    }

    /** 为完整入住区间分配同一间实体房间。 所有写操作按房型 ID、房间 ID 的顺序加锁，避免多房型请求互相反向等待。 任一房型不足时抛出异常，外层事务会回滚报价消费和全部占房。 */
    private List<BookingRepository.AllocatedRoom> lockAndAllocateRooms(QuoteSnapshot snapshot) {
        var byType = new TreeMap<Long, List<QuoteSnapshot.RoomPrice>>();
        for (var line : snapshot.rooms()) {
            byType.computeIfAbsent(Long.parseLong(line.roomTypeId()), ignored -> new ArrayList<>())
                    .add(line);
        }
        List<BookingRepository.AllocatedRoom> allocations = new ArrayList<>();
        // Lock types, then physical rooms in ascending ID order. All writers use the same order.
        for (var entry : byType.entrySet()) {
            var type = catalog.type(entry.getKey(), true);
            if (!type.isActive()) {
                throw DomainException.conflict("TYPE_INACTIVE", "房型已停用");
            }
            var rooms = catalog.rooms(type.getId(), true);
            var available =
                    catalog.available(type.getId(), snapshot.checkIn(), snapshot.checkOut());
            if (available.size() < entry.getValue().size()) {
                throw DomainException.conflict("NO_AVAILABILITY", "房间已被其他顾客预订");
            }
            var indexed = new HashMap<Long, Room>();
            rooms.forEach(room -> indexed.put(room.getId(), room));
            for (int i = 0; i < entry.getValue().size(); i++) {
                var line = entry.getValue().get(i);
                if (line.guests() > type.getCapacity()) {
                    throw DomainException.conflict("CAPACITY_CHANGED", "房型容量已变化");
                }
                var room = indexed.get(available.get(i));
                allocations.add(
                        new BookingRepository.AllocatedRoom(room.getId(), room.getNumber(), line));
            }
        }
        return allocations;
    }

    @Transactional(readOnly = true)
    public BookingView get(long user, long id, boolean staff) {
        var booking = bookings.booking(id);
        if (!staff && booking.getUserId() != user) {
            throw DomainException.missing();
        }
        return view(booking);
    }

    @Transactional(readOnly = true)
    public PageResult<BookingView> list(
            Long user, Booking.Status status, LocalDate from, LocalDate to, int page, int size) {
        int offset = PageResult.offset(page, size);
        if (from != null && to != null && from.isAfter(to)) {
            throw DomainException.invalid("DATE_RANGE", "日期筛选范围无效");
        }
        return new PageResult<>(
                bookings.list(user, status, from, to, offset, size).stream()
                        .map(this::view)
                        .toList(),
                page,
                size,
                bookings.count(user, status, from, to));
    }

    private BookingView view(Booking booking) {
        return new BookingView(
                booking.getId().toString(),
                booking.getUserId().toString(),
                booking.getStatus().name(),
                booking.getCheckIn(),
                booking.getCheckOut(),
                booking.getTotalAmount().toPlainString(),
                "CNY",
                booking.getContactName(),
                booking.getContactEmail(),
                booking.getContactPhone(),
                booking.getExpiresAt(),
                booking.getConfirmedAt(),
                booking.getCurrentRevision(),
                payments.forBooking(booking.getId()).getId().toString(),
                bookings.rooms(booking.getId()));
    }

    /** 仅供已核验的支付流程调用；确认订单、库存与通知任务必须共同提交。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void confirm(long id, Instant paidAt) {
        var booking = bookings.booking(id);
        booking.confirm(paidAt);
        catalog.confirm(id);
        notifications.schedule(
                booking.getId(),
                booking.getCurrentRevision(),
                booking.getContactEmail(),
                booking.getContactName(),
                booking.getCheckIn(),
                booking.getCheckOut(),
                booking.getTotalAmount());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void expire(long id) {
        var booking = bookings.booking(id);
        booking.expire();
        catalog.release(id);
    }
}
