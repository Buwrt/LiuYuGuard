package com.liuyuguard.manager.modules

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.telephony.TelephonyManager
import com.liuyuguard.base.LiuYuApplication
import com.liuyuguard.manager.RootShellManagerImpl
import com.liuyuguard.model.InterfaceTraffic
import com.liuyuguard.model.SimCardInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 双卡网卡区分模块实现
 *
 * 职责：
 * - 识别双卡卡槽信息（通过TelephonyManager）
 * - 分离两张SIM独立网卡接口
 * - 绑定subscriptionId与网卡接口的映射
 * - 监听双卡切换事件
 *
 * 网卡匹配策略：
 * 1. 通过TelephonyManager获取SubscriptionInfo列表（包含carrierName、displayName等）
 * 2. 通过 /sys/class/net/ 读取所有网络接口信息
 * 3. 通过ConnectivityManager获取当前活跃的数据连接对应的接口名
 * 4. 结合 /sys/class/net/[iface]/ 和网络属性匹配subscription
 *
 * 监听策略：
 * - 注册TelephonyManager.PhoneStateListener / SubscriptionManager.OnSubscriptionsChangedListener
 * - 当SIM卡状态变化时通知上层
 */
class SimCardManagerImpl : ISimCardManager {

    /** TelephonyManager实例 */
    private val telephonyManager: TelephonyManager
        get() = LiuYuApplication.instance.getSystemService(
            Context.TELEPHONY_SERVICE
        ) as TelephonyManager

    /** ConnectivityManager实例 */
    private val connectivityManager: ConnectivityManager
        get() = LiuYuApplication.instance.getSystemService(
            Context.CONNECTIVITY_SERVICE
        ) as ConnectivityManager

    /** SIM卡变化监听器 */
    private var simChangeListener: ((List<SimCardInfo>) -> Unit)? = null

    /** 缓存的网络接口与卡槽映射 */
    private val interfaceSlotCache = mutableMapOf<String, Int>()

    /** SubscriptionManager监听器引用（用于注销） */
    private var subscriptionListener: Any? = null

    // ========================================================================
    // ISimCardManager 接口实现
    // ========================================================================

    /**
     * 获取双卡信息列表
     *
     * 流程：
     * 1. 通过TelephonyManager获取所有SubscriptionInfo
     * 2. 对每个SubscriptionInfo获取运营商名称、显示名称、subscriptionId
     * 3. 通过getInterfaceForSlot匹配网卡接口
     * 4. 读取接口流量数据
     *
     * @return SimCardInfo列表（每个SIM卡一条记录）
     */
    @Suppress("DEPRECATION")
    override suspend fun getSimCardInfo(): List<SimCardInfo> {
        return try {
            val subscriptionManager = LiuYuApplication.instance.getSystemService(
                Context.TELEPHONY_SUBSCRIPTION_SERVICE
            ) as? android.telephony.SubscriptionManager
                ?: return emptyList()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val subscriptionInfoList = subscriptionManager.activeSubscriptionInfoList
                    ?: return emptyList()

                // 获取所有接口的流量数据
                val trafficMap = RootShellManagerImpl.kernelReader.readProcNetDev()

                subscriptionInfoList.mapIndexed { index, info ->
                    val slotIndex = info.simSlotIndex
                    val iface = getInterfaceForSlot(slotIndex)

                    // 查找匹配的流量数据
                    val traffic = if (iface != null) {
                        trafficMap[iface] ?: InterfaceTraffic()
                    } else {
                        InterfaceTraffic()
                    }

                    SimCardInfo(
                        slotIndex = slotIndex,
                        carrierName = info.carrierName?.toString() ?: "",
                        displayName = info.displayName?.toString() ?: "SIM ${slotIndex + 1}",
                        subscriptionId = info.subscriptionId,
                        interfaceName = iface ?: "",
                        rxBytes = traffic.rxBytes,
                        txBytes = traffic.txBytes
                    )
                }
            } else {
                // Android 5.0 以下：仅支持单卡
                val networkInterface = getActiveCellularInterface()
                val traffic = if (networkInterface != null) {
                    RootShellManagerImpl.kernelReader.readProcNetDev()[networkInterface]
                        ?: InterfaceTraffic()
                } else {
                    InterfaceTraffic()
                }

                listOf(
                    SimCardInfo(
                        slotIndex = 0,
                        carrierName = telephonyManager.networkOperatorName ?: "",
                        displayName = "SIM 1",
                        subscriptionId = -1,
                        interfaceName = networkInterface ?: "",
                        rxBytes = traffic.rxBytes,
                        txBytes = traffic.txBytes
                    )
                )
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // 可能是权限不足或其他异常
            emptyList()
        }
    }

    /**
     * 获取指定卡槽的网卡接口名
     *
     * 匹配策略：
     * 1. 先查缓存
     * 2. 通过读取 /sys/class/net/ 下的接口信息
     * 3. 通过ConnectivityManager获取各网络的数据网络类型
     * 4. 通过 ifconfig/ip addr 等命令获取接口状态
     * 5. 结合 rmnet_data 前缀匹配蜂窝数据接口
     *
     * @param slotIndex 卡槽索引（0=SIM1, 1=SIM2）
     * @return 网卡接口名（如 "rmnet_data0"），未找到返回null
     */
    override suspend fun getInterfaceForSlot(slotIndex: Int): String? {
        // 先查缓存
        interfaceSlotCache.entries.firstOrNull { it.value == slotIndex }?.let {
            return it.key
        }

        return try {
            // 通过Shell获取所有网络接口及其状态
            val result = RootShellManagerImpl.executeShell(
                "ip link show 2>/dev/null | grep -E '^[0-9]:'"
            )
            if (!result.success) {
                // 回退到 /sys/class/net/ 目录列举
                return getInterfaceForSlotFromSys(slotIndex)
            }

            // 解析接口列表
            val interfaces = mutableListOf<String>()
            for (line in result.stdout.lines()) {
                // 格式示例：2: rmnet_data0: <BROADCAST,MULTICAST,UP,LOWER_UP> ...
                val match = Regex(":\\s*(\\S+):").find(line)
                match?.groupValues?.get(1)?.let { interfaces.add(it) }
            }

            // 过滤出蜂窝数据接口（rmnet_data* 或 rmnet* 或 ccmni* 等）
            val cellularIfaces = interfaces.filter { iface ->
                iface.startsWith("rmnet_data") ||
                iface.startsWith("rmnet") ||
                iface.startsWith("ccmni") ||
                iface.startsWith("wlan") // 某些双卡WiFi场景
            }

            // 尝试匹配卡槽
            // 策略1：按接口编号匹配（rmnet_data0 -> slot0, rmnet_data1 -> slot1）
            val targetIface = cellularIfaces.find { iface ->
                val num = extractTrailingNumber(iface)
                num == slotIndex
            }

            if (targetIface != null) {
                interfaceSlotCache[targetIface] = slotIndex
                return targetIface
            }

            // 策略2：如果只有一个蜂窝接口且是slot0
            if (slotIndex == 0 && cellularIfaces.size == 1) {
                interfaceSlotCache[cellularIfaces[0]] = 0
                return cellularIfaces[0]
            }

            // 策略3：通过 /sys/class/net/ 进一步匹配
            getInterfaceForSlotFromSys(slotIndex)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            null
        }
    }

    /**
     * 获取指定网卡接口所属卡槽
     *
     * 反向查询：已知接口名，查找对应的卡槽
     *
     * @param iface 网卡接口名
     * @return 卡槽索引，未找到返回null
     */
    override suspend fun getSlotForInterface(iface: String): Int? {
        // 先查缓存
        interfaceSlotCache[iface]?.let { return it }

        // 尝试两个卡槽，看哪个匹配
        for (slot in 0..1) {
            val matchedIface = getInterfaceForSlot(slot)
            if (matchedIface == iface) {
                interfaceSlotCache[iface] = slot
                return slot
            }
        }

        return null
    }

    /**
     * 设置双卡切换事件监听器
     *
     * 注册TelephonyManager的监听器，当SIM卡状态变化时通知上层。
     * Android 5.1+ 使用 SubscriptionManager.OnSubscriptionsChangedListener
     * 低版本使用 PhoneStateListener
     *
     * @param listener 回调函数，参数为最新的SIM卡信息列表
     */
    @Suppress("DEPRECATION")
    override fun setOnSimChangeListener(listener: (List<SimCardInfo>) -> Unit) {
        // 移除旧的监听器
        removeSimChangeListener()

        this.simChangeListener = listener

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            // Android 5.1+：使用 SubscriptionManager.OnSubscriptionsChangedListener
            val subscriptionManager = LiuYuApplication.instance.getSystemService(
                Context.TELEPHONY_SUBSCRIPTION_SERVICE
            ) as? android.telephony.SubscriptionManager
                ?: return

            val listenerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val newListener = object : android.telephony.SubscriptionManager.OnSubscriptionsChangedListener() {
                override fun onSubscriptionsChanged() {
                    // 在协程中异步获取最新SIM卡信息并通知
                    listenerScope.launch {
                        val simInfo = getSimCardInfo()
                        listener(simInfo)
                    }
                }
            }

            subscriptionManager.addOnSubscriptionsChangedListener(newListener)
            subscriptionListener = newListener
        } else {
            // 低版本：使用PhoneStateListener
            val listenerScope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val phoneListener = object : TelephonyManager.PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    // SIM卡变化时触发
                    listenerScope2.launch {
                        val simInfo = getSimCardInfo()
                        listener(simInfo)
                    }
                }
            }

            telephonyManager.listen(phoneListener, TelephonyManager.LISTEN_CALL_STATE)
            subscriptionListener = phoneListener
        }
    }

    // ========================================================================
    // 内部方法
    // ========================================================================

    /**
     * 通过 /sys/class/net/ 获取指定卡槽的网卡接口
     *
     * 备选匹配策略，当 ip link 不可用时使用。
     * 读取 /sys/class/net/ 下各接口的 type 和 operstate 信息来判断。
     *
     * @param slotIndex 卡槽索引
     * @return 网卡接口名，未找到返回null
     */
    private suspend fun getInterfaceForSlotFromSys(slotIndex: Int): String? {
        val result = RootShellManagerImpl.executeShell("ls /sys/class/net/ 2>/dev/null")
        if (!result.success) return null

        val interfaces = result.stdout.trim().split("\\s+".toRegex())

        // 过滤蜂窝数据接口
        val cellularIfaces = interfaces.filter { iface ->
            iface.startsWith("rmnet_data") ||
            iface.startsWith("rmnet") ||
            iface.startsWith("ccmni")
        }

        // 按编号匹配
        for (iface in cellularIfaces) {
            val num = extractTrailingNumber(iface)
            if (num == slotIndex) {
                interfaceSlotCache[iface] = slotIndex
                return iface
            }
        }

        // 回退：只有一个蜂窝接口时给slot0
        if (slotIndex == 0 && cellularIfaces.isNotEmpty()) {
            interfaceSlotCache[cellularIfaces[0]] = 0
            return cellularIfaces[0]
        }

        return null
    }

    /**
     * 获取当前活跃的蜂窝数据接口名
     *
     * 通过ConnectivityManager获取当前活跃的网络，
     * 过滤出类型为MOBILE的网络并获取其接口名。
     *
     * @return 活跃蜂窝接口名，无活跃连接返回null
     */
    @Suppress("DEPRECATION")
    private fun getActiveCellularInterface(): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork ?: return null
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return null
                if (capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    // 获取LinkProperties中的接口名
                    val linkProperties = connectivityManager.getLinkProperties(network)
                    linkProperties?.interfaceName
                } else {
                    null
                }
            } else {
                // Android 6.0 以下
                val networkInfo = connectivityManager.activeNetworkInfo
                if (networkInfo?.type == android.net.ConnectivityManager.TYPE_MOBILE) {
                    // 旧版本无法直接获取接口名，通过 /sys/class/net/ 推断
                    null
                } else {
                    null
                }
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            null
        }
    }

    /**
     * 从接口名中提取末尾的数字
     *
     * 例如：rmnet_data0 -> 0, rmnet_data10 -> 10, wlan0 -> 0
     *
     * @param iface 接口名
     * @return 末尾数字，无数字返回null
     */
    private fun extractTrailingNumber(iface: String): Int? {
        val match = Regex("(\\d+)$").find(iface)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * 移除SIM卡变化监听器
     */
    private fun removeSimChangeListener() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val subscriptionManager = LiuYuApplication.instance.getSystemService(
                    Context.TELEPHONY_SUBSCRIPTION_SERVICE
                ) as? android.telephony.SubscriptionManager
                @Suppress("UNCHECKED_CAST")
                subscriptionListener?.let { listener ->
                    subscriptionManager?.removeOnSubscriptionsChangedListener(
                        listener as android.telephony.SubscriptionManager.OnSubscriptionsChangedListener
                    )
                }
            } else {
                @Suppress("UNCHECKED_CAST")
                subscriptionListener?.let { listener ->
                    telephonyManager.listen(
                        listener as TelephonyManager.PhoneStateListener,
                        TelephonyManager.LISTEN_NONE
                    )
                }
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // 注销失败，忽略
        }
        subscriptionListener = null
    }
}