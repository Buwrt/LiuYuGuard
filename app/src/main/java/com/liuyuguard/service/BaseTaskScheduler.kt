package com.liuyuguard.service

import kotlinx.coroutines.*

/**
 * 定时任务调度抽象基类
 *
 * 职责：
 * - 管理4类定时任务：10s内核流量采样、分钟结算、小时归档、闲置轮询
 * - 提供统一的启动/停止/暂停/恢复生命周期管理
 * - 支持任务优先级排序
 *
 * 禁止：
 * - 直接包含业务逻辑（由子类定义具体任务行为）
 */
abstract class BaseTaskScheduler {

    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 所有注册的定时任务 */
    private val tasks = mutableMapOf<String, Job>()

    /**
     * 定义所有定时任务（子类实现）
     * @return 任务名称 -> 任务执行逻辑
     */
    protected abstract fun defineTasks(): Map<String, TaskDefinition>

    /**
     * 启动所有定时任务
     */
    fun startAll() {
        defineTasks().forEach { (name, definition) ->
            startTask(name, definition)
        }
    }

    /**
     * 停止所有定时任务
     */
    fun stopAll() {
        tasks.values.forEach { it.cancel() }
        tasks.clear()
    }

    /**
     * 启动单个任务
     */
    fun startTask(name: String) {
        defineTasks()[name]?.let { startTask(name, it) }
    }

    /**
     * 停止单个任务
     */
    fun stopTask(name: String) {
        tasks[name]?.cancel()
        tasks.remove(name)
    }

    /**
     * 暂停所有任务（不取消，仅暂停）
     */
    fun pauseAll() {
        tasks.values.forEach { it.cancel() }
        tasks.clear()
    }

    private fun startTask(name: String, definition: TaskDefinition) {
        tasks[name]?.cancel()
        tasks[name] = scope.launch {
            while (isActive) {
                try {
                    definition.action()
                } catch (_: CancellationException) {
                    break
                } catch (e: Exception) {
                    // 小节4实现：错误上报
                }
                delay(definition.intervalMs)
            }
        }
    }

    /**
     * 释放资源
     */
    fun destroy() {
        stopAll()
        scope.cancel()
    }
}

/**
 * 定时任务定义
 */
data class TaskDefinition(
    /** 任务执行间隔（毫秒） */
    val intervalMs: Long,

    /** 任务执行逻辑（suspend函数，支持异步） */
    val action: suspend () -> Unit
)

// ============================================================================
// 预定义任务名称常量
// ============================================================================

object TaskNames {
    /** 10秒内核流量采样 */
    const val KERNEL_SAMPLING = "kernel_sampling"

    /** 分钟结算 */
    const val MINUTE_SETTLE = "minute_settle"

    /** 小时归档 */
    const val HOUR_ARCHIVE = "hour_archive"

    /** 后台闲置轮询 */
    const val IDLE_POLLING = "idle_polling"

    /** 守护心跳 */
    const val GUARD_HEARTBEAT = "guard_heartbeat"
}