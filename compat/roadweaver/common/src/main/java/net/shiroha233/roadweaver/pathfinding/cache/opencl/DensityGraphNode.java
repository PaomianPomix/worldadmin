/* 文件职责：定义 density graph 的紧凑节点数据布局。 */
package net.shiroha233.roadweaver.pathfinding.cache.opencl;

/**
 * Density graph 紧凑节点。
 */
public record DensityGraphNode(
        DensityGraphNodeType type,
        int left,
        int right,
        int extraA,
        int extraB,
        double valueA,
        double valueB,
        double valueC,
        double valueD
) {}
