package com.liuyuguard.manager

import com.liuyuguard.manager.modules.*
import com.liuyuguard.model.ShellResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * RootShellManager完整实现
 *
 * 架构位置：层级3（Root指令管理层）
 * 职责：
 * 1. 统一收口所有Shell指令，通过su -c执行
 * 2. 指令排队（ConcurrentLinkedQueue + Mutex保证线程安全）
 * 3. 异常自动重试
 * 4. 生命周期管理（初始化/销毁）
 * 5. 隔离UI层和内核层的直接交互
 *
 * 使用object单例模式，全局唯一入口
 */
object RootShellManagerImpl : BaseRootShellManager() {

    // ========================================================================
    // 子模块实例
    // ========================================================================

    /** 子模块1：UID管理 */
    override val uidManager = UidManagerImpl()

    /** 子模块2：Iptables防火墙 */
    override val iptablesManager = IptablesManagerImpl()

    /** 子模块3：内核文件读取 */
    override val kernelReader = KernelFileReaderImpl()

    /** 子模块4：进程管控 */
    override val processController = ProcessControllerImpl()

    /** 子模块5：双卡网卡区分 */
    override val simCardManager = SimCardManagerImpl()

    // ========================================================================
    // 内部状态
    // ========================================================================

    /** 是否已完成初始化 */
    private var initialized = false

    /** 指令排队队列，保证Shell命令串行执行 */
    private val commandQueue = ConcurrentLinkedQueue<String>()

    /** 协程锁，保证同一时刻只有一条Shell命令在执行 */
    private val shellMutex = Mutex()

    /** Shell执行专用协程作用域 */
    private val shellScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ========================================================================
    // 生命周期
    // ========================================================================

    /**
     * 初始化RootShellManager
     *
     * 流程：
     * 1. 通过执行 `id` 命令验证Root权限
     * 2. 初始化iptables自定义链（LYGUARD_INGRESS / LYGUARD_EGRESS）
     * 3. 从持久化文件恢复之前保存的规则
     *
     * @return true表示初始化成功，false表示失败
     */
    override suspend fun initialize(): Boolean {
        // 防止重复初始化
        if (initialized) return true

        // 第一步：检测Root权限
        val idResult = executeShell("id", retryCount = 0)
        if (!idResult.success || !idResult.stdout.contains("uid=0")) {
            // Root权限验证失败，stdout中应包含 uid=0(root)
            return false
        }

        // 第二步：创建iptables自定义链
        val chainResult = iptablesManager.initChains()
        if (chainResult !is com.liuyuguard.model.CommandResult.Success) {
            // 自定义链初始化失败（可能链已存在，不算致命错误，继续执行）
            // 但如果返回Error，需要记录日志
        }

        // 第三步：恢复持久化规则
        val restoreResult = iptablesManager.restoreRules()
        // 恢复失败不阻塞初始化（可能是首次运行，无持久化文件）
        @Suppress("UNUSED_VARIABLE")
        val restoreIgnored = restoreResult

        initialized = true
        return true
    }

    /**
     * 释放所有资源
     *
     * 流程：
     * 1. 清理iptables自定义链
     * 2. 取消所有Shell协程
     * 3. 重置初始化状态
     */
    override fun destroy() {
        // 清理iptables自定义链（同步调用，因为销毁时可能不在协程中）
        runBlocking {
            iptablesManager.cleanupChains()
        }
        // 取消所有协程
        shellScope.cancel()
        // 清空指令队列
        commandQueue.clear()
        // 重置初始化状态
        initialized = false
    }

    /**
     * 检查是否已初始化
     * @return true表示已完成初始化
     */
    override fun isInitialized(): Boolean = initialized

    // ========================================================================
    // 指令执行核心
    // ========================================================================

    /**
     * 执行Shell命令（带重试和排队）
     *
     * 流程：
     * 1. 将命令加入队列排队
     * 2. 通过Mutex加锁保证串行执行
     * 3. 使用 Runtime.exec("su", "-c", command) 执行
     * 4. 读取stdout和stderr
     * 5. 失败时自动重试retryCount次
     * 6. 返回ShellResult
     *
     * @param command Shell命令字符串
     * @param retryCount 失败重试次数（默认2次）
     * @return ShellResult 包含成功状态、标准输出、标准错误、退出码
     */
    override suspend fun executeShell(command: String, retryCount: Int): ShellResult {
        // 加入队列排队（标记该命令正在等待执行）
        commandQueue.add(command)

        return shellMutex.withLock {
            // 从队列中移除（已开始执行）
            commandQueue.remove(command)

            // 执行命令，支持重试
            var lastResult: ShellResult? = null
            val maxAttempts = 1 + retryCount

            repeat(maxAttempts) { attempt ->
                lastResult = executeShellInternal(command)
                if (lastResult!!.success) {
                    // 执行成功，立即返回
                    return@withLock lastResult!!
                }
                // 执行失败，如果不是最后一次尝试则等待一小段时间再重试
                if (attempt < maxAttempts - 1) {
                    delay(100L * (attempt + 1))
                }
            }

            // 所有重试均失败，返回最后一次的结果
            lastResult!!
        }
    }

    /**
     * 批量执行Shell命令（原子性）
     *
     * 顺序执行所有命令，全部成功才算原子成功。
     * 如果中途某条命令失败，立即停止后续命令执行，返回已执行的结果。
     *
     * @param commands Shell命令列表
     * @return 每条命令的执行结果列表
     */
    override suspend fun executeShellBatch(commands: List<String>): List<ShellResult> {
        val results = mutableListOf<ShellResult>()

        for (cmd in commands) {
            val result = executeShell(cmd)
            results.add(result)
            if (!result.success) {
                // 原子性保证：某条命令失败则中断后续执行
                break
            }
        }

        return results
    }

    // ========================================================================
    // 内部方法
    // ========================================================================

    /**
     * 内部Shell执行方法
     *
     * 通过 Runtime.exec("su", "-c", command) 执行Root命令
     *
     * @param command 要执行的Shell命令
     * @return ShellResult
     */
    private suspend fun executeShellInternal(command: String): ShellResult {
        return withContext(Dispatchers.IO) {
            var process: Process? = null
            try {
                // 通过su -c以Root权限执行命令
                process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))

                // 读取标准输出和标准错误（需要在 waitFor 之前读取，避免缓冲区满导致死锁）
                val stdout = process.inputStream.bufferedReader().use { it.readText() }.trim()
                val stderr = process.errorStream.bufferedReader().use { it.readText() }.trim()

                // 等待命令执行完成
                val exitCode = process.waitFor()

                ShellResult(
                    success = (exitCode == 0),
                    stdout = stdout,
                    stderr = stderr,
                    exitCode = exitCode
                )
            } catch (e: Exception) {
                // 执行异常（如su命令不存在、权限不足等）
                ShellResult(
                    success = false,
                    stdout = "",
                    stderr = e.message ?: "未知执行异常",
                    exitCode = -1
                )
            } finally {
                // 确保进程资源被释放
                process?.destroy()
            }
        }
    }
}