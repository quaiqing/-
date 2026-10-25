
package com.example.timedclickerms

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity() {
    private lateinit var xInput: EditText
    private lateinit var yInput: EditText
    private lateinit var timeInput: EditText
    private lateinit var offsetInput: EditText
    private lateinit var networkRadio: RadioButton
    private lateinit var earlyRadio: RadioButton
    private lateinit var currentTimeText: TextView
    private lateinit var countdownText: TextView
    private lateinit var networkStatus: TextView
    private lateinit var clockOffsetText: TextView
    private lateinit var resultText: TextView
    private lateinit var serviceStatus: TextView

    // Mapping from a selected wall-clock source to monotonic elapsedRealtimeNanos().
    @Volatile private var networkOffsetNanos = 0L
    @Volatile private var running = false
    private var job: Job? = null
    private val zone = ZoneId.of("Asia/Shanghai")
    private val formatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        xInput = findViewById(R.id.xInput)
        yInput = findViewById(R.id.yInput)
        timeInput = findViewById(R.id.timeInput)
        offsetInput = findViewById(R.id.offsetInput)
        networkRadio = findViewById(R.id.networkRadio)
        earlyRadio = findViewById(R.id.earlyRadio)
        currentTimeText = findViewById(R.id.currentTimeText)
        countdownText = findViewById(R.id.countdownText)
        networkStatus = findViewById(R.id.networkStatus)
        clockOffsetText = findViewById(R.id.clockOffsetText)
        resultText = findViewById(R.id.resultText)
        serviceStatus = findViewById(R.id.serviceStatus)

        findViewById<Button>(R.id.accessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.pickButton).setOnClickListener {
            Toast.makeText(this, "请输入屏幕 X/Y 坐标；屏幕取点将在下一版本加入。", Toast.LENGTH_LONG).show()
        }
        findViewById<Button>(R.id.startButton).setOnClickListener { startTiming() }
        findViewById<Button>(R.id.stopButton).setOnClickListener { stopTiming() }

        if (timeInput.text.isEmpty()) timeInput.setText("09:30:15.123")
        networkRadio.setOnCheckedChangeListener { _, checked ->
            if (checked) syncNetworkTime()
        }

        // UI monitor is separate from the timing loop.
        CoroutineScope(Dispatchers.Main.immediate).launch {
            while (isActive) {
                renderClock()
                delay(20)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        serviceStatus.text = if (isAccessibilityEnabled()) "无障碍服务：已开启"
        else "无障碍服务：未开启"
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun sourceNowNanos(): Long {
        return if (networkRadio.isChecked)
            SystemClock.elapsedRealtimeNanos() + networkOffsetNanos
        else
            System.currentTimeMillis() * 1_000_000L
    }

    private fun renderClock() {
        val wallMs = if (networkRadio.isChecked) {
            (System.currentTimeMillis() * 1_000_000L + networkOffsetNanos) / 1_000_000L
        } else System.currentTimeMillis()

        val text = Instant.ofEpochMilli(wallMs).atZone(zone).format(formatter)
        currentTimeText.text = text
        clockOffsetText.text = "时间偏差：%.3f ms".format(Locale.US, networkOffsetNanos / 1_000_000.0)

        if (running) {
            val target = computeTargetMonotonicNanos()
            val remain = target - sourceNowNanos()
            countdownText.text = if (remain > 0)
                "%.3f ms".format(Locale.US, remain / 1_000_000.0)
            else "0.000 ms"
        }
    }

    private fun computeTargetMonotonicNanos(): Long {
        val text = timeInput.text.toString().trim()
        val parts = text.split(":")
        require(parts.size == 3) { "时间必须是 HH:mm:ss.SSS" }
        val h = parts[0].toInt()
        val m = parts[1].toInt()
        val secMs = parts[2].split(".")
        val s = secMs[0].toInt()
        val ms = if (secMs.size > 1) secMs[1].padEnd(3, '0').take(3).toInt() else 0
        require(h in 0..23 && m in 0..59 && s in 0..59 && ms in 0..999)

        val nowSource = sourceNowNanos()
        val nowWallMs = if (networkRadio.isChecked)
            (System.currentTimeMillis() * 1_000_000L + networkOffsetNanos) / 1_000_000L
        else System.currentTimeMillis()
        val today = Instant.ofEpochMilli(nowWallMs).atZone(zone).toLocalDate()
        var targetWall = today.atTime(h, m, s, ms * 1_000_000).atZone(zone).toInstant().toEpochMilli()
        if (targetWall <= nowWallMs) targetWall += 86_400_000L

        val correctionMs = offsetInput.text.toString().toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
        if (earlyRadio.isChecked) targetWall -= (correctionMs * 1_000_000.0).toLong()
        else targetWall += (correctionMs * 1_000_000.0).toLong()

        return if (networkRadio.isChecked) {
            // Network wall target mapped to elapsedRealtime through the calibrated offset.
            val wallNowNanos = nowWallMs * 1_000_000L
            nowSource + (targetWall * 1_000_000L - wallNowNanos)
        } else {
            // System wall clock is mapped to elapsedRealtime at launch of the wait.
            val elapsedNow = SystemClock.elapsedRealtimeNanos()
            elapsedNow + (targetWall - nowWallMs) * 1_000_000L
        }
    }

    private fun startTiming() {
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        val x = xInput.text.toString().toFloatOrNull()
        val y = yInput.text.toString().toFloatOrNull()
        if (x == null || y == null || x < 0 || y < 0) {
            Toast.makeText(this, "请输入有效 X/Y", Toast.LENGTH_SHORT).show()
            return
        }
        try { computeTargetMonotonicNanos() } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "时间格式错误", Toast.LENGTH_SHORT).show()
            return
        }
        if (networkRadio.isChecked) syncNetworkTime()
        running = true
        job?.cancel()
        job = CoroutineScope(Dispatchers.Default).launch {
            try {
                val target = computeTargetMonotonicNanos()
                waitPrecisely(target)
                if (!running) return@launch
                val before = SystemClock.elapsedRealtimeNanos()
                val ok = ClickAccessibilityService.instance?.click(x, y, 100L) ?: false
                val after = SystemClock.elapsedRealtimeNanos()
                val fireDeltaUs = (before - target) / 1000.0
                withContext(Dispatchers.Main) {
                    running = false
                    resultText.text = if (ok)
                        "状态：已发送点击\n触发时间误差：%.3f ms\n手势时长：%.3f ms".format(
                            Locale.US, fireDeltaUs / 1000.0, (after - before) / 1_000_000.0)
                    else "状态：点击发送失败"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    running = false
                    resultText.text = "状态：${e.message ?: "失败"}"
                }
            }
        }
        resultText.text = "状态：高精度等待中"
    }

    private suspend fun waitPrecisely(target: Long) {
        while (true) {
            val remain = target - SystemClock.elapsedRealtimeNanos()
            if (remain <= 0) return
            if (remain > 5_000_000L) {
                delay((remain / 1_000_000L - 2).coerceAtLeast(1))
            } else if (remain > 500_000L) {
                Thread.yield()
            } else {
                // Final sub-millisecond phase: busy wait on monotonic clock.
                while (SystemClock.elapsedRealtimeNanos() < target) {
                    Thread.onSpinWait()
                }
                return
            }
        }
    }

    private fun syncNetworkTime() {
        networkStatus.text = "网络校时：采样中…"
        CoroutineScope(Dispatchers.IO).launch {
            val samples = mutableListOf<Long>()
            repeat(7) {
                try {
                    val t0 = System.nanoTime()
                    val conn = (URL("https://www.baidu.com").openConnection() as HttpURLConnection)
                    conn.connectTimeout = 2500
                    conn.readTimeout = 2500
                    conn.requestMethod = "HEAD"
                    conn.connect()
                    val serverMs = conn.getHeaderFieldDate("Date", 0L)
                    val t1 = System.nanoTime()
                    conn.disconnect()
                    if (serverMs > 0) {
                        val midpointMs = System.currentTimeMillis() + ((t1 - t0) / 2L) / 1_000_000L
                        samples += serverMs - midpointMs
                    }
                } catch (_: Exception) {}
            }
            withContext(Dispatchers.Main) {
                if (samples.isNotEmpty()) {
                    val sorted = samples.sorted()
                    val medianMs = sorted[sorted.size / 2]
                    networkOffsetNanos = medianMs * 1_000_000L
                    networkStatus.text = "网络校时：已校准（${medianMs} ms）"
                } else {
                    networkStatus.text = "网络校时：失败"
                }
            }
        }
    }

    private fun stopTiming() {
        running = false
        job?.cancel()
        countdownText.text = "0.000 ms"
        resultText.text = "状态：已停止"
    }

    override fun onDestroy() {
        stopTiming()
        super.onDestroy()
    }
}
