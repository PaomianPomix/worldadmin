package dev.worldadmin.pregen.core;

import java.time.Duration;

/**
 * Admission control for optional chunk generation, using System.nanoTime timing.
 * All methods must be called by the same owner thread. This cannot interrupt an
 * individual chunk task already running inside Minecraft or another mod.
 */
public final class LoadGate {
    public enum Reason {
        READY,
        WAITING_FOR_TICK,
        IN_FLIGHT_LIMIT,
        HEAP_PRESSURE,
        RECENT_TICK_SPIKE,
        TICK_PRESSURE,
        SUBMISSION_INTERVAL
    }

    public record Config(
            int maxInFlight,
            double tickEwmaAlpha,
            long tickPauseNanos,
            long tickRecoverNanos,
            long spikeTickNanos,
            long spikeCooldownNanos,
            double heapPauseFraction,
            double heapRecoverFraction,
            long minSubmissionIntervalNanos,
            long maxSubmissionIntervalNanos) {
        public Config {
            if (maxInFlight < 1 || !Double.isFinite(tickEwmaAlpha)
                    || tickEwmaAlpha <= 0 || tickEwmaAlpha > 1) {
                throw new IllegalArgumentException("Invalid concurrency or EWMA settings");
            }
            if (tickRecoverNanos < 0 || tickPauseNanos <= tickRecoverNanos
                    || spikeTickNanos <= tickPauseNanos || spikeCooldownNanos < 0) {
                throw new IllegalArgumentException("Invalid tick pressure thresholds");
            }
            if (!Double.isFinite(heapPauseFraction) || !Double.isFinite(heapRecoverFraction)
                    || heapRecoverFraction <= 0 || heapPauseFraction <= heapRecoverFraction
                    || heapPauseFraction > 1) {
                throw new IllegalArgumentException("Invalid heap pressure thresholds");
            }
            if (minSubmissionIntervalNanos <= 0
                    || maxSubmissionIntervalNanos < minSubmissionIntervalNanos) {
                throw new IllegalArgumentException("Invalid submission intervals");
            }
        }

        public static Config defaults() {
            return new Config(1, 0.1,
                    Duration.ofMillis(40).toNanos(), Duration.ofMillis(30).toNanos(),
                    Duration.ofMillis(100).toNanos(), Duration.ofSeconds(5).toNanos(),
                    0.85, 0.78,
                    Duration.ofMillis(100).toNanos(), Duration.ofSeconds(2).toNanos());
        }
    }

    public record Decision(boolean allowed, Reason reason, long intervalNanos, double tickEwmaNanos) {}

    private final Config config;
    private boolean hasTick;
    private boolean tickPaused;
    private boolean heapPaused;
    private boolean hasSpike;
    private boolean hasSubmission;
    private double tickEwmaNanos;
    private long lastSpikeNanos;
    private long lastSubmissionNanos;

    public LoadGate(Config config) {
        this.config = java.util.Objects.requireNonNull(config, "config");
    }

    public void recordTick(long durationNanos, long nowNanos) {
        if (durationNanos < 0) {
            throw new IllegalArgumentException("Tick duration must be non-negative");
        }
        tickEwmaNanos = hasTick
                ? tickEwmaNanos + config.tickEwmaAlpha() * (durationNanos - tickEwmaNanos)
                : durationNanos;
        hasTick = true;
        if (tickEwmaNanos >= config.tickPauseNanos()) {
            tickPaused = true;
        } else if (tickEwmaNanos <= config.tickRecoverNanos()) {
            tickPaused = false;
        }
        if (durationNanos > config.spikeTickNanos()) {
            hasSpike = true;
            lastSpikeNanos = nowNanos;
        }
    }

    public Decision evaluate(long nowNanos, double heapFraction, int inFlight) {
        if (!Double.isFinite(heapFraction) || heapFraction < 0 || heapFraction > 1 || inFlight < 0) {
            throw new IllegalArgumentException("Invalid heap usage or in-flight count");
        }
        if (heapFraction >= config.heapPauseFraction()) {
            heapPaused = true;
        } else if (heapFraction <= config.heapRecoverFraction()) {
            heapPaused = false;
        }
        long interval = currentIntervalNanos();
        Reason reason;
        if (!hasTick) {
            reason = Reason.WAITING_FOR_TICK;
        } else if (inFlight >= config.maxInFlight()) {
            reason = Reason.IN_FLIGHT_LIMIT;
        } else if (heapPaused) {
            reason = Reason.HEAP_PRESSURE;
        } else if (hasSpike && nowNanos - lastSpikeNanos < config.spikeCooldownNanos()) {
            reason = Reason.RECENT_TICK_SPIKE;
        } else if (tickPaused) {
            reason = Reason.TICK_PRESSURE;
        } else if (hasSubmission && nowNanos - lastSubmissionNanos < interval) {
            reason = Reason.SUBMISSION_INTERVAL;
        } else {
            reason = Reason.READY;
        }
        return new Decision(reason == Reason.READY, reason, interval, tickEwmaNanos);
    }

    /** Call only after a task was actually admitted, including a retry. */
    public void recordSubmission(long nowNanos) {
        hasSubmission = true;
        lastSubmissionNanos = nowNanos;
    }

    private long currentIntervalNanos() {
        double pressure = Math.min(1.0, tickEwmaNanos / config.tickPauseNanos());
        long extra = Math.round((config.maxSubmissionIntervalNanos() - config.minSubmissionIntervalNanos())
                * pressure * pressure);
        // Clamp after rounding: extreme custom long values must not overflow.
        return config.minSubmissionIntervalNanos()
                + Math.min(extra, config.maxSubmissionIntervalNanos() - config.minSubmissionIntervalNanos());
    }
}
