package com.holidaybooking.backend.catalog;

import static com.holidaybooking.backend.support.TestEvidence.*;
import static org.assertj.core.api.Assertions.*;

import com.holidaybooking.backend.booking.domain.Stay;
import com.holidaybooking.backend.shared.domain.DomainException;
import com.holidaybooking.backend.support.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

/** 房源、日期价格及维护封房测试；每个用例创建独立房型和实体房。 */
@Tag("core")
public class CatalogServiceTest extends BusinessTestSupport {
    @Test
    @CaseSpec(input = "2间实体房，在2晚中分别封住不同一晚", expected = "没有完整连续可住房；创建报价被拒绝")
    protected void dailyFreeCountsDoNotImplyAContinuousAvailableRoom() {
        long user = user(), type = type(user, 2);
        var rooms = catalog.rooms(type);
        catalog.block(
                user,
                Long.parseLong(rooms.get(0).id()),
                new Stay(stay().checkIn(), stay().checkIn().plusDays(1)),
                "Maintenance");
        catalog.block(
                user,
                Long.parseLong(rooms.get(1).id()),
                new Stay(stay().checkIn().plusDays(1), stay().checkOut()),
                "Maintenance");
        assertThat(actual(catalog.availability(stay(), 2, 1)))
                .noneMatch(a -> a.roomType().id().equals(Long.toString(type)));
        assertThatThrownBy(expectFailure(() -> quote(user, type)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    @CaseSpec(
            input = "2间房，基础价改220，首晚覆盖300，住2晚；随后封房、解除、停用和启用实体房",
            expected = "总价520.00；库存2→1→2→1→2；封房历史保留；重复封房冲突")
    void ratesBlocksAndRoomLifecycleUpdateAvailability() {
        long actor = user(), type = type(actor, 2);
        catalog.updateType(
                actor, type, "Updated", "Updated description", 2, new BigDecimal("220"), true);
        catalog.rates(
                actor,
                type,
                new Stay(stay().checkIn(), stay().checkIn().plusDays(1)),
                new BigDecimal("300"));
        var available =
                catalog.availability(stay(), 2, 1).stream()
                        .filter(v -> v.roomType().id().equals(Long.toString(type)))
                        .findFirst()
                        .orElseThrow();
        assertThat(actual(available.totalPerRoom())).isEqualTo("520.00");
        assertThat(actual(available.availableRooms())).isEqualTo(2);
        long room = Long.parseLong(catalog.rooms(type).getFirst().id());
        String block = catalog.block(actor, room, stay(), "Maintenance");
        assertThat(actual(catalog.blocks(room))).hasSize(1);
        assertThat(
                        actual(
                                catalog.availability(stay(), 2, 1).stream()
                                        .filter(v -> v.roomType().id().equals(Long.toString(type)))
                                        .findFirst()
                                        .orElseThrow()
                                        .availableRooms()))
                .isEqualTo(1);
        assertThatThrownBy(expectFailure(() -> catalog.block(actor, room, stay(), "Overlap")))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        catalog.unblock(actor, Long.parseLong(block));
        assertThat(
                        actual(
                                catalog.availability(stay(), 2, 1).stream()
                                        .filter(v -> v.roomType().id().equals(Long.toString(type)))
                                        .findFirst()
                                        .orElseThrow()
                                        .availableRooms()))
                .isEqualTo(2);
        assertThat(actual(catalog.activate(actor, room, false).active())).isFalse();
        assertThat(
                        actual(
                                catalog.availability(stay(), 2, 1).stream()
                                        .filter(v -> v.roomType().id().equals(Long.toString(type)))
                                        .findFirst()
                                        .orElseThrow()
                                        .availableRooms()))
                .isEqualTo(1);
        assertThat(actual(catalog.activate(actor, room, true).active())).isTrue();
        assertThat(actual(catalog.rooms(type))).hasSize(2);
        assertThat(
                        actual(
                                catalog.availability(stay(), 2, 1).stream()
                                        .filter(v -> v.roomType().id().equals(Long.toString(type)))
                                        .findFirst()
                                        .orElseThrow()
                                        .availableRooms()))
                .isEqualTo(2);
    }

    @Test
    @CaseSpec(input = "创建房型后停用；分别调用公开列表、后台列表和详情", expected = "公开列表不显示，后台保留，详情NOT_FOUND；非法人数和房间数被拒绝")
    void inactiveTypesAreHiddenAndPartyLimitsAreValidated() {
        long actor = user(), type = type(actor, 1);
        catalog.updateType(actor, type, "Retired", "", 2, new BigDecimal("100"), false);
        assertThat(
                        actual(
                                catalog.types(false).stream()
                                        .anyMatch(v -> v.id().equals(Long.toString(type)))))
                .isFalse();
        assertThat(
                        actual(
                                catalog.types(true).stream()
                                        .anyMatch(v -> v.id().equals(Long.toString(type)))))
                .isTrue();
        assertThatThrownBy(expectFailure(() -> catalog.detail(type)))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(expectFailure(() -> catalog.availability(stay(), 0, 1)))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(expectFailure(() -> catalog.availability(stay(), 1, 11)))
                .isInstanceOf(DomainException.class);
    }
}
