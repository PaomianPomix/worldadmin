/* 文件职责：定义共享 OpenCL 会话的粗采、精采与维护提交优先级。 */
package net.shiroha233.roadweaver.pathfinding.cache.opencl;

/**
 * 共享设备会话的提交优先级。
 */
enum OpenCLSubmissionPriority {
    ACCURATE,
    COARSE,
    MAINTENANCE
}
