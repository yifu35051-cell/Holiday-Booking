package com.holidaybooking.backend.shared.application;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Per-channel circuit: five failures open for 30 seconds; exactly one half-open probe. */
@Component
public class IntegrationCircuit {
    private static final class State {
        int failures;
        long openUntil;
        boolean probing;
    }

    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();
    private final Clock clock;
    private final MeterRegistry metrics;

    public IntegrationCircuit(Clock clock, MeterRegistry metrics) {
        this.clock = clock;
        this.metrics = metrics;
    }

    public void duration(String channel, long nanos) {
        metrics.timer("holiday.integration.duration", "channel", channel)
                .record(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    public boolean acquire(String channel) {
        State state = states.computeIfAbsent(channel, key -> new State());
        synchronized (state) {
            if (state.failures < 5) return true;
            if (clock.millis() < state.openUntil || state.probing) {
                metrics.counter(
                                "holiday.integration.calls",
                                "channel",
                                channel,
                                "outcome",
                                "circuit_open")
                        .increment();
                return false;
            }
            state.probing = true;
            return true;
        }
    }

    public void complete(String channel, boolean healthy) {
        State state = states.computeIfAbsent(channel, key -> new State());
        synchronized (state) {
            state.probing = false;
            if (healthy) {
                state.failures = 0;
                state.openUntil = 0;
            } else if (++state.failures >= 5) state.openUntil = clock.millis() + 30_000;
        }
        metrics.counter(
                        "holiday.integration.calls",
                        "channel",
                        channel,
                        "outcome",
                        healthy ? "accepted" : "failure")
                .increment();
    }
}
