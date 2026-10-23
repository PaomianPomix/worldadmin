package dev.worldadmin.pregen.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class LoadGateTest {
    private static long ms(long value) {
        return Duration.ofMillis(value).toNanos();
    }

    @Test
    void admissionRequiresTelemetryCapacityAndSpacing() {
        LoadGate gate = new LoadGate(LoadGate.Config.defaults());
        assertEquals(LoadGate.Reason.WAITING_FOR_TICK, gate.evaluate(0, 0.4, 0).reason());
        gate.recordTick(ms(5), 0);
        LoadGate.Decision initial = gate.evaluate(0, 0.4, 0);
        assertTrue(initial.allowed());
        gate.recordSubmission(0);
        assertEquals(LoadGate.Reason.IN_FLIGHT_LIMIT, gate.evaluate(ms(5000), 0.4, 1).reason());
        assertEquals(LoadGate.Reason.SUBMISSION_INTERVAL, gate.evaluate(1, 0.4, 0).reason());
        assertTrue(gate.evaluate(initial.intervalNanos(), 0.4, 0).allowed());
    }

    @Test
    void oneSevereTickTriggersCooldownEvenAfterEwmaRecovers() {
        LoadGate gate = new LoadGate(LoadGate.Config.defaults());
        gate.recordTick(ms(5), 0);
        gate.recordTick(ms(200), ms(200));
        // The EWMA is still below the pause threshold; the spike alone must stop work.
        assertTrue(gate.evaluate(ms(200), 0.4, 0).tickEwmaNanos() < ms(40));
        for (int tick = 1; tick <= 40; tick++) {
            gate.recordTick(ms(5), ms(200 + tick * 50L));
        }
        assertEquals(LoadGate.Reason.RECENT_TICK_SPIKE, gate.evaluate(ms(5199), 0.4, 0).reason());
        assertTrue(gate.evaluate(ms(5200), 0.4, 0).allowed());
    }

    @Test
    void sustainedPressurePausesAndRequiresClearRecovery() {
        LoadGate gate = new LoadGate(configWithImmediateEwma());
        gate.recordTick(ms(41), 0);
        assertEquals(LoadGate.Reason.TICK_PRESSURE, gate.evaluate(0, 0.4, 0).reason());
        gate.recordTick(ms(35), ms(50));
        assertFalse(gate.evaluate(ms(50), 0.4, 0).allowed());
        gate.recordTick(ms(30), ms(100));
        assertTrue(gate.evaluate(ms(100), 0.4, 0).allowed());

        assertEquals(LoadGate.Reason.HEAP_PRESSURE, gate.evaluate(ms(150), 0.85, 0).reason());
        assertFalse(gate.evaluate(ms(200), 0.82, 0).allowed());
        assertTrue(gate.evaluate(ms(250), 0.78, 0).allowed());
    }

    @Test
    void slowsDownBeforeThePauseThresholdAndSpeedsUpWhenPressureClears() {
        LoadGate gate = new LoadGate(configWithImmediateEwma());
        gate.recordTick(ms(5), 0);
        long quietInterval = gate.evaluate(0, 0.4, 0).intervalNanos();
        gate.recordTick(ms(35), ms(50));
        LoadGate.Decision pressured = gate.evaluate(ms(50), 0.4, 0);
        assertTrue(pressured.allowed());
        assertTrue(pressured.intervalNanos() > quietInterval * 5);
        assertTrue(pressured.intervalNanos() <= ms(2000));
        gate.recordTick(ms(5), ms(100));
        assertEquals(quietInterval, gate.evaluate(ms(100), 0.4, 0).intervalNanos());
    }

    @Test
    void monotonicCounterWrapDoesNotBreakSpacingOrCooldown() {
        LoadGate gate = new LoadGate(configWithImmediateEwma());
        long start = Long.MAX_VALUE - ms(1000);
        gate.recordTick(ms(101), start);
        gate.recordTick(ms(5), start + ms(50));
        assertFalse(gate.evaluate(start + ms(4999), 0.4, 0).allowed());
        assertTrue(gate.evaluate(start + ms(5000), 0.4, 0).allowed());
        gate.recordSubmission(start);
        assertTrue(gate.evaluate(start + ms(5000), 0.4, 0).allowed());
    }

    @Test
    void invalidSamplesAreRejectedInsteadOfBypassingLimits() {
        LoadGate gate = new LoadGate(LoadGate.Config.defaults());
        assertThrows(IllegalArgumentException.class, () -> gate.evaluate(0, Double.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> gate.evaluate(0, 1.1, 0));
        assertThrows(IllegalArgumentException.class, () -> gate.evaluate(0, 0.4, -1));
        assertThrows(IllegalArgumentException.class, () -> gate.recordTick(-1, 0));
    }

    private static LoadGate.Config configWithImmediateEwma() {
        LoadGate.Config defaults = LoadGate.Config.defaults();
        return new LoadGate.Config(defaults.maxInFlight(), 1.0,
                defaults.tickPauseNanos(), defaults.tickRecoverNanos(), defaults.spikeTickNanos(),
                defaults.spikeCooldownNanos(), defaults.heapPauseFraction(), defaults.heapRecoverFraction(),
                defaults.minSubmissionIntervalNanos(), defaults.maxSubmissionIntervalNanos());
    }
}
