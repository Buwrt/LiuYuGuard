package com.liuyuguard.executor

import com.liuyuguard.model.ShellResult
import com.topjohnwu.libsu.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Root权限内核执行器实现
 *
 * 架构位置：层级4（Linux内核底层执行层）- Root模式具体实现
 * 职责：
 * - 通过libsu库创建并管理Root Shell连接
 * - 以Root身份执行所有Shell命令（iptables、proc读取、进程管理）
 * - 验证Root权限可用性
 *
 * 依赖：libsu库（com.topjohnwu.libsu）
 * 线程安全：Shell实例通过synchronized保护
 */
class RootKernelExecutorImpl : RootKernelExecutor() {

    /** libsu的Shell实例，延迟初始化 */
    private var shell: Shell? = null

    /** Shell实例互斥锁，防止并发操作 */
    private val shellLock = Any()

    // ========================================================================
    // 生命周期管理
    // ========================================================================

    /**
     * 初始化Root Shell连接
     *
     * 流程：
     * 1. 通过libsu Shell.Builder创建Root Shell
     * 2. 设置FLAG_REDIRECT_STDERR将stderr合并到stdout便于解析
     * 3. 执行 id 命令验证Root权限（期望输出 uid=0(root)）
     * 4. 保存Shell实例供后续命令复用
     *
     * @return true表示Root Shell创建成功且权限验证通过，false表示失败
     */
    override suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            synchronized(shellLock) {
                // 使用libsu创建Root Shell，重定向stderr便于统一解析
                val newShell = Shell.Builder.create()
                    .setFlags(Shell.FLAG_REDIRECT_STDERR)
                    .build()

                // 执行 id 命令验证Root权限
                val result = newShell.newJob()
                    .add("id")
                    .to { _, stdout: List<String>, _, _ ->
                        stdout.joinToString("\n")
                    }
                    .exec()

                val output = result.joinToString("\n").trim()

                if (output.contains("uid=0") || output.contains("root")) {
                    shell = newShell
                    true
                } else {
                    // 权限验证失败，关闭Shell
                    newShell.close()
                    false
                }
            }
        } catch (e: Exception) {
            // 创建Shell过程中出现异常（设备未Root、su不可用等）
            shell = null
            false
        }
    }

    /**
     * 执行单条Shell命令
     *
     * 通过libsu的Job机制异步执行命令，解析返回结果为ShellResult。
     * 使用FLAG_REDIRECT_STDERR后，错误信息会被合并到stdout中，
     * 同时也会从stderr回调获取原始错误信息。
     *
     * @param command 要执行的Shell命令字符串
     * @return ShellResult包含执行状态、标准输出、标准错误和退出码
     */
    override suspend fun executeCommand(command: String): ShellResult = withContext(Dispatchers.IO) {
        val currentShell = synchronized(shellLock) { shell }
            ?: return@withContext ShellResult(
                success = false,
                stdout = "",
                stderr = "Shell未初始化",
                exitCode = -1
            )

        try {
            val stdoutBuilder = StringBuilder()
            val stderrBuilder = StringBuilder()

            val result = currentShell.newJob()
                .add(command)
                .to({ _, out: List<String>, _, _ ->
                    out.forEach { stdoutBuilder.appendLine(it) }
                })
                .to({ _, err: List<String>, _, _ ->
                    err.forEach { stderrBuilder.appendLine(it) }
                }, false) // false表示这是stderr回调
                .exec()

            ShellResult(
                success = result.isSuccess,
                stdout = stdoutBuilder.toString().trim(),
                stderr = stderrBuilder.toString().trim(),
                exitCode = if (result.isSuccess) 0 else 1
            )
        } catch (e: Exception) {
            ShellResult(
                success = false,
                stdout = "",
                stderr = "命令执行异常: ${e.message}",
                exitCode = -1
            )
        }
    }

    /**
     * 释放Shell资源
     *
     * 在服务停止或切换执行模式时调用，确保Shell进程被正确关闭。
     */
    override fun destroy() {
        synchronized(shellLock) {
            shell?.close()
            shell = null
        }
    }
}
