package cacom.example.smarthome

import android.app.AlertDialog
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONException
import org.json.JSONObject
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    // ========== UI 状态变量（使用 Compose 的可观察状态）==========

    // 心率显示值，初始为 "30BPM"，由 MQTT 消息更新后界面自动刷新
    private var hrVal by mutableStateOf("30BPM")
    // 体温显示值，初始为 "30℃"
    private var tempVal by mutableStateOf("30℃")
    // 步数显示值
    private var stepVal by mutableStateOf("30")
    // 运动持续时间，单位秒
    private var durVal by mutableStateOf("30s")
    // 运动里程，单位米
    private var distVal by mutableStateOf("30m")
    // 心率报警阈值，由硬件端同步
    private var heartThreshold by mutableStateOf("1")
    // 体温报警阈值，由硬件端同步
    private var tempThreshold by mutableStateOf("1")
    // 日期时间字符串，显示在界面上，每秒更新一次
    private var timeShow by mutableStateOf("日期时间")
    // 阈值设置模式开关，true 表示手动阈值模式，false 表示自动模式
    private var thresholdMode by mutableStateOf(false)

    // ========== 时间相关字段（用于向硬件校时）==========

    // 时间字符串，格式为 HHmmss，例如 "153045"
    private var timeString = ""
    // 日期字符串，格式为 YYYYmmdd，例如 "20240520"
    private var dateString = ""

    // ========== MQTT 相关字段 ==========

    // MQTT 客户端 ID，由前缀加随机 5 位数字组成，确保唯一性
    private val mqttClientId = "sadjk${((Math.random() * 9 + 1) * 10000).toInt()}"
    // 断线重连协程任务句柄，用于取消重连循环
    private var reconnectJob: Job? = null
    // 时间更新协程任务句柄，用于取消时间刷新循环
    private var timeJob: Job? = null
    // MQTT 客户端实例，负责与 Broker 通信
    private var client: MqttClient? = null
    // MQTT 连接配置项，包含用户名、密码、超时等参数
    private var connectOptions: MqttConnectOptions? = null
    // 独立的协程作用域，专门用于 Activity 销毁时的 MQTT 资源清理，
    // 使用 SupervisorJob 保证即使子任务失败也不影响其他任务
    private val mqttCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // MQTT Broker 服务器地址及端口（TCP 协议）
    private val host = "tcp://47.109.89.8:1883"
    // MQTT Broker 登录用户名
    private val userName = "root23"
    // MQTT Broker 登录密码
    private val passWord = "root34"
    // MQTT 订阅主题，由用户登录时输入的"用户名"字段决定
    private var mqttSubTopic = ""
    // MQTT 发布主题，由用户登录时输入的"密码"字段决定
    private var mqttPubTopic = ""
    // SharedPreferences 实例，用于本地持久化存储用户输入的主题信息
    private lateinit var sharedPreferences: SharedPreferences
    // 登录对话框引用，便于在 Activity 销毁时及时关闭，防止窗口泄漏
    private var loginDialog: AlertDialog? = null

    /**
     * Activity 生命周期回调：创建阶段。
     *
     * 该方法在 Activity 首次创建时由系统调用，完成以下初始化工作：
     * 1. 初始化 SharedPreferences，用于读写本地存储的主题配置；
     * 2. 通过 setContent 设置 Jetpack Compose UI 界面，将所有状态变量
     *    和事件回调传入根 Composable 函数 SmartHomeScreen；
     * 3. 启动时间更新协程，使界面每秒刷新一次日期时间；
     * 4. 弹出登录对话框，让用户确认订阅/发布主题后再初始化 MQTT 连接。
     *
     * @param savedInstanceState 系统保存的实例状态（如屏幕旋转时恢复数据）
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 获取名为 "UserData" 的 SharedPreferences 文件，用于存储用户输入的主题
        sharedPreferences = getSharedPreferences("UserData", MODE_PRIVATE)

        // 使用 Jetpack Compose 设置界面内容，将所有 UI 状态和交互回调传入
        setContent {
            SmartHomeScreen(
                hrVal = hrVal,
                tempVal = tempVal,
                stepVal = stepVal,
                durVal = durVal,
                distVal = distVal,
                timeShow = timeShow,
                thresholdMode = thresholdMode,
                heartThreshold = heartThreshold,
                tempThreshold = tempThreshold,
                // 阈值模式切换回调：更新本地状态，并向硬件发布对应指令
                onThresholdModeChange = { checked ->
                    thresholdMode = checked
                    publishmessageplus(mqttPubTopic, if (checked) "ThresholdMode" else "Automatic")
                },
                // 心率阈值减少按钮回调：向硬件发送 "HeartThresholdDown" 指令
                onHeartThresholdDown = { publishmessageplus(mqttPubTopic, "HeartThresholdDown") },
                // 心率阈值增加按钮回调：向硬件发送 "HeartThresholdAdd" 指令
                onHeartThresholdAdd = { publishmessageplus(mqttPubTopic, "HeartThresholdAdd") },
                // 体温阈值减少按钮回调
                onTempThresholdDown = { publishmessageplus(mqttPubTopic, "TempThresholdDown") },
                // 体温阈值增加按钮回调
                onTempThresholdAdd = { publishmessageplus(mqttPubTopic, "TempThresholdAdd") },
                // 时间校正按钮回调：将当前时间和日期拼接成指令发送给硬件
                // 指令格式：Ntime + HHmmss + YYYYmmdd，例如 "Ntime15304520240520"
                onSetTime = { publishmessageplus(mqttPubTopic, "Ntime$timeString$dateString") },
            )
        }

        // 启动时间刷新协程，每隔 1 秒更新一次 timeShow、timeString、dateString
        startTimeUpdates()
        // 弹出主题配置对话框，用户确认后再执行 MQTT 初始化与重连
        showLoginDialog {
            initializeAfterLogin()
        }
    }

    /**
     * Activity 生命周期回调：销毁阶段。
     *
     * 当 Activity 被系统销毁（用户退出或系统回收）时调用，负责释放所有资源：
     * 1. 取消重连协程和时间更新协程，防止内存泄漏；
     * 2. 关闭登录对话框（若仍在显示），防止 WindowLeaked 异常；
     * 3. 在独立 IO 协程中断开并关闭 MQTT 连接，避免阻塞主线程。
     */
    override fun onDestroy() {
        super.onDestroy()
        // 取消断线重连协程，停止循环检测
        reconnectJob?.cancel()
        // 取消时间更新协程，停止定时刷新
        timeJob?.cancel()
        // 关闭可能还在显示的登录对话框
        loginDialog?.dismiss()
        // 在 IO 线程中执行 MQTT 资源清理，避免在主线程执行网络操作
        mqttCleanupScope.launch {
            try {
                // 若当前已连接，则先断开连接
                if (client?.isConnected == true) {
                    client?.disconnect()
                }
                // 释放 MQTT 客户端占用的底层资源
                client?.close()
            } catch (e: MqttException) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 显示主题配置登录对话框。
     *
     * 弹出一个不可取消的 AlertDialog，要求用户输入订阅主题（用户名字段）
     * 和发布主题（密码字段）。若本地已有保存的配置，则自动填入，方便用户
     * 直接确认而无需重新输入。
     *
     * 验证规则：两个输入框均不能为空，否则显示错误提示并阻止提交。
     * 验证通过后：保存配置到本地、设置 MQTT 主题，并通过回调通知外部继续初始化。
     *
     * @param onSuccess 用户确认成功后执行的回调函数（用于触发 MQTT 初始化）
     */
    private fun showLoginDialog(onSuccess: () -> Unit) {
        // 从 XML 布局文件加载对话框视图
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_login, null)
        // 获取输入框控件引用
        val etUsername = dialogView.findViewById<EditText>(R.id.et_username)
        val etPassword = dialogView.findViewById<EditText>(R.id.et_password)

        // 读取本地已保存的主题配置，若无则默认为空字符串
        val savedUsername = sharedPreferences.getString("username", "") ?: ""
        val savedPassword = sharedPreferences.getString("password", "") ?: ""
        // 将已保存的值回填到输入框，提升用户体验
        etUsername.setText(savedUsername)
        etPassword.setText(savedPassword)

        // 构建对话框：首次使用显示"首次输入"，已有配置则显示"确认主题"
        loginDialog = AlertDialog.Builder(this)
            .setTitle(if (savedUsername.isEmpty()) "首次输入" else "确认主题")
            .setView(dialogView)
            .setPositiveButton("确定", null) // 设为 null，在 setOnShowListener 中手动处理，以实现输入校验
            .setNegativeButton("取消") { _, _ -> finish() } // 点击取消则直接退出 Activity
            .setCancelable(false) // 禁止点击对话框外部区域关闭，强制用户完成配置
            .create()

        // 在对话框显示后设置确定按钮的点击事件，以便在校验失败时阻止对话框关闭
        loginDialog?.setOnShowListener {
            val positiveButton = loginDialog?.getButton(AlertDialog.BUTTON_POSITIVE) as Button
            positiveButton.setOnClickListener {
                val username = etUsername.text.toString().trim()
                val password = etPassword.text.toString().trim()

                // 清除上次的错误提示
                etUsername.error = null
                etPassword.error = null

                // 输入校验标志
                var hasError = false
                if (username.isEmpty()) {
                    etUsername.error = "不能为空"
                    hasError = true
                }
                if (password.isEmpty()) {
                    etPassword.error = "不能为空"
                    hasError = true
                }

                if (!hasError) {
                    // 校验通过：持久化保存配置
                    saveUserData(username, password)
                    // 将输入的用户名作为 MQTT 订阅主题（接收硬件上报数据）
                    mqttSubTopic = username
                    // 将输入的密码作为 MQTT 发布主题（向硬件发送指令）
                    mqttPubTopic = password
                    loginDialog?.dismiss()
                    // 触发登录成功回调，继续执行 MQTT 初始化
                    onSuccess()
                }
            }
        }

        loginDialog?.show()
    }

    /**
     * 将用户输入的主题配置持久化到 SharedPreferences。
     *
     * 使用 apply() 异步写入，不阻塞调用线程。
     * 下次启动 App 时可通过 SharedPreferences 读取并自动填入对话框。
     *
     * @param username 订阅主题字符串（对话框中的"用户名"输入框内容）
     * @param password 发布主题字符串（对话框中的"密码"输入框内容）
     */
    private fun saveUserData(username: String, password: String) {
        sharedPreferences.edit()
            .putString("username", username)
            .putString("password", password)
            .apply()
    }

    /**
     * 登录成功后的初始化入口。
     *
     * 按顺序执行两步操作：
     * 1. 初始化 MQTT 客户端对象及连接配置；
     * 2. 启动断线重连协程，自动维持与 Broker 的长连接。
     */
    private fun initializeAfterLogin() {
        Mqtt_init()
        startReconnect()
    }

    /**
     * 初始化 MQTT 客户端及连接参数。
     *
     * 创建 MqttClient 实例并配置以下参数：
     * - 持久化方式：MemoryPersistence（内存持久化，App 退出后消息不保留）；
     * - 清除会话：false，断线重连后服务器会重新推送离线消息；
     * - 心跳间隔：20 秒，用于检测连接存活状态；
     * - 连接超时：10 秒，超时后认为连接失败；
     * - 消息回调：通过 MqttCallback 接口处理连接断开、消息到达、消息发送完成三类事件。
     *
     * 当消息到达时，切换到主线程解析 JSON 数据并更新 UI 状态变量。
     */
    private fun Mqtt_init() {
        try {
            // 创建 MQTT 客户端，指定 Broker 地址、客户端唯一 ID 和内存持久化策略
            client = MqttClient(host, mqttClientId, MemoryPersistence())

            // 配置连接选项
            connectOptions = MqttConnectOptions().apply {
                isCleanSession = false          // 保持会话，断线重连后恢复订阅
                userName = this@MainActivity.userName  // Broker 认证用户名
                password = passWord.toCharArray()      // Broker 认证密码（字符数组形式）
                connectionTimeout = 10          // 连接超时时间（秒）
                keepAliveInterval = 20          // 心跳包发送间隔（秒）
            }

            // 注册 MQTT 事件回调
            client?.setCallback(object : MqttCallback {
                /**
                 * 连接断开回调：当与 Broker 的连接意外中断时触发。
                 * 断线后由 startReconnect 中的循环协程负责重新连接，此处仅打印日志。
                 */
                override fun connectionLost(cause: Throwable) {
                    println("connectionLost----------")
                }

                /**
                 * 消息发布完成回调：当 publish 操作完成时触发。
                 * @param token 包含发布结果信息的令牌对象
                 */
                override fun deliveryComplete(token: IMqttDeliveryToken) {
                    println("deliveryComplete---------" + token.isComplete)
                }

                /**
                 * 消息到达回调：当订阅主题收到新消息时触发（在 IO 线程中执行）。
                 * 通过 lifecycleScope 切换到主线程后再解析和更新 UI 状态，
                 * 确保 Compose 状态变量的修改在主线程执行，防止线程安全问题。
                 *
                 * @param topicName 消息来源主题名称
                 * @param message   收到的 MQTT 消息对象，调用 toString() 获取消息体字符串
                 */
                override fun messageArrived(topicName: String, message: MqttMessage) {
                    println("messageArrived----------")
                    val payload = message.toString()
                    // 切换到主线程处理 UI 更新
                    lifecycleScope.launch(Dispatchers.Main) {
                        println(payload)
                        // 解析 JSON 格式的传感器数据并更新各状态变量
                        parseJsonobj(payload)
                    }
                }
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 启动 MQTT 断线自动重连协程。
     *
     * 在 IO 线程中以 10 秒为间隔循环检测 MQTT 连接状态：
     * - 若未连接，则调用 Mqtt_connect() 尝试重新建立连接；
     * - 若已连接，则跳过本次检测，等待下一个间隔。
     *
     * 通过持有 reconnectJob 引用，可在 Activity 销毁时取消该协程，
     * 防止在 Activity 生命周期结束后仍然执行连接操作造成资源泄漏。
     */
    private fun startReconnect() {
        reconnectJob?.cancel() // 先取消旧的重连任务，防止重复启动
        reconnectJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) { // isActive 为 false 时协程被取消，退出循环
                if (client?.isConnected != true) {
                    // 当前未连接，尝试重新连接
                    Mqtt_connect()
                }
                // 等待 10 秒后再次检测
                delay(10_000)
            }
        }
    }

    /**
     * 执行 MQTT 连接操作（挂起函数，在 IO 线程中调用）。
     *
     * 连接成功后执行：
     * 1. 在主线程弹出 Toast 提示用户连接成功；
     * 2. 订阅由用户配置的主题，QoS 级别为 0（最多一次投递，不保证到达）。
     *
     * 连接失败时捕获异常，在主线程弹出失败提示，由重连循环在下一周期重试。
     */
    private suspend fun Mqtt_connect() {
        try {
            if (client?.isConnected != true) {
                // 使用预配置的连接参数建立 TCP 连接并完成 MQTT 握手
                client?.connect(connectOptions)
                // 切换到主线程显示连接成功提示（Toast 必须在主线程调用）
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "MQTT服务器连接成功,等待硬件数据上报", Toast.LENGTH_SHORT).show()
                }
                // 订阅硬件数据上报主题，QoS=0 表示尽力投递一次
                client?.subscribe(mqttSubTopic, 0)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // 切换到主线程显示连接失败提示
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "MQTT服务器连接失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 向指定 MQTT 主题发布消息（异步，在 IO 线程执行）。
     *
     * 发布前检查客户端是否已连接，未连接时直接返回，不执行发布，
     * 防止因网络断开导致的异常。
     * QoS 设为 0，retain 设为 false，即非持久化的即时指令。
     *
     * @param topic    发布目标主题（即用户输入的"发布主题"字符串）
     * @param message2 要发布的消息内容字符串（如 "HeartThresholdAdd"、"ThresholdMode" 等指令）
     */
    private fun publishmessageplus(topic: String, message2: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            // 连接检查：客户端为空或未连接时跳过发布
            if (client == null || client?.isConnected != true) {
                return@launch
            }
            try {
                // 将字符串转为字节数组后发布，QoS=0，非持久化
                client?.publish(topic, message2.toByteArray(), 0, false)
            } catch (e: MqttException) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 解析硬件上报的 JSON 格式传感器数据，并更新界面显示的状态变量。
     *
     * 硬件通过 MQTT 上报的 JSON 数据格式如下（共 7 个传感器字段）：
     * {
     *   "sensor1": "75",   // 心率值（BPM）
     *   "sensor2": "36.5", // 体温值（℃）
     *   "sensor3": "1000", // 步数
     *   "sensor4": "120",  // 运动持续时间（秒）
     *   "sensor5": "800",  // 运动里程（米）
     *   "sensor6": "100",  // 心率报警阈值
     *   "sensor7": "37"    // 体温报警阈值
     * }
     *
     * 解析后为各显示值追加对应单位，直接赋值给 mutableStateOf 状态变量，
     * Compose 框架检测到状态变化后会自动触发相关 UI 组件重新渲染。
     *
     * @param jsonobj 从 MQTT 消息体中获取的 JSON 字符串
     */
    private fun parseJsonobj(jsonobj: String) {
        try {
            val jsonObject = JSONObject(jsonobj)
            // 依次读取各传感器数据字段
            val sensor1 = jsonObject.getString("sensor1")
            val sensor2 = jsonObject.getString("sensor2")
            val sensor3 = jsonObject.getString("sensor3")
            val sensor4 = jsonObject.getString("sensor4")
            val sensor5 = jsonObject.getString("sensor5")
            val sensor6 = jsonObject.getString("sensor6")
            val sensor7 = jsonObject.getString("sensor7")

            // 更新 Compose 状态变量，自动触发 UI 重组（recomposition）
            hrVal = sensor1 + "BPM"   // 心率，追加单位 BPM
            tempVal = sensor2 + "℃"   // 体温，追加单位 ℃
            stepVal = sensor3          // 步数，无单位
            durVal = sensor4 + "s"    // 运动时长，追加单位秒
            distVal = sensor5 + "m"   // 里程，追加单位米
            heartThreshold = sensor6  // 心率阈值，直接显示
            tempThreshold = sensor7   // 体温阈值，直接显示
        } catch (e: JSONException) {
            // JSON 格式不合法时捕获异常，打印堆栈信息，不影响程序运行
            e.printStackTrace()
        }
    }

    /**
     * 启动时间自动更新协程。
     *
     * 在主线程（默认调度器）中以 1 秒为间隔循环调用 updateTime()，
     * 实时更新界面上的日期时间显示文本。
     * 先取消旧任务再启动新任务，防止多次调用时产生多个并行循环。
     */
    private fun startTimeUpdates() {
        timeJob?.cancel()
        timeJob = lifecycleScope.launch {
            while (isActive) {
                updateTime()
                // 每隔 1 秒刷新一次时间显示
                delay(1000)
            }
        }
    }

    /**
     * 获取当前系统时间并更新时间相关的状态变量和字段。
     *
     * 该函数同时维护三个数据：
     * 1. timeShow（Compose 状态）：格式化为 "年月日\n时分秒\n星期X" 的多行显示字符串；
     * 2. timeString（字段）：格式为 HHmmss（如 "153045"），用于向硬件发送校时指令；
     * 3. dateString（字段）：格式为 YYYYmmdd（如 "20240520"），与 timeString 拼接后发送。
     *
     * 月份、日期、时、分、秒均补零为两位数字，以保证向硬件发送的时间格式固定长度。
     */
    private fun updateTime() {
        val calendar = Calendar.getInstance()
        // 读取各时间分量
        val year    = calendar.get(Calendar.YEAR)
        val month   = calendar.get(Calendar.MONTH) + 1  // Calendar.MONTH 从 0 开始，需加 1
        val day     = calendar.get(Calendar.DAY_OF_MONTH)
        val hour    = calendar.get(Calendar.HOUR_OF_DAY) // 24 小时制
        val minute  = calendar.get(Calendar.MINUTE)
        val second  = calendar.get(Calendar.SECOND)
        val weekday = calendar.get(Calendar.DAY_OF_WEEK) // 1=周日，2=周一，…，7=周六

        // 星期名称映射数组，索引对应 Calendar.DAY_OF_WEEK 的值（1~7）
        val daysOfWeek = arrayOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")
        val dayName = daysOfWeek[weekday - 1]

        // 构造界面显示字符串（用于 UI 展示，带中文单位）
        val t  = "${hour}时${minute}分${second}秒"
        val t1 = "${year}年${month}月${day}日"

        // 构造补零的数字字符串（用于向硬件发送校时指令，保证固定格式）
        val monthStr  = month.toString().padStart(2, '0')
        val dayStr    = day.toString().padStart(2, '0')
        val hourStr   = hour.toString().padStart(2, '0')
        val minuteStr = minute.toString().padStart(2, '0')
        val secondStr = second.toString().padStart(2, '0')

        // 更新校时指令所需的时间字段，格式 HHmmss
        timeString = hourStr + minuteStr + secondStr
        // 更新校时指令所需的日期字段，格式 YYYYmmdd
        dateString = year.toString() + monthStr + dayStr
        Log.e("aaa", dateString)
        // 更新界面显示字符串，\r\n 为换行符，分三行显示日期、时间、星期
        timeShow = "$t1\r\n$t\r\n$dayName"
    }
}

// ============================================================
//                    Jetpack Compose UI 层
// ============================================================

/**
 * 智能手环主界面根 Composable 函数。
 *
 * 负责构建整个 App 的 UI 结构，采用 Material3 主题。
 * 界面从上到下依次包含：
 * - 顶部绿色标题栏（固定高度 45dp）；
 * - 可垂直滚动的内容区域，包含：
 *     · 心率、体温、步数、运动时间、里程五个数据卡片（两列网格布局）；
 *     · 时间校正卡片；
 *     · 阈值设置区域（含开关，开关开启后展开阈值调节控件）。
 *
 * 所有数据通过参数传入，所有交互通过回调函数传出，保持 UI 的无状态性，
 * 便于预览和测试。
 *
 * @param hrVal                心率显示字符串，如 "75BPM"
 * @param tempVal              体温显示字符串，如 "36.5℃"
 * @param stepVal              步数字符串，如 "1000"
 * @param durVal               运动时长字符串，如 "120s"
 * @param distVal              里程字符串，如 "800m"
 * @param timeShow             日期时间多行字符串（三行：日期/时间/星期）
 * @param thresholdMode        阈值模式开关状态，true 为手动阈值模式
 * @param heartThreshold       心率阈值字符串
 * @param tempThreshold        体温阈值字符串
 * @param onThresholdModeChange 阈值模式开关变化回调
 * @param onHeartThresholdDown  心率阈值减少按钮回调
 * @param onHeartThresholdAdd   心率阈值增加按钮回调
 * @param onTempThresholdDown   体温阈值减少按钮回调
 * @param onTempThresholdAdd    体温阈值增加按钮回调
 * @param onSetTime             时间校正按钮回调
 */
@Composable
private fun SmartHomeScreen(
    hrVal: String,
    tempVal: String,
    stepVal: String,
    durVal: String,
    distVal: String,
    timeShow: String,
    thresholdMode: Boolean,
    heartThreshold: String,
    tempThreshold: String,
    onThresholdModeChange: (Boolean) -> Unit,
    onHeartThresholdDown: () -> Unit,
    onHeartThresholdAdd: () -> Unit,
    onTempThresholdDown: () -> Unit,
    onTempThresholdAdd: () -> Unit,
    onSetTime: () -> Unit,
) {
    MaterialTheme {
        // Surface 作为整个界面的背景容器，填满屏幕并设置浅灰背景色
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFFF5F9F9),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ---- 顶部标题栏 ----
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(45.dp)
                        .background(Color(0xFF38C47F)), // 绿色背景
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "李飞龙的智能手环",
                        color = Color.White,
                        fontSize = 20.sp,
                    )
                }

                // ---- 可滚动内容区域 ----
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()), // 内容超出屏幕时可上下滚动
                ) {
                    Spacer(modifier = Modifier.height(20.dp))

                    // 第一行：心率 + 体温
                    MetricRow {
                        MetricCard("心率", hrVal, R.mipmap.xinlv)
                        MetricCard("体温", tempVal, R.mipmap.wendu1)
                    }
                    // 第二行：步数 + 运动时间（运动时间图标宽度略窄，设为 31dp）
                    MetricRow {
                        MetricCard("步数", stepVal, R.mipmap.bushu)
                        MetricCard("运动时间", durVal, R.mipmap.yundongshijian, iconWidth = 31)
                    }
                    // 第三行：里程 + 空白占位（右侧留空，保持布局对称）
                    MetricRow {
                        MetricCard("里程", distVal, R.mipmap.licheng)
                        Spacer(modifier = Modifier.weight(1f))
                    }

                    // 时间显示与校正卡片
                    TimeCard(timeShow = timeShow, onSetTime = onSetTime)

                    // 阈值设置区域标题行（含开关）
                    ThresholdHeader(
                        checked = thresholdMode,
                        onCheckedChange = onThresholdModeChange,
                    )
                    // 仅在阈值模式开启时显示阈值调节控件（条件渲染）
                    if (thresholdMode) {
                        ThresholdSettings(
                            heartThreshold = heartThreshold,
                            tempThreshold = tempThreshold,
                            onHeartThresholdDown = onHeartThresholdDown,
                            onHeartThresholdAdd = onHeartThresholdAdd,
                            onTempThresholdDown = onTempThresholdDown,
                            onTempThresholdAdd = onTempThresholdAdd,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 数据卡片行容器 Composable。
 *
 * 提供一个水平排列的行布局，固定高度 130dp，水平内边距 10dp，
 * 子项之间间距 20dp，作为两个 MetricCard 的外层容器。
 *
 * 使用高阶函数 content 接收子 Composable，使调用方可以在此行中
 * 灵活放置任意 RowScope 作用域内的 Composable 组件。
 *
 * @param content 行内子组件的 Composable lambda，在 RowScope 作用域内执行
 */
@Composable
private fun MetricRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(130.dp)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp), // 子项间距 20dp
    ) {
        content()
    }
}

/**
 * 单个传感器数据展示卡片 Composable（在 RowScope 中使用）。
 *
 * 卡片布局为左右两列：
 * - 左侧：上方显示指标名称（粗体，20sp），下方显示当前数值（绿色，16sp）；
 * - 右侧：底部对齐的圆形图标背景框，内嵌对应传感器图标图片。
 *
 * 使用 shadow + RoundedCornerShape 实现圆角卡片阴影效果，
 * 使用 weight(1f) 使卡片在 Row 中均分宽度。
 *
 * @param title     指标名称，如 "心率"、"体温"
 * @param value     当前数值字符串，如 "75BPM"、"36.5℃"
 * @param iconRes   图标资源 ID（来自 mipmap 资源目录）
 * @param iconWidth 图标显示宽度（dp），默认 35dp，特殊图标可自定义
 */
@Composable
private fun RowScope.MetricCard(
    title: String,
    value: String,
    iconRes: Int,
    iconWidth: Int = 35,
) {
    Row(
        modifier = Modifier
            .weight(1f)             // 在 Row 中等分宽度
            .height(120.dp)
            .shadow(8.dp, RoundedCornerShape(10.dp))  // 圆角阴影
            .background(Color.White, RoundedCornerShape(10.dp))
            .padding(10.dp),
    ) {
        // 左侧：指标名称和数值
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            // 指标名称区域，固定高度 40dp，左对齐居中
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = title,
                    color = Color.Black,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            // 数值显示，绿色字体，顶部留 10dp 间距
            Text(
                modifier = Modifier.padding(top = 10.dp),
                text = value,
                color = Color(0xFF38C47F), // 品牌绿色
                fontSize = 16.sp,
            )
        }
        // 右侧：图标区域，底部对齐，圆形浅蓝背景
        Box(
            modifier = Modifier
                .width(50.dp)
                .fillMaxHeight(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .background(Color(0xFFF1F7FC), CircleShape), // 浅蓝圆形背景
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(width = iconWidth.dp, height = 35.dp),
                )
            }
        }
    }
}

/**
 * 时间显示与校正卡片 Composable。
 *
 * 卡片内分为左右两半：
 * - 左半部分：居中显示多行时间字符串（日期、时间、星期三行）；
 * - 右半部分：居中显示"校正"按钮，点击后触发向硬件发送当前时间的指令。
 *
 * 按钮使用天蓝色（0xFF00CFFF）圆角背景，点击事件由外部回调处理。
 *
 * @param timeShow  多行时间显示字符串，格式为 "年月日\r\n时分秒\r\n星期X"
 * @param onSetTime 点击"校正"按钮时触发的回调，用于向硬件发送校时指令
 */
@Composable
private fun TimeCard(timeShow: String, onSetTime: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(130.dp)
            .padding(horizontal = 10.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(120.dp)
                .shadow(8.dp, RoundedCornerShape(10.dp))
                .background(Color.White, RoundedCornerShape(10.dp))
                .padding(10.dp),
        ) {
            // 左半：时间文本，居中显示，支持多行
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = timeShow,
                    color = Color.Black,
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp,
                )
            }
            // 右半：校正按钮，居中显示
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .widthIn(min = 40.dp)         // 最小宽度 40dp，内容较短时不压缩
                        .height(40.dp)
                        .background(Color(0xFF00CFFF), RoundedCornerShape(10.dp)) // 天蓝色按钮
                        .clickable(onClick = onSetTime),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "校正",
                        color = Color.Black,
                        fontSize = 15.sp,
                    )
                }
            }
        }
    }
}

/**
 * 阈值设置区域标题行 Composable。
 *
 * 由三个元素水平排列组成：
 * - 左侧：绿色小圆柱形装饰条（宽 10dp，高 30dp）；
 * - 中间："阈值设置"标题文字，flex 填满剩余宽度；
 * - 右侧：Switch 开关控件，控制是否开启手动阈值模式。
 *
 * Switch 颜色经过自定义：开启时为绿色，关闭时为灰色，与整体配色方案一致。
 *
 * @param checked        开关当前状态
 * @param onCheckedChange 开关状态变化回调
 */
@Composable
private fun ThresholdHeader(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(55.dp)
            .padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 绿色装饰条
        Box(
            modifier = Modifier
                .padding(start = 10.dp)
                .size(width = 10.dp, height = 30.dp)
                .background(Color(0xFF36C57B), CircleShape),
        )
        // 标题文字，weight(1f) 填满剩余宽度
        Text(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(start = 10.dp),
            text = "阈值设置",
            color = Color.Black,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        // 模式切换开关，自定义颜色方案
        Switch(
            modifier = Modifier.padding(end = 10.dp),
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF36C57B),    // 开启时滑块绿色
                checkedTrackColor = Color(0xFF36C57B),    // 开启时轨道绿色
                uncheckedThumbColor = Color(0xFFEEEEEE),  // 关闭时滑块浅灰
                uncheckedTrackColor = Color(0xFFF3F4F5),  // 关闭时轨道浅灰
                uncheckedBorderColor = Color(0xFF999999), // 关闭时边框灰色
            ),
        )
    }
}

/**
 * 阈值调节区域 Composable（仅在阈值模式开启时渲染）。
 *
 * 包含两行阈值调节控件，纵向排列在白色背景区域内：
 * - 第一行：心率阈值调节（图标宽度 17dp）；
 * - 第二行：体温阈值调节（图标宽度 20dp）。
 *
 * 整体高度固定为 155dp，白色背景。
 *
 * @param heartThreshold       当前心率阈值字符串
 * @param tempThreshold        当前体温阈值字符串
 * @param onHeartThresholdDown 心率阈值减小回调
 * @param onHeartThresholdAdd  心率阈值增大回调
 * @param onTempThresholdDown  体温阈值减小回调
 * @param onTempThresholdAdd   体温阈值增大回调
 */
@Composable
private fun ThresholdSettings(
    heartThreshold: String,
    tempThreshold: String,
    onHeartThresholdDown: () -> Unit,
    onHeartThresholdAdd: () -> Unit,
    onTempThresholdDown: () -> Unit,
    onTempThresholdAdd: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(155.dp)
            .background(Color.White),
    ) {
        // 心率阈值调节行
        ThresholdRow(
            label = "1.    心率阈值",
            value = heartThreshold,
            onDown = onHeartThresholdDown,
            onAdd = onHeartThresholdAdd,
            downIconWidth = 17,  // 减号图标宽度
            addIconHeight = 30,  // 加号图标高度
        )
        // 体温阈值调节行
        ThresholdRow(
            label = "2.    体温阈值",
            value = tempThreshold,
            onDown = onTempThresholdDown,
            onAdd = onTempThresholdAdd,
            downIconWidth = 20,
            addIconHeight = 25,
        )
    }
}

/**
 * 单行阈值调节控件 Composable。
 *
 * 布局结构：[标题文字] ← 弹性填充 → [减号按钮] [数值文字] [加号按钮]
 *
 * - 标题文字占满左侧剩余空间（weight(1f)）；
 * - 右侧操作区域最小宽度 130dp，保证按钮和数值不因内容过短而挤压；
 * - 减号和加号按钮使用 ThresholdButton 封装，图标尺寸可独立配置。
 *
 * @param label        阈值名称，如 "1.    心率阈值"
 * @param value        当前阈值显示字符串
 * @param onDown       减少按钮点击回调
 * @param onAdd        增加按钮点击回调
 * @param downIconWidth 减号图标宽度（dp），不同图标素材比例不同
 * @param addIconHeight 加号图标高度（dp）
 */
@Composable
private fun ThresholdRow(
    label: String,
    value: String,
    onDown: () -> Unit,
    onAdd: () -> Unit,
    downIconWidth: Int,
    addIconHeight: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧标签文字
        Text(
            modifier = Modifier.weight(1f),
            text = label,
            color = Color(0xFF333333),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
        // 右侧操作区：减按钮 + 数值 + 加按钮，水平居中排列
        Row(
            modifier = Modifier.widthIn(min = 130.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            // 减少阈值按钮
            ThresholdButton(
                iconRes = R.mipmap.jian,
                iconWidth = downIconWidth,
                iconHeight = 25,
                onClick = onDown,
            )
            // 当前阈值数值，最小宽度 30dp 保证数字有足够显示空间
            Text(
                modifier = Modifier.widthIn(min = 30.dp),
                text = value,
                textAlign = TextAlign.Center,
                color = Color.Black,
            )
            // 增加阈值按钮
            ThresholdButton(
                iconRes = R.mipmap.jia,
                iconWidth = 25,
                iconHeight = addIconHeight,
                onClick = onAdd,
            )
        }
    }
}

/**
 * 阈值调节图标按钮 Composable（加号/减号通用）。
 *
 * 使用 30×30dp 的圆角正方形白色背景包裹图标图片，
 * 整体可点击，点击事件通过 onClick 回调传出。
 * 图标的宽高可独立设置，以适配不同比例的图片素材。
 *
 * @param iconRes   图标资源 ID（R.mipmap.jian 或 R.mipmap.jia）
 * @param iconWidth  图标显示宽度（dp）
 * @param iconHeight 图标显示高度（dp）
 * @param onClick   点击事件回调
 */
@Composable
private fun ThresholdButton(
    iconRes: Int,
    iconWidth: Int,
    iconHeight: Int,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .background(Color.White, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(width = iconWidth.dp, height = iconHeight.dp),
        )
    }
}

/**
 * Android Studio 预览函数，仅用于 UI 设计阶段的可视化预览，不会打包进 APK。
 *
 * 传入固定的测试数据，在 IDE 的 Design 面板中渲染 SmartHomeScreen 的完整布局，
 * 其中 thresholdMode = true 使阈值设置区域可见，便于同时预览所有 UI 组件。
 */
@Preview(showBackground = true)
@Composable
private fun SmartHomeScreenPreview() {
    SmartHomeScreen(
        hrVal = "30BPM",
        tempVal = "30℃",
        stepVal = "30",
        durVal = "30s",
        distVal = "30m",
        timeShow = "日期时间",
        thresholdMode = true,
        heartThreshold = "1",
        tempThreshold = "1",
        onThresholdModeChange = {},
        onHeartThresholdDown = {},
        onHeartThresholdAdd = {},
        onTempThresholdDown = {},
        onTempThresholdAdd = {},
        onSetTime = {},
    )
}
