package net.shiroha233.roadweaver.pathfinding.terrain.region;

/** CPU-friendly preliminary terrain resolution; A* and final exact-height refinement are separate. */
public final class CoarseSamplingPolicy {
    public static final String PROPERTY = "roadweaver.coarseSampleStep";
    private CoarseSamplingPolicy() {}

    public static int configuredStep() { return parse(System.getProperty(PROPERTY, "32")); }

    public static int sampleStep(int requestedStep) {
        return Math.max(Math.max(1, requestedStep), configuredStep());
    }

    static int parse(String value) {
        return switch (value == null ? "32" : value.trim()) {
            case "8" -> 8;
            case "16" -> 16;
            case "32" -> 32;
            default -> throw new IllegalArgumentException(PROPERTY + " must be 8, 16, or 32 (restart after changing)");
        };
    }
}
