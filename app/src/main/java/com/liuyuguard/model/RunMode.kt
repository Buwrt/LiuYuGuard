package com.liuyuguard.model

/**
 * 应用运行模式枚举
 * Root优先 -> Shizuku次之 -> 无权限锁定
 */
enum class RunMode(val modeName: String) {
    /** Root权限：完整高精度内核级管控 */
    ROOT("run_mode_root"),

    /** Shizuku权限：基础弱精度管控 */
    SHIZUKU("run_mode_shizuku"),

    /** 无有效权限：全部功能锁定 */
    LOCKED("run_mode_locked");

    val isFunctional: Boolean get() = this != LOCKED
    val isRoot: Boolean get() = this == ROOT
    val isShizuku: Boolean get() = this == SHIZUKU
}