/* 文件职责：按配置与世界生成器选择精确高度采样后端。 */
package net.shiroha233.roadweaver.pathfinding.cache;

import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.pathfinding.cache.opencl.OpenCLAccurateHeightBackend;

/**
 * 精确高度后端工厂。
 */
public final class AccurateHeightBackends {
    private AccurateHeightBackends() {}

    public static AccurateHeightBackend create(ServerLevel level, CpuAccurateHeightBackend cpu) {
        try {
            if (ConfigService.get().performance().openclAccurateSamplingEnabled()) {
                AccurateHeightBackend opencl = OpenCLAccurateHeightBackend.tryCreate(level, cpu);
                if (opencl != null) {
                    return opencl;
                }
            }
        } catch (Throwable ignored) {
        }
        return cpu;
    }
}
