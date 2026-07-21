package com.liuyuguard.executor

import com.liuyuguard.model.*

/**
 * 内核执行层统一抽象基类
 *
 * 架构位置：层级4（Linux内核底层执行层）
 * 职责：
 * - Netfilter/Iptables数据包过滤底层执行
 * - 网卡硬件原始流量字节采集（对接xt_qtaguid）
 * - 内核态进程CPU/前台后台状态监听
 * - 实现 IKernelExecutor 接口供层级3调用
 *
 * 禁止：
 * - 任何UI操作
 * - 任何业务逻辑判断（阈值、闲置等由层级2处理）
 * - 直接与用户交互
 */
abstract class BaseKernelExecutor : IKernelExecutor {

    // ========================================================================
    // IKernelExecutor 基础实现
    // ========================================================================

    /**
     * 执行Shell命令（层级4最底层实现）
     * 子类根据Root/Shizuku环境提供不同实现
     */
    override abstract suspend fun executeCommand(command: String): ShellResult

    /**
     * 批量执行（原子性保证：全部成功才算成功）
     */
    override suspend fun executeCommands(commands: List<String>): List<ShellResult> {
        return commands.map { executeCommand(it) }
    }

    /**
     * 读取文件内容
     */
    override suspend fun readFile(path: String): String? {
        val result = executeCommand("cat $path")
        return if (result.success) result.stdout.trim() else null
    }

    /**
     * 判断文件是否存在
     */
    override suspend fun fileExists(path: String): Boolean {
        val result = executeCommand("test -f $path && echo 'exists' || echo 'not_found'")
        return result.success && result.stdout.trim() == "exists"
    }

    /**
     * 写入文件
     */
    override suspend fun writeFile(path: String, content: String): Boolean {
        val escaped = content.replace("'", "'\\''")
        val result = executeCommand("echo '$escaped' > $path")
        return result.success
    }

    // ========================================================================
    // 执行器初始化
    // ========================================================================

    /**
     * 初始化执行器（检测权限、建立Shell连接）
     * @return 是否初始化成功
     */
    abstract suspend fun initialize(): Boolean

    /**
     * 释放资源
     */
    abstract fun destroy()
}

// ============================================================================
// Root执行器（小节6完整实现）
// ============================================================================

/**
 * Root权限下的内核执行器
 * 通过su执行所有命令
 */
abstract class RootKernelExecutor : BaseKernelExecutor() {
    // 小节6实现：使用libsu库通过su执行命令
}

// ============================================================================
// Shizuku执行器（小节9完整实现）
// ============================================================================

/**
 * Shizuku权限下的内核执行器
 * 通过Shizuku RemoteProcess执行命令（ADB权限级别）
 */
abstract class ShizukuKernelExecutor : BaseKernelExecutor() {
    // 小节9实现：使用Shizuku API执行ADB级别命令
}