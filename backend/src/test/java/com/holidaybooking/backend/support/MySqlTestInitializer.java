package com.holidaybooking.backend.support;

import java.util.UUID;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.testcontainers.mysql.MySQLContainer;

/** Flyway/JPA 启动前注入隔离 MySQL；不读开发配置，Docker 不可用即失败。 */
public class MySqlTestInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
        MySQLContainer database =
                new MySQLContainer("mysql:8.4")
                        .withDatabaseName("holiday_booking_test")
                        .withUsername("holiday_test")
                        .withPassword(UUID.randomUUID().toString())
                        .withReuse(false)
                        .withUrlParam("connectionTimeZone", "UTC")
                        .withUrlParam("forceConnectionTimeZoneToSession", "true")
                        .withCommand(
                                "--character-set-server=utf8mb4",
                                "--collation-server=utf8mb4_0900_ai_ci",
                                "--default-time-zone=+00:00");
        database.start();
        context.addApplicationListener(
                event -> {
                    if (event instanceof ContextClosedEvent closed
                            && closed.getApplicationContext() == context) {
                        database.stop();
                    }
                });
        try {
            TestPropertyValues.of(
                            "spring.datasource.url=" + database.getJdbcUrl(),
                            "spring.datasource.username=" + database.getUsername(),
                            "spring.datasource.password=" + database.getPassword(),
                            "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                            "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
                            "booking.bootstrap-email=",
                            "booking.bootstrap-password=",
                            "booking.jobs-enabled=false",
                            "booking.payment-mode=mock",
                            "booking.email-mode=log")
                    .applyTo(context.getEnvironment());
        } catch (RuntimeException error) {
            database.stop();
            throw error;
        }
    }
}
