package net.shiroha233.roadweaver.pathfinding.cache.opencl;

import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.runtime.LevelScopedState;

/**
 * OpenCL 粗采样支持状态。
 */
public final class OpenCLWorldSupport {
    private static final LevelScopedState<AtomicReference<String>> REASONS = new LevelScopedState<>();

    private OpenCLWorldSupport() {}

    public static boolean isUnsupported(ServerLevel level) {
        return unsupportedReason(level) != null;
    }

    public static String unsupportedReason(ServerLevel level) {
        AtomicReference<String> reason = REASONS.peek(level);
        return reason == null ? null : reason.get();
    }

    public static boolean markUnsupported(ServerLevel level, String reason) {
        return REASONS.get(level, AtomicReference::new).compareAndSet(null, reason == null || reason.isBlank() ? "unknown" : reason);
    }

    public static void clear() {
        REASONS.clear();
    }

    public static void clear(ServerLevel level) { REASONS.remove(level); }
}
