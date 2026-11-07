package com.holidaybooking.backend.shared.infrastructure;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Low-cardinality operational gauges. SQL runs on metric collection, never at bean construction.
 */
@Component
public class IntegrationMetrics {
    public IntegrationMetrics(JdbcClient jdbc, MeterRegistry metrics) {
        gauge(
                metrics,
                jdbc,
                "holiday.mail.unknown",
                "select count(*) from mail_message where state='UNKNOWN'");
        gauge(
                metrics,
                jdbc,
                "holiday.notification.unknown",
                "select count(*) from notification_task where state='UNKNOWN'");
        gauge(
                metrics,
                jdbc,
                "holiday.payment.exceptions",
                "select count(*) from payment_exception where status='OPEN'");
        gauge(
                metrics,
                jdbc,
                "holiday.callback.review",
                "select count(*) from sandbox_payment_event where state='REVIEW'");
        gauge(
                metrics,
                jdbc,
                "holiday.recovery.review",
                "select count(*) from integration_recovery where manual_review=true");
        gauge(
                metrics,
                jdbc,
                "holiday.mail.queue.age.seconds",
                "select coalesce(timestampdiff(second,min(due_at),utc_timestamp()),0) from mail_message where state in ('READY','RETRY') and due_at<=utc_timestamp()");
        gauge(
                metrics,
                jdbc,
                "holiday.notification.queue.age.seconds",
                "select coalesce(timestampdiff(second,min(due_at),utc_timestamp()),0) from notification_task where state in ('READY','RETRY') and due_at<=utc_timestamp()");
    }

    private static void gauge(MeterRegistry registry, JdbcClient jdbc, String name, String sql) {
        io.micrometer.core.instrument.Gauge.builder(
                        name, jdbc, client -> client.sql(sql).query(Double.class).single())
                .strongReference(true)
                .register(registry);
    }
}
