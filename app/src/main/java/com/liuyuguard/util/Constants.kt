package com.liuyuguard.util

/**
 * 全局常量定义
 */
object Constants {

    // 网络接口名
    const val INTERFACE_WIFI = "wlan0"
    const val INTERFACE_CELLULAR_PREFIX = "rmnet_data"

    // 内核流量统计文件
    const val PROC_QTAGUID_STATS = "/proc/net/xt_qtaguid/stats"

    // iptables 链名
    const val IPTABLES_CHAIN_INGRESS = "LYGUARD_INGRESS"
    const val IPTABLES_CHAIN_EGRESS = "LYGUARD_EGRESS"

    // 规则持久化目录
    const val RULES_CACHE_DIR = "/data/local/tmp/liuyuguard"
    const val RULES_CACHE_FILE = "iptables_rules.json"

    // 采样间隔（毫秒）
    const val SAMPLING_INTERVAL_MS = 10_000L     // 10秒
    const val MINUTE_SETTLE_MS = 60_000L          // 1分钟结算
    const val HOUR_ARCHIVE_MS = 3_600_000L        // 1小时归档

    // 闲置检测
    const val DEFAULT_IDLE_TIMEOUT_MINUTES = 5
    const val IDLE_CHECK_INTERVAL_MS = 30_000L    // 30秒轮询

    // 守护心跳
    const val GUARD_HEARTBEAT_MS = 5_000L
    const val GUARD_RESTART_DELAY_MS = 3_000L

    // OOM优先级（Root模式下提升服务优先级）
    const val OOM_ADJ_FOREGROUND_SERVICE = -800

    // 通知ID
    const val NOTIFICATION_ID_TRAFFIC = 1001
    const val NOTIFICATION_ID_GUARD = 1002
    const val NOTIFICATION_ID_PERMISSION = 1003

    // 通知渠道
    const val CHANNEL_TRAFFIC = "traffic_service"
    const val CHANNEL_GUARD = "guard_service"
    const val CHANNEL_PERMISSION = "permission_alert"

    // DataStore 名称
    const val DATASTORE_NAME = "liuyuguard_settings"

    // 偏好键
    const val KEY_RUN_MODE = "run_mode"
    const val KEY_HIGH_PRECISION = "high_precision_mode"
    const val KEY_IDLE_BLOCK_ENABLED = "idle_block_enabled"
    const val KEY_IDLE_TIMEOUT = "idle_timeout_minutes"
    const val KEY_THEME_MODE = "theme_mode"
    const val KEY_BLOCKED_UIDS_WIFI = "blocked_uids_wifi"
    const val KEY_BLOCKED_UIDS_CELLULAR = "blocked_uids_cellular"
    const val KEY_PERSISTED_RULES = "persisted_iptables_rules"
}