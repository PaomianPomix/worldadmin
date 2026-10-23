package dev.worldadmin;

import dev.worldadmin.pregen.core.LoadGate;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class WorldAdminConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue MAX_IN_FLIGHT;
    public static final ModConfigSpec.IntValue INTERVAL_MS;
    public static final ModConfigSpec.IntValue TIMEOUT_SECONDS;
    public static final ModConfigSpec.IntValue ROAD_TIMEOUT_SECONDS;
    public static final ModConfigSpec.LongValue MAX_JOB_CHUNKS;
    static {
        var b = new ModConfigSpec.Builder();
        b.comment("Conservative defaults for 4 CPU cores / 16 GB RAM. Restart to apply.",
                "These limit optional requests, not Minecraft/mod worker pools or a single slow generator.");
        MAX_IN_FLIGHT = b.defineInRange("maxInFlight", 1, 1, 4);
        INTERVAL_MS = b.defineInRange("minimumSubmissionIntervalMs", 100, 50, 5000);
        TIMEOUT_SECONDS = b.defineInRange("requestTimeoutSeconds", 120, 10, 3600);
        ROAD_TIMEOUT_SECONDS = b.defineInRange("roadPreparationTimeoutSeconds", 600, 30, 3600);
        MAX_JOB_CHUNKS = b.defineInRange("maximumJobChunks", 1_000_000L, 1L, 100_000_000L);
        SPEC = b.build();
    }
    public static LoadGate.Config loadGate() {
        return new LoadGate.Config(MAX_IN_FLIGHT.get(), .1, 40_000_000L, 30_000_000L,
                100_000_000L, 5_000_000_000L, .85, .78,
                INTERVAL_MS.get() * 1_000_000L, Math.max(2000, INTERVAL_MS.get()) * 1_000_000L);
    }
    private WorldAdminConfig() {}
}
