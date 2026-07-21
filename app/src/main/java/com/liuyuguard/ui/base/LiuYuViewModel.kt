package com.liuyuguard.ui.base

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.liuyuguard.model.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

// ============================================================================
// UI层基类 ViewModel
// 所有页面ViewModel继承此类，通过接口与业务层通信
// 禁止：直接调用任何Root/Shell/内核操作
// ============================================================================

/**
 * UI层ViewModel基类
 *
 * 规则：
 * 1. UI层仅通过 ICommandDispatcher 下发指令
 * 2. UI层仅通过 ITrafficDataObserver 获取数据
 * 3. UI层禁止直接持有 Service/Manager/Executor 的引用
 * 4. UI销毁不影响后台服务运行
 */
abstract class LiuYuViewModel(
    protected val commandDispatcher: ICommandDispatcher,
    protected val dataObserver: ITrafficDataObserver
) : ViewModel() {

    /** 页面加载状态 */
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** 页面错误消息 */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 运行模式 */
    val runMode: StateFlow<RunMode> = dataObserver.runMode

    /** 服务运行状态 */
    val isServiceRunning: StateFlow<Boolean> = dataObserver.isServiceRunning

    /** 高精度模式 */
    val isHighPrecision: StateFlow<Boolean> = dataObserver.isHighPrecisionMode

    /** 流量总览 */
    val trafficOverview: StateFlow<TrafficOverview?> = dataObserver.trafficOverview

    /**
     * 安全下发指令（自动处理loading和错误）
     */
    protected fun dispatchCommand(
        command: UiCommand,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            when (val result = commandDispatcher.dispatch(command)) {
                is CommandResult.Success -> {
                    onSuccess?.invoke()
                }
                is CommandResult.Error -> {
                    _error.value = result.message
                    onError?.invoke(result.message)
                }
                is CommandResult.PartialSuccess -> {
                    _error.value = result.message
                    onSuccess?.invoke()
                }
            }
            _isLoading.value = false
        }
    }

    /**
     * 清除错误
     */
    fun clearError() {
        _error.value = null
    }
}

// ============================================================================
// 页面状态封装
// ============================================================================

/**
 * 通用页面状态
 */
sealed class PageState<out T> {
    data object Loading : PageState<Nothing>()
    data class Success<T>(val data: T) : PageState<T>()
    data class Error(val message: String) : PageState<Nothing>()
    data object Empty : PageState<Nothing>()
}

/**
 * 将Flow转换为PageState
 */
fun <T> Flow<T>.asPageState(): Flow<PageState<T>> = map<PageState<T>> { PageState.Success(it) }
    .onStart { emit(PageState.Loading) }
    .catch { emit(PageState.Error(it.message ?: "未知错误")) }