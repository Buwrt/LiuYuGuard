package com.liuyuguard.service

import com.liuyuguard.model.CommandResult
import com.liuyuguard.model.Direction
import com.liuyuguard.model.ICommandDispatcher
import com.liuyuguard.model.UiCommand
import com.liuyuguard.util.Constants

/**
 * 指令分发器 - ICommandDispatcher的实现
 *
 * 接收UI层下发的UiCommand，解析并路由到对应的业务处理逻辑。
 * 作为UI层与业务服务层之间的唯一指令通道，确保所有操作有统一的入口和出口。
 *
 * 职责边界：
 * - 解析UiCommand并路由到对应业务逻辑
 * - 通过RootBridge向底层下发网络管控指令
 * - 返回统一的CommandResult结果
 *
 * 禁止：
 * - 直接执行Shell/iptables命令
 * - 包含业务判断逻辑（如阈值判断、闲置检测等）
 */
class CommandDispatcherImpl(
    /** 主流量服务引用，用于获取RootBridge和Repository */
    private val trafficService: MainTrafficService
) : ICommandDispatcher {

    /**
     * 分发UI层指令到对应的业务处理逻辑
     * @param command UI层下发的指令
     * @return 指令执行结果
     */
    override suspend fun dispatch(command: UiCommand): CommandResult {
        return when (command) {
            is UiCommand.StartTrafficService -> handleStartTrafficService()
            is UiCommand.StopTrafficService -> handleStopTrafficService()
            is UiCommand.BlockAppNetwork -> handleBlockAppNetwork(command)
            is UiCommand.UnblockAppNetwork -> handleUnblockAppNetwork(command)
            is UiCommand.SetIdleBlockEnabled -> handleSetIdleBlockEnabled(command)
            is UiCommand.SetHighPrecisionMode -> handleSetHighPrecisionMode(command)
            is UiCommand.SwitchChartPeriod -> handleSwitchChartPeriod(command)
            is UiCommand.UpdateSettings -> handleUpdateSettings(command)
        }
    }

    // ========================================================================
    // 各指令处理方法
    // ========================================================================

    /**
     * 处理：启动流量服务
     * 启动定时任务调度，将服务状态同步到Repository
     */
    private suspend fun handleStartTrafficService(): CommandResult {
        return try {
            trafficService.startScheduledTasks()
            trafficService.repository.setServiceRunning(true)
            CommandResult.Success
        } catch (e: Exception) {
            CommandResult.Error("启动服务失败: ${e.message}")
        }
    }

    /**
     * 处理：停止流量服务
     * 停止定时任务调度，清理运行状态
     */
    private suspend fun handleStopTrafficService(): CommandResult {
        return try {
            trafficService.stopScheduledTasks()
            trafficService.repository.setServiceRunning(false)
            CommandResult.Success
        } catch (e: Exception) {
            CommandResult.Error("停止服务失败: ${e.message}")
        }
    }

    /**
     * 处理：对指定应用断网
     * 通过RootBridge下发iptables规则，拦截指定UID的网络流量
     */
    private suspend fun handleBlockAppNetwork(command: UiCommand.BlockAppNetwork): CommandResult {
        val bridge = trafficService.rootBridge
            ?: return CommandResult.Error("RootBridge未初始化，无法下发断网指令")

        return try {
            val uid = command.uid
            var lastError: CommandResult? = null

            // 下发WiFi断网规则
            if (command.blockWifi) {
                val wifiResult = bridge.addBlockRule(
                    uid = uid,
                    iface = Constants.INTERFACE_WIFI,
                    direction = Direction.EGRESS
                )
                if (wifiResult is CommandResult.Error) {
                    lastError = wifiResult
                }
            }

            // 下发蜂窝数据断网规则
            if (command.blockCellular) {
                val cellularResult = bridge.addBlockRule(
                    uid = uid,
                    iface = Constants.INTERFACE_CELLULAR_PREFIX,
                    direction = Direction.EGRESS
                )
                if (cellularResult is CommandResult.Error) {
                    lastError = cellularResult
                }
            }

            // 如果全部成功返回Success，部分失败返回PartialSuccess
            lastError ?: CommandResult.Success
        } catch (e: Exception) {
            CommandResult.Error("断网指令下发失败: ${e.message}")
        }
    }

    /**
     * 处理：恢复指定应用网络
     * 通过RootBridge移除iptables规则，恢复指定UID的网络访问
     */
    private suspend fun handleUnblockAppNetwork(command: UiCommand.UnblockAppNetwork): CommandResult {
        val bridge = trafficService.rootBridge
            ?: return CommandResult.Error("RootBridge未初始化，无法下发恢复指令")

        return try {
            val uid = command.uid
            var lastError: CommandResult? = null

            // 移除WiFi断网规则
            if (command.unblockWifi) {
                val wifiResult = bridge.removeBlockRule(
                    uid = uid,
                    iface = Constants.INTERFACE_WIFI,
                    direction = Direction.EGRESS
                )
                if (wifiResult is CommandResult.Error) {
                    lastError = wifiResult
                }
            }

            // 移除蜂窝数据断网规则
            if (command.unblockCellular) {
                val cellularResult = bridge.removeBlockRule(
                    uid = uid,
                    iface = Constants.INTERFACE_CELLULAR_PREFIX,
                    direction = Direction.EGRESS
                )
                if (cellularResult is CommandResult.Error) {
                    lastError = cellularResult
                }
            }

            lastError ?: CommandResult.Success
        } catch (e: Exception) {
            CommandResult.Error("恢复网络指令下发失败: ${e.message}")
        }
    }

    /**
     * 处理：设置闲置应用自动断网
     * 更新闲置检测配置，并通知调度器
     */
    private suspend fun handleSetIdleBlockEnabled(command: UiCommand.SetIdleBlockEnabled): CommandResult {
        return try {
            // TODO: 更新DataStore中的闲置配置
            // TODO: 根据enabled决定启动或停止闲置轮询任务
            CommandResult.Success
        } catch (e: Exception) {
            CommandResult.Error("设置闲置断网失败: ${e.message}")
        }
    }

    /**
     * 处理：设置骆驼高精度模式
     * 高精度模式下缩短采样间隔，提升流量统计精度
     */
    private suspend fun handleSetHighPrecisionMode(command: UiCommand.SetHighPrecisionMode): CommandResult {
        return try {
            trafficService.repository.setHighPrecisionMode(command.enabled)
            CommandResult.Success
        } catch (e: Exception) {
            CommandResult.Error("设置高精度模式失败: ${e.message}")
        }
    }

    /**
     * 处理：切换图表统计周期
     * 通知UI层切换日/周/月视图
     */
    private suspend fun handleSwitchChartPeriod(command: UiCommand.SwitchChartPeriod): CommandResult {
        return try {
            // TODO: 根据period重新加载对应周期数据，发射到chartData
            CommandResult.Success
        } catch (e: Exception) {
            CommandResult.Error("切换统计周期失败: ${e.message}")
        }
    }

    /**
     * 处理：更新设置项
     * 通用设置变更，写入DataStore持久化
     */
    private suspend fun handleUpdateSettings(command: UiCommand.UpdateSettings): CommandResult {
        return try {
            // TODO: 根据key写入对应DataStore值
            // TODO: 对特殊key（如KEY_HIGH_PRECISION）联动Repository更新
            CommandResult.Success
        } catch (e: Exception) {
            CommandResult.Error("更新设置失败: ${e.message}")
        }
    }
}
