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

    private var hrVal by mutableStateOf("30BPM")
    private var tempVal by mutableStateOf("30℃")
    private var stepVal by mutableStateOf("30")
    private var durVal by mutableStateOf("30s")
    private var distVal by mutableStateOf("30m")
    private var heartThreshold by mutableStateOf("1")
    private var tempThreshold by mutableStateOf("1")
    private var timeShow by mutableStateOf("日期时间")
    private var thresholdMode by mutableStateOf(false)

    private var timeString = ""
    private var dateString = ""
    private val mqttClientId = "sadjk${((Math.random() * 9 + 1) * 10000).toInt()}"
    private var reconnectJob: Job? = null
    private var timeJob: Job? = null
    private var client: MqttClient? = null
    private val host = "tcp://47.109.89.8:1883"
    private val userName = "root23"
    private val passWord = "root34"
    private var mqttSubTopic = ""
    private var mqttPubTopic = ""
    private lateinit var sharedPreferences: SharedPreferences
    private var loginDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedPreferences = getSharedPreferences("UserData", MODE_PRIVATE)

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
                onThresholdModeChange = { checked ->
                    thresholdMode = checked
                    publishmessageplus(mqttPubTopic, if (checked) "ThresholdMode" else "Automatic")
                },
                onHeartThresholdDown = { publishmessageplus(mqttPubTopic, "HeartThresholdDown") },
                onHeartThresholdAdd = { publishmessageplus(mqttPubTopic, "HeartThresholdAdd") },
                onTempThresholdDown = { publishmessageplus(mqttPubTopic, "TempThresholdDown") },
                onTempThresholdAdd = { publishmessageplus(mqttPubTopic, "TempThresholdAdd") },
                onSetTime = { publishmessageplus(mqttPubTopic, "Ntime$timeString$dateString") },
            )
        }

        startTimeUpdates()
        showLoginDialog {
            initializeAfterLogin()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        reconnectJob?.cancel()
        timeJob?.cancel()
        loginDialog?.dismiss()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (client?.isConnected == true) {
                    client?.disconnect()
                }
                client?.close()
            } catch (e: MqttException) {
                e.printStackTrace()
            }
        }
    }

    private fun showLoginDialog(onSuccess: Runnable) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_login, null)
        val etUsername = dialogView.findViewById<EditText>(R.id.et_username)
        val etPassword = dialogView.findViewById<EditText>(R.id.et_password)

        val savedUsername = sharedPreferences.getString("username", "") ?: ""
        val savedPassword = sharedPreferences.getString("password", "") ?: ""
        etUsername.setText(savedUsername)
        etPassword.setText(savedPassword)

        loginDialog = AlertDialog.Builder(this)
            .setTitle(if (savedUsername.isEmpty()) "首次输入" else "确认主题")
            .setView(dialogView)
            .setPositiveButton("确定", null)
            .setNegativeButton("取消") { _, _ -> finish() }
            .setCancelable(false)
            .create()

        loginDialog?.setOnShowListener {
            val positiveButton = loginDialog?.getButton(AlertDialog.BUTTON_POSITIVE) as Button
            positiveButton.setOnClickListener {
                val username = etUsername.text.toString().trim()
                val password = etPassword.text.toString().trim()

                etUsername.error = null
                etPassword.error = null

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
                    saveUserData(username, password)
                    mqttSubTopic = username
                    mqttPubTopic = password
                    loginDialog?.dismiss()
                    onSuccess.run()
                }
            }
        }

        loginDialog?.show()
    }

    private fun saveUserData(username: String, password: String) {
        sharedPreferences.edit()
            .putString("username", username)
            .putString("password", password)
            .apply()
    }

    private fun initializeAfterLogin() {
        Mqtt_init()
        startReconnect()
    }

    private fun Mqtt_init() {
        try {
            client = MqttClient(host, mqttClientId, MemoryPersistence())

            val options = MqttConnectOptions()
            options.isCleanSession = false
            options.userName = userName
            options.password = passWord.toCharArray()
            options.connectionTimeout = 10
            options.keepAliveInterval = 20

            client?.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable) {
                    println("connectionLost----------")
                }

                override fun deliveryComplete(token: IMqttDeliveryToken) {
                    println("deliveryComplete---------" + token.isComplete)
                }

                override fun messageArrived(topicName: String, message: MqttMessage) {
                    println("messageArrived----------")
                    val payload = message.toString()
                    lifecycleScope.launch(Dispatchers.Main) {
                        println(payload)
                        parseJsonobj(payload)
                    }
                }
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startReconnect() {
        reconnectJob?.cancel()
        reconnectJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                if (client?.isConnected != true) {
                    Mqtt_connect()
                }
                delay(10_000)
            }
        }
    }

    private suspend fun Mqtt_connect() {
        try {
            if (client?.isConnected != true) {
                client?.connect(null as MqttConnectOptions?)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "MQTT服务器连接成功,等待硬件数据上报", Toast.LENGTH_SHORT).show()
                }
                client?.subscribe(mqttSubTopic, 0)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "MQTT服务器连接失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun publishmessageplus(topic: String, message2: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            if (client == null || client?.isConnected != true) {
                return@launch
            }
            try {
                client?.publish(topic, message2.toByteArray(), 0, false)
            } catch (e: MqttException) {
                e.printStackTrace()
            }
        }
    }

    private fun parseJsonobj(jsonobj: String) {
        try {
            val jsonObject = JSONObject(jsonobj)
            val sensor1 = jsonObject.getString("sensor1")
            val sensor2 = jsonObject.getString("sensor2")
            val sensor3 = jsonObject.getString("sensor3")
            val sensor4 = jsonObject.getString("sensor4")
            val sensor5 = jsonObject.getString("sensor5")
            val sensor6 = jsonObject.getString("sensor6")
            val sensor7 = jsonObject.getString("sensor7")

            hrVal = sensor1 + "BPM"
            tempVal = sensor2 + "℃"
            stepVal = sensor3
            durVal = sensor4 + "s"
            distVal = sensor5 + "m"
            heartThreshold = sensor6
            tempThreshold = sensor7
        } catch (e: JSONException) {
            e.printStackTrace()
        }
    }

    private fun startTimeUpdates() {
        timeJob?.cancel()
        timeJob = lifecycleScope.launch {
            while (isActive) {
                updateTime()
                delay(1000)
            }
        }
    }

    private fun updateTime() {
        val calendar = Calendar.getInstance()
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        val second = calendar.get(Calendar.SECOND)
        val weekday = calendar.get(Calendar.DAY_OF_WEEK)
        val daysOfWeek = arrayOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")
        val dayName = daysOfWeek[weekday - 1]

        val t = "${hour}时${minute}分${second}秒"
        val t1 = "${year}年${month}月${day}日"
        val monthStr = month.toString().padStart(2, '0')
        val dayStr = day.toString().padStart(2, '0')
        val hourStr = hour.toString().padStart(2, '0')
        val minuteStr = minute.toString().padStart(2, '0')
        val secondStr = second.toString().padStart(2, '0')

        timeString = hourStr + minuteStr + secondStr
        dateString = year.toString() + monthStr + dayStr
        Log.e("aaa", dateString)
        timeShow = "$t1\r\n$t\r\n$dayName"
    }
}

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
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFFF5F9F9),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(45.dp)
                        .background(Color(0xFF38C47F)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "智能手环",
                        color = Color.White,
                        fontSize = 20.sp,
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Spacer(modifier = Modifier.height(20.dp))
                    MetricRow {
                        MetricCard("心率", hrVal, R.mipmap.xinlv)
                        MetricCard("体温", tempVal, R.mipmap.wendu1)
                    }
                    MetricRow {
                        MetricCard("步数", stepVal, R.mipmap.bushu)
                        MetricCard("运动时间", durVal, R.mipmap.yundongshijian, iconWidth = 31)
                    }
                    MetricRow {
                        MetricCard("里程", distVal, R.mipmap.licheng)
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    TimeCard(timeShow = timeShow, onSetTime = onSetTime)
                    ThresholdHeader(
                        checked = thresholdMode,
                        onCheckedChange = onThresholdModeChange,
                    )
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

@Composable
private fun MetricRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(130.dp)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        content()
    }
}

@Composable
private fun RowScope.MetricCard(
    title: String,
    value: String,
    iconRes: Int,
    iconWidth: Int = 35,
) {
    Row(
        modifier = Modifier
            .weight(1f)
            .height(120.dp)
            .shadow(8.dp, RoundedCornerShape(10.dp))
            .background(Color.White, RoundedCornerShape(10.dp))
            .padding(10.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
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
            Text(
                modifier = Modifier.padding(top = 10.dp),
                text = value,
                color = Color(0xFF38C47F),
                fontSize = 16.sp,
            )
        }
        Box(
            modifier = Modifier
                .width(50.dp)
                .fillMaxHeight(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .background(Color(0xFFF1F7FC), CircleShape),
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
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .widthIn(min = 40.dp)
                        .height(40.dp)
                        .background(Color(0xFF00CFFF), RoundedCornerShape(10.dp))
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
        Box(
            modifier = Modifier
                .padding(start = 10.dp)
                .size(width = 10.dp, height = 30.dp)
                .background(Color(0xFF36C57B), CircleShape),
        )
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
        Switch(
            modifier = Modifier.padding(end = 10.dp),
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF36C57B),
                checkedTrackColor = Color(0xFF36C57B),
                uncheckedThumbColor = Color(0xFFEEEEEE),
                uncheckedTrackColor = Color(0xFFF3F4F5),
                uncheckedBorderColor = Color(0xFF999999),
            ),
        )
    }
}

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
        ThresholdRow(
            label = "1.    心率阈值",
            value = heartThreshold,
            onDown = onHeartThresholdDown,
            onAdd = onHeartThresholdAdd,
            downIconWidth = 17,
            addIconHeight = 30,
        )
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
        Text(
            modifier = Modifier.weight(1f),
            text = label,
            color = Color(0xFF333333),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
        Row(
            modifier = Modifier.widthIn(min = 130.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            ThresholdButton(
                iconRes = R.mipmap.jian,
                iconWidth = downIconWidth,
                iconHeight = 25,
                onClick = onDown,
            )
            Text(
                modifier = Modifier.widthIn(min = 30.dp),
                text = value,
                textAlign = TextAlign.Center,
                color = Color.Black,
            )
            ThresholdButton(
                iconRes = R.mipmap.jia,
                iconWidth = 25,
                iconHeight = addIconHeight,
                onClick = onAdd,
            )
        }
    }
}

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
