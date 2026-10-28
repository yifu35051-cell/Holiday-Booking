package com.holidaybooking.backend.shared.config;

import com.holidaybooking.backend.booking.application.BookingChangeService;
import com.holidaybooking.backend.booking.application.BookingService;
import com.holidaybooking.backend.booking.domain.Stay;
import com.holidaybooking.backend.catalog.application.CatalogService;
import com.holidaybooking.backend.identity.application.IdentityService;
import com.holidaybooking.backend.identity.domain.IdentityRepository;
import com.holidaybooking.backend.identity.domain.UserAccount;
import com.holidaybooking.backend.mail.application.MailService;
import com.holidaybooking.backend.membership.application.MembershipService;
import com.holidaybooking.backend.payment.application.PaymentService;
import com.holidaybooking.backend.payment.application.RefundService;
import com.holidaybooking.backend.shared.domain.OperationStore;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** 显式启用的本地演示数据装载器，不属于生产迁移，不提供 HTTP 入口。 全部业务记录与完成标记在同一事务提交；重复启动不覆盖管理员或已编辑数据。 */
@Component
@Profile("local")
@ConditionalOnProperty(name = "booking.demo.enabled", havingValue = "true")
@Order(100)
@RequiredArgsConstructor
@Slf4j
public class LocalDemoData implements ApplicationRunner {
    private static final String MARKER = "LOCAL_DEMO_V1";
    private static final List<String> EMAILS =
            List.of(
                    "lin.demo@example.test",
                    "zhou.demo@example.test",
                    "chen.demo@example.test",
                    "frontdesk.demo@example.test",
                    "service.demo@example.test");

    private final Environment environment;
    private final BookingProperties settings;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final IdentityService identity;
    private final IdentityRepository users;
    private final CatalogService catalog;
    private final MembershipService membership;
    private final BookingService bookings;
    private final BookingChangeService changes;
    private final PaymentService payments;
    private final RefundService refunds;
    private final MailService mail;
    private final OperationStore operations;
    private final Clock clock;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        requireLocalChannels();
        transactions.executeWithoutResult(status -> seed());
    }

    private void requireLocalChannels() {
        String url = environment.getRequiredProperty("spring.datasource.url");
        boolean container = environment.getProperty("booking.demo.container", Boolean.class, false);
        String hosts = container ? "mysql" : "(127\\.0\\.0\\.1|localhost)";
        if (!url.matches("jdbc:mysql://" + hosts + ":[0-9]+/holiday_booking(?:\\?.*)?")
                || !"mock".equals(settings.getPaymentMode())) {
            throw new IllegalStateException(
                    "Demo import requires loopback holiday_booking MySQL and mock payment");
        }
        if ("smtp".equals(settings.getEmailMode())
                && (!environment.getProperty("MAILPIT_ENABLED", Boolean.class, false)
                        || !(container ? List.of("mailpit") : List.of("localhost", "127.0.0.1"))
                                .contains(environment.getProperty("spring.mail.host"))
                        || !environment
                                .getProperty("spring.mail.port", "")
                                .equals(environment.getProperty("MAILPIT_PORT", "1025")))) {
            throw new IllegalStateException("Demo SMTP must use the configured local Mailpit");
        }
    }

    private void seed() {
        // Existing singleton row serializes concurrent imports without introducing another table.
        jdbc.sql("select id from notification_quota where id=1 for update")
                .query(Integer.class)
                .single();
        if (jdbc.sql("select count(*) from audit_log where action=?")
                        .param(MARKER)
                        .query(Long.class)
                        .single()
                > 0) {
            log.info("Local demo V1 already imported; all existing data retained");
            return;
        }
        var admins =
                jdbc.sql(
                                "select id from user_account where role='ADMIN' and enabled=true order by id")
                        .query(Long.class)
                        .list();
        if (admins.size() != 1) {
            throw new IllegalStateException(
                    "Demo import requires exactly one existing enabled administrator; use -InitializeAdmin only on a new database");
        }
        long admin = admins.getFirst();
        for (String email : EMAILS) {
            if (users.byEmail(email).isPresent()) {
                throw new IllegalStateException(
                        "A reserved demo email already exists; import rolled back without replacing accounts");
            }
        }
        Properties credentials =
                LocalDemoCredentials.loadOrCreate(
                        Path.of(environment.getRequiredProperty("booking.demo.credentials-file")),
                        EMAILS);
        String[] names = {"林悦（演示顾客）", "周宁（演示顾客）", "陈晨（演示顾客）", "前台小王（演示员工）", "客服小李（演示员工）"};
        List<Long> accounts = new ArrayList<>();
        for (int i = 0; i < EMAILS.size(); i++) {
            long id =
                    Long.parseLong(
                            identity.register(
                                            EMAILS.get(i),
                                            credentials.getProperty(EMAILS.get(i)),
                                            names[i])
                                    .id());
            if (i >= 3) identity.changeRole(admin, id, UserAccount.Role.STAFF);
            accounts.add(id);
        }

        LocalDate today = LocalDate.now(clock.withZone(settings.getZone()));
        String[] typeNames = {"园景大床房（演示）", "庭院双床房（演示）", "亲子家庭房（演示）", "湖景套房（演示）"};
        String[] descriptions = {
            "1张1.8米大床，独立卫浴、书桌，适合双人度假。",
            "2张1.2米单人床，独立卫浴、休息区，适合结伴出游。",
            "1张大床和2张单人床，家庭活动区，最多入住4人。",
            "独立卧室与客厅，湖景阳台，适合双人休闲住宿。"
        };
        int[] capacities = {2, 2, 4, 2};
        String[] prices = {"328.00", "368.00", "568.00", "888.00"};
        List<Long> types = new ArrayList<>();
        List<List<Long>> rooms = new ArrayList<>();
        for (int i = 0; i < typeNames.length; i++) {
            long type =
                    Long.parseLong(
                            catalog.createType(
                                            admin,
                                            typeNames[i],
                                            descriptions[i],
                                            capacities[i],
                                            new BigDecimal(prices[i]))
                                    .id());
            types.add(type);
            List<Long> group = new ArrayList<>();
            for (int n = 1; n <= 5; n++) {
                group.add(
                        Long.parseLong(
                                catalog.createRoom(admin, type, "DEMO-" + (i + 1) + "0" + n).id()));
            }
            rooms.add(group);
            // 未来90天周五/周六加价；其余日期继续使用基础价。
            for (LocalDate day = today; day.isBefore(today.plusDays(90)); day = day.plusDays(1)) {
                if (day.getDayOfWeek() == DayOfWeek.FRIDAY
                        || day.getDayOfWeek() == DayOfWeek.SATURDAY) {
                    catalog.rates(
                            admin,
                            type,
                            new Stay(day, day.plusDays(1)),
                            new BigDecimal(prices[i]).add(new BigDecimal("80.00")));
                }
            }
        }
        catalog.activate(admin, rooms.get(3).get(4), false);
        catalog.block(
                accounts.get(3),
                rooms.get(1).get(4),
                new Stay(today.plusDays(2), today.plusDays(5)),
                "演示：空调维护，结束日不占用");
        var plan =
                membership.createPlan(
                        admin, "季度度假会员（演示）", new BigDecimal("79.00"), new BigDecimal("0.9500"), 90);
        membership.createPlan(
                admin, "尊享年度会员（演示）", new BigDecimal("299.00"), new BigDecimal("0.8500"), 365);
        var purchase =
                membership.purchase(
                        accounts.get(0), Long.parseLong(plan.id()), "demo-v1-membership");
        pay(accounts.get(0), purchase.paymentOrderId(), "demo-v1-member-pay");

        createBooking(
                accounts.get(0), EMAILS.get(0), types.get(0), today.plusDays(7), 2, true, "member");
        createBooking(
                accounts.get(1),
                EMAILS.get(1),
                types.get(1),
                today.plusDays(12),
                3,
                true,
                "standard");
        createBooking(
                accounts.get(2),
                EMAILS.get(2),
                types.get(2),
                today.plusDays(20),
                2,
                false,
                "pending");
        var cancelled =
                createBooking(
                        accounts.get(1),
                        EMAILS.get(1),
                        types.get(3),
                        today.plusDays(25),
                        2,
                        true,
                        "cancel");
        var quote = changes.quoteCancellation(accounts.get(1), Long.parseLong(cancelled.id()));
        var applied =
                changes.submit(
                        accounts.get(1),
                        Long.parseLong(cancelled.id()),
                        Long.parseLong(quote.id()),
                        "CANCEL",
                        "demo-v1-cancel");
        for (var refund : applied.refunds()) refunds.refresh(accounts.get(1), refund.id());

        // 正常排队，不伪造已投递状态。应用启动后由Mailpit实际接受邮件。
        mail.send(
                accounts.get(0),
                EMAILS.get(3),
                "演示：抵店时间咨询",
                "您好，预计入住当日16点抵店，请问前台在哪里？",
                null,
                "demo-v1-mail-1");
        mail.send(
                accounts.get(3),
                EMAILS.get(0),
                "演示：入住指引",
                "欢迎入住！前台位于一层大堂，请携带本人有效证件。",
                null,
                "demo-v1-mail-2");
        mail.send(
                accounts.get(1),
                EMAILS.get(4),
                "演示：亲子出游咨询",
                "您好，请问家庭房是否适合两位成人及两位儿童？",
                null,
                "demo-v1-mail-3");
        mail.send(
                accounts.get(4),
                EMAILS.get(1),
                "演示：家庭房说明",
                "家庭房最多入住4人，预订时请填写每间实际入住人数。",
                null,
                "demo-v1-mail-4");
        operations.audit(
                admin,
                MARKER,
                "DEMO_DATA",
                admin,
                "baseDate="
                        + today
                        + "; users=5; types=4; rooms=20; plans=2; bookings=4; personalMail=4",
                clock.instant());
        log.info(
                "Local demo V1 imported: 5 accounts, 4 room types, 20 rooms, 2 plans, 4 bookings, 4 queued messages; baseDate={}",
                today);
    }

    private BookingService.BookingView createBooking(
            long user,
            String email,
            long type,
            LocalDate arrival,
            int nights,
            boolean paid,
            String key) {
        var quote =
                bookings.quote(
                        user,
                        new Stay(arrival, arrival.plusDays(nights)),
                        List.of(new BookingService.Selection(type, 1, 2)));
        var booking =
                bookings.create(
                        user,
                        Long.parseLong(quote.id()),
                        "演示入住人",
                        email,
                        "13800000000",
                        "demo-v1-book-" + key);
        if (paid) pay(user, booking.paymentOrderId(), "demo-v1-pay-" + key);
        return booking;
    }

    private void pay(long user, String orderId, String key) {
        var order = payments.get(user, Long.parseLong(orderId));
        var started = payments.start(user, Long.parseLong(orderId), order.amount(), "Alipay", key);
        payments.simulate(user, Long.parseLong(started.attempts().getLast().id()));
    }

    /** 单次导入命令复用完整Spring上下文，完成后关闭临时HTTP端口和连接池。 */
    @EventListener(ApplicationReadyEvent.class)
    public void closeAfterImport(ApplicationReadyEvent event) {
        if (environment.getProperty("booking.demo.exit-after-seed", Boolean.class, false)) {
            event.getApplicationContext().close();
        }
    }
}
