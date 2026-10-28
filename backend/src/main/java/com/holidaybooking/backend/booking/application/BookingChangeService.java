package com.holidaybooking.backend.booking.application;

import com.holidaybooking.backend.booking.domain.*;
import com.holidaybooking.backend.catalog.domain.CatalogRepository;
import com.holidaybooking.backend.identity.domain.IdentityRepository;
import com.holidaybooking.backend.membership.application.MembershipService;
import com.holidaybooking.backend.notification.application.NotificationService;
import com.holidaybooking.backend.payment.domain.*;
import com.holidaybooking.backend.shared.application.PageResult;
import com.holidaybooking.backend.shared.application.RequestFingerprint;
import com.holidaybooking.backend.shared.config.BookingProperties;
import com.holidaybooking.backend.shared.domain.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** CR1/CR2：所有写操作先锁用户，再按升序锁房型/实体房；渠道调用交由支付和退款服务。 */
@Service
@RequiredArgsConstructor
public class BookingChangeService {
    private final BookingRepository bookings;
    private final BookingChangeRepository changes;
    private final BookingService bookingService;
    private final CatalogRepository catalog;
    private final IdentityRepository users;
    private final MembershipService memberships;
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final NotificationService notifications;
    private final OperationStore operations;
    private final SnapshotCodec codec;
    private final BookingProperties settings;
    private final Clock clock;

    public record View(
            String id,
            String bookingId,
            int sourceRevision,
            String kind,
            String state,
            String priceDifference,
            String currency,
            Instant expiresAt,
            String paymentOrderId,
            QuoteSnapshot proposedStay,
            List<RefundRepository.Refund> refunds) {}

    @Transactional
    public View quoteCancellation(long user, long bookingId) {
        users.lock(user);
        var booking = owned(user, bookingId);
        ensureIdle(bookingId);
        cancellable(booking);
        BigDecimal refund =
                booking.getStatus() == Booking.Status.CONFIRMED
                        ? booking.getTotalAmount()
                        : BigDecimal.ZERO;
        long id =
                changes.create(
                        bookingId,
                        booking.getCurrentRevision(),
                        "CANCEL",
                        null,
                        refund.negate(),
                        clock.instant().plus(settings.getQuoteTtl()),
                        clock.instant());
        return view(changes.get(id));
    }

    @Transactional
    public View quoteAmendment(
            long user, long bookingId, Stay stay, List<BookingService.Selection> rooms) {
        users.lock(user);
        var booking = owned(user, bookingId);
        ensureIdle(bookingId);
        amendable(booking);
        requireNotice(stay.checkIn());
        var quote = bookingService.quoteForChange(user, stay, rooms, bookingId);
        long id =
                changes.create(
                        bookingId,
                        booking.getCurrentRevision(),
                        "AMEND",
                        Long.parseLong(quote.id()),
                        quote.snapshot().total().subtract(booking.getTotalAmount()),
                        quote.expiresAt(),
                        clock.instant());
        return view(changes.get(id));
    }

    @Transactional(readOnly = true)
    public View get(long user, long bookingId, long id) {
        owned(user, bookingId);
        var change = changes.get(id);
        if (change.bookingId() != bookingId) throw DomainException.missing();
        return view(change);
    }

    @Transactional(readOnly = true)
    public PageResult<View> list(long user, long bookingId, int page, int size) {
        owned(user, bookingId);
        int offset = PageResult.offset(page, size);
        return new PageResult<>(
                changes.ids(bookingId, offset, size).stream()
                        .map(changes::get)
                        .map(this::view)
                        .toList(),
                page,
                size,
                changes.count(bookingId));
    }

    /** 在发出关单请求前校验报价及幂等键；外部调用后提交事务仍会重新检查。 */
    @Transactional
    public boolean validateCancellationForClose(long user, long bookingId, long id, String key) {
        String hash = RequestFingerprint.hash(key, bookingId + "\n" + id + "\nCANCEL");
        users.lock(user);
        var booking = owned(user, bookingId);
        if (operations.replay(user, "BOOKING_CHANGE", key, hash) != null) return false;
        var change = changes.get(id);
        if (change.bookingId() != bookingId || !change.kind().equals("CANCEL"))
            throw DomainException.missing();
        if (!change.state().equals("QUOTED")) return false;
        if (!change.expiresAt().isAfter(clock.instant())
                || change.revision() != booking.getCurrentRevision()) {
            throw DomainException.conflict("CHANGE_QUOTE_STALE", "变更报价已过期或版本已变化");
        }
        ensureIdle(bookingId);
        cancellable(booking);
        return true;
    }

    @Transactional
    public View submit(long user, long bookingId, long id, String kind, String key) {
        String hash = RequestFingerprint.hash(key, bookingId + "\n" + id + "\n" + kind);
        users.lock(user);
        var booking = owned(user, bookingId);
        Long prior = operations.replay(user, "BOOKING_CHANGE", key, hash);
        if (prior != null) return view(changes.get(prior));
        var change = changes.get(id);
        if (change.bookingId() != bookingId || !change.kind().equals(kind))
            throw DomainException.missing();
        // Different request keys cannot execute the same quote twice either.
        if (!change.state().equals("QUOTED")) return view(change);
        if (!change.expiresAt().isAfter(clock.instant())
                || change.revision() != booking.getCurrentRevision()) {
            throw DomainException.conflict("CHANGE_QUOTE_STALE", "变更报价已过期或预订版本已变化，请重新报价");
        }
        ensureIdle(bookingId);
        if (kind.equals("CANCEL")) {
            cancellable(booking);
            BigDecimal expected =
                    booking.getStatus() == Booking.Status.CONFIRMED
                            ? booking.getTotalAmount().negate()
                            : BigDecimal.ZERO;
            if (expected.compareTo(change.delta()) != 0)
                throw DomainException.conflict("CHANGE_QUOTE_STALE", "付款状态已变化，请重新获取取消报价");
            var order = payments.forBooking(bookingId);
            if (payments.attempts(order.getId()).stream().anyMatch(PaymentAttempt::unresolved)) {
                throw DomainException.conflict(
                        "PAYMENT_IN_PROGRESS", "仍有在途付款，请先刷新支付结果；无法确认未付款时不释放库存");
            }
            lockTypes(booking, null);
            if (expected.signum() < 0)
                refunds.reserve(id, bookingId, expected.negate(), clock.instant());
            order.expire();
            booking.cancel();
            changes.cancelInventory(bookingId);
            changes.suppressNotifications(bookingId);
            changes.state(id, "APPLIED");
        } else {
            amendable(booking);
            var quote = bookings.quote(Objects.requireNonNull(change.quoteId()));
            quote.consume(clock.instant());
            var snapshot = codec.decode(quote.getSnapshot());
            requireNotice(snapshot.checkIn());
            if (memberships.ratio(user).compareTo(new BigDecimal(snapshot.discountRatio())) != 0) {
                throw DomainException.conflict("MEMBERSHIP_CHANGED", "会员权益已变化，请重新报价");
            }
            lockTypes(booking, snapshot);
            var rooms = allocate(bookingId, snapshot);
            changes.reserve(change, snapshot, rooms);
            if (change.delta().signum() > 0) {
                Instant deadline = clock.instant().plus(settings.getHoldTtl());
                deadline =
                        earlier(
                                deadline,
                                arrival(booking.getCheckIn()).minus(Duration.ofHours(24)));
                deadline =
                        earlier(deadline, arrival(snapshot.checkIn()).minus(Duration.ofHours(24)));
                if (!deadline.isAfter(clock.instant()))
                    throw DomainException.conflict("CHANGE_WINDOW", "已无可用补款时间，请联系酒店");
                payments.save(PaymentOrder.forChange(user, id, change.delta(), deadline));
                changes.state(id, "AWAITING_PAYMENT");
            } else {
                applyAmendment(change, booking, snapshot, rooms);
            }
        }
        operations.remember(user, "BOOKING_CHANGE", key, hash, id, clock.instant());
        operations.audit(
                user,
                "SUBMIT_" + kind,
                "BOOKING_CHANGE",
                id,
                "policy=FREE_24H_V1",
                clock.instant());
        return view(changes.get(id));
    }

    /** 由已验签且金额、时效匹配的补款落账事务调用。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void confirm(long id) {
        var change = changes.get(id);
        var booking = bookings.booking(change.bookingId());
        if (!change.state().equals("AWAITING_PAYMENT")
                || booking.getStatus() != Booking.Status.CONFIRMED
                || change.revision() != booking.getCurrentRevision()) {
            throw DomainException.conflict("CHANGE_STATE", "改订已失效");
        }
        var snapshot = codec.decode(bookings.quote(change.quoteId()).getSnapshot());
        lockTypes(booking, snapshot);
        applyAmendment(change, booking, snapshot, changes.allocations(id, snapshot));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void expire(long id) {
        var change = changes.get(id);
        if (!change.state().equals("AWAITING_PAYMENT")) return;
        var snapshot = codec.decode(bookings.quote(change.quoteId()).getSnapshot());
        lockTypes(bookings.booking(change.bookingId()), snapshot);
        changes.releaseAdditional(id);
        changes.state(id, "EXPIRED");
    }

    private void applyAmendment(
            BookingChangeRepository.Change change,
            Booking booking,
            QuoteSnapshot snapshot,
            List<BookingRepository.AllocatedRoom> rooms) {
        if (change.delta().signum() < 0)
            refunds.reserve(change.id(), booking.getId(), change.delta().negate(), clock.instant());
        changes.applyInventory(change, snapshot, rooms);
        changes.suppressNotifications(booking.getId());
        booking.amend(snapshot);
        bookings.revision(booking, snapshot, rooms);
        changes.state(change.id(), "APPLIED");
        notifications.schedule(
                booking.getId(),
                booking.getCurrentRevision(),
                booking.getContactEmail(),
                booking.getContactName(),
                booking.getCheckIn(),
                booking.getCheckOut(),
                booking.getTotalAmount());
        operations.audit(
                booking.getUserId(),
                "AMENDMENT_APPLIED",
                "BOOKING_CHANGE",
                change.id(),
                "revision=" + booking.getCurrentRevision(),
                clock.instant());
    }

    private List<BookingRepository.AllocatedRoom> allocate(long booking, QuoteSnapshot snapshot) {
        Map<Long, ArrayDeque<Long>> available = new HashMap<>();
        Map<Long, String> numbers = new HashMap<>();
        List<BookingRepository.AllocatedRoom> result = new ArrayList<>();
        for (var line : snapshot.rooms()) {
            long typeId = Long.parseLong(line.roomTypeId());
            var type = catalog.type(typeId, true);
            if (!type.isActive() || line.guests() > type.getCapacity())
                throw DomainException.conflict("TYPE_CHANGED", "房型已停用或容量变化");
            available.computeIfAbsent(
                    typeId,
                    ignored -> {
                        catalog.rooms(typeId, true)
                                .forEach(room -> numbers.put(room.getId(), room.getNumber()));
                        return new ArrayDeque<>(
                                catalog.availableForChange(
                                        typeId, snapshot.checkIn(), snapshot.checkOut(), booking));
                    });
            Long room = available.get(typeId).poll();
            if (room == null) throw DomainException.conflict("NO_AVAILABILITY", "新行程没有足够的连续可住房间");
            result.add(new BookingRepository.AllocatedRoom(room, numbers.get(room), line));
        }
        return result;
    }

    private void lockTypes(Booking booking, QuoteSnapshot snapshot) {
        var ids = new TreeSet<Long>();
        bookings.rooms(booking.getId())
                .forEach(room -> ids.add(Long.parseLong(room.price().roomTypeId())));
        if (snapshot != null)
            snapshot.rooms().forEach(room -> ids.add(Long.parseLong(room.roomTypeId())));
        for (long id : ids) {
            catalog.type(id, true);
            catalog.rooms(id, true);
        }
    }

    private Booking owned(long user, long booking) {
        var result = bookings.booking(booking);
        if (result.getUserId() != user) throw DomainException.missing();
        return result;
    }

    private void ensureIdle(long booking) {
        if (changes.active(booking) || refunds.unsettled(booking)) {
            throw DomainException.conflict("CHANGE_IN_PROGRESS", "请先完成当前补款或退款");
        }
    }

    private void cancellable(Booking booking) {
        if (booking.getStatus() == Booking.Status.CONFIRMED) requireNotice(booking.getCheckIn());
        else if (booking.getStatus() != Booking.Status.PENDING_PAYMENT)
            throw DomainException.conflict("BOOKING_STATE", "当前预订不可取消");
    }

    private void amendable(Booking booking) {
        if (booking.getStatus() != Booking.Status.CONFIRMED)
            throw DomainException.conflict("BOOKING_STATE", "仅已确认预订可改订");
        requireNotice(booking.getCheckIn());
    }

    private void requireNotice(LocalDate checkIn) {
        if (arrival(checkIn).isBefore(clock.instant().plus(Duration.ofHours(24)))) {
            throw DomainException.conflict("NOTICE_PERIOD", "取消和改订需至少提前24小时");
        }
    }

    private Instant arrival(LocalDate date) {
        return date.atTime(settings.getCheckInTime()).atZone(settings.getZone()).toInstant();
    }

    private static Instant earlier(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private View view(BookingChangeRepository.Change change) {
        Long order = changes.paymentOrder(change.id());
        return new View(
                Long.toString(change.id()),
                Long.toString(change.bookingId()),
                change.revision(),
                change.kind(),
                change.state(),
                change.delta().toPlainString(),
                "CNY",
                change.expiresAt(),
                order == null ? null : order.toString(),
                change.quoteId() == null
                        ? null
                        : codec.decode(bookings.quote(change.quoteId()).getSnapshot()),
                refunds.forChange(change.id()));
    }
}
