package com.holidaybooking.backend;

import java.time.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;

@TestConfiguration
public class TestClockConfig {
    @Bean
    @Primary
    public MutableClock testClock() {
        return new MutableClock();
    }

    public static class MutableClock extends Clock {
        private final AtomicReference<Instant> value =
                new AtomicReference<>(Instant.parse("2026-10-22T00:00:00Z"));

        public void reset() {
            value.set(Instant.parse("2026-10-22T00:00:00Z"));
        }

        public void advance(Duration duration) {
            value.updateAndGet(i -> i.plus(duration));
        }

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        public Instant instant() {
            return value.get();
        }
    }
}
