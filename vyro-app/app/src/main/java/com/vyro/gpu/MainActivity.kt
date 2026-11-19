package com.vyro.gpu

import android.app.PendingIntent
import android.content.*
import android.graphics.*
import android.hardware.usb.*
import android.os.*
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.hoho.android.usbserial.driver.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

class MainActivity : AppCompatActivity() {
    private val ACTION_USB_PERMISSION = "com.vyro.gpu.USB_PERMISSION"
    private lateinit var usbManager: UsbManager
    private var serialPort: UsbSerialPort? = null
    private val isRunning = AtomicBoolean(false)
    private var useHardwareAccel = true

    @Volatile private var coreOutputs = IntArray(8) { (it + 1) * 30 }
    @Volatile private var opsPerSec = 0

    private lateinit var statusText: TextView
    private lateinit var statsText: TextView
    private lateinit var modeBtn: Button
    private lateinit var gpuView: GpuRenderView

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                        openSerialConnection(device)
                    } else {
                        statusText.text = "USB-C 권한 거부됨 (다시 연결해주세요)"
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#090D16"))
            setPadding(32, 48, 32, 32)
        }
        val titleText = TextView(this).apply {
            text = "VYRO_G1 8-Core 하드웨어 가속기"
            textSize = 20f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE); gravity = Gravity.CENTER
        }
        statusText = TextView(this).apply {
            text = "USB-C OTG 케이블로 보드를 연결하세요"
            textSize = 13f; setTextColor(Color.parseColor("#38BDF8")); gravity = Gravity.CENTER; setPadding(0, 8, 0, 24)
        }
        gpuView = GpuRenderView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f)
        }
        statsText = TextView(this).apply {
            text = "초당 가속 연산: 0 OPS | 렌더링: 0 FPS"
            textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#34D399")); gravity = Gravity.CENTER; setPadding(0, 24, 0, 24)
        }
        modeBtn = Button(this).apply {
            text = "현재: VYRO 8-Core 하드웨어 가속 (터치하여 CPU 단독 전환)"
            setBackgroundColor(Color.parseColor("#2563EB")); setTextColor(Color.WHITE)
            setOnClickListener {
                useHardwareAccel = !useHardwareAccel
                text = if (useHardwareAccel) "현재: VYRO 8-Core 하드웨어 가속 (터치하여 CPU 단독 전환)" else "현재: 스마트폰 CPU 단독 연산 (터치하여 8-Core 가속 전환)"
                setBackgroundColor(Color.parseColor(if (useHardwareAccel) "#2563EB" else "#DC2626"))
            }
        }
        val connectBtn = Button(this).apply {
            text = "USB-C 가속기 스캔 및 재연결"
            setBackgroundColor(Color.parseColor("#1E293B")); setTextColor(Color.LTGRAY)
            setOnClickListener { findAndConnectUsb() }
        }
        root.addView(titleText); root.addView(statusText); root.addView(gpuView); root.addView(statsText); root.addView(modeBtn); root.addView(connectBtn)
        setContentView(root)

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(usbReceiver, filter)

        findAndConnectUsb()
    }

    private fun getProber(): UsbSerialProber {
        val customTable = ProbeTable().apply { addProduct(0x359F, 0x0300, CdcAcmSerialDriver::class.java) }
        return UsbSerialProber(customTable)
    }

    private fun findAndConnectUsb() {
        var drivers = getProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) {
            statusText.text = "연결된 USB-C 가속기 없음 (데모 시뮬레이션 중)"
            startWorkerThread(null); return
        }
        val device = drivers[0].device
        if (!usbManager.hasPermission(device)) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            usbManager.requestPermission(device, PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags))
        } else openSerialConnection(device)
    }

    private fun openSerialConnection(device: UsbDevice) {
        var drivers = getProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        val driver = drivers.firstOrNull { it.device == device } ?: return
        val connection = usbManager.openDevice(device) ?: return
        val port = driver.ports[if (driver.ports.size > 1) 1 else 0]
        try {
            port.open(connection)
            port.setParameters(921600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            serialPort = port
            statusText.text = "USB-C 연결 성공! (921,600 bps | 8-Core 온라인)"
            startWorkerThread(port)
        } catch (e: Exception) { statusText.text = "포트 열기 오류: ${e.message}" }
    }

    private fun startWorkerThread(port: UsbSerialPort?) {
        isRunning.set(false); Thread.sleep(30); isRunning.set(true)
        Thread {
            var valA = 10; var valB = 50; var ops = 0; var lastTime = System.currentTimeMillis()
            val rxBuf = ByteArray(16)
            while (isRunning.get()) {
                if (useHardwareAccel && port != null) {
                    try {
                        port.write(byteArrayOf(0xAA.toByte(), valA.toByte(), valB.toByte()), 20)
                        val len = port.read(rxBuf, 20)
                        if (len >= 8) { coreOutputs = IntArray(8) { rxBuf[it].toInt() and 0xFF }; ops++ }
                    } catch (e: Exception) { runOnUiThread { statusText.text = "USB 연결 끊김" }; break }
                } else {
                    coreOutputs = IntArray(8) { i -> (((valA * (i + 3)) shr 2) xor (valB + (i shl 5))) and 0xFF }
                    Thread.sleep(16); ops++
                }
                valA = (valA + 1) and 0xFF; valB = (valB + 3) and 0xFF
                val now = System.currentTimeMillis()
                if (now - lastTime >= 1000) { opsPerSec = ops * 8; ops = 0; lastTime = now }
            }
        }.start()
    }

    inner class GpuRenderView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var frameCount = 0; private var lastFpsTime = System.currentTimeMillis(); private var currentFps = 0
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(Color.parseColor("#05080F"))
            val cx = width / 2f; val cy = height / 2f; val data = coreOutputs; val t = System.currentTimeMillis() * 0.0025
            for (i in 0 until 8) {
                val angle = t + (i * Math.PI / 4.0) + (data[i] * 0.015)
                val radius = (width * 0.15f) + (data[i] * 0.8f)
                paint.color = Color.HSVToColor(floatArrayOf(((i * 45 + data[i]) % 360).toFloat(), 0.85f, 0.95f))
                canvas.drawCircle((cx + cos(angle) * radius).toFloat(), (cy + sin(angle) * radius).toFloat(), 36f + (i * 4f), paint)
            }
            frameCount++
            val now = System.currentTimeMillis()
            if (now - lastFpsTime >= 1000) {
                currentFps = frameCount; frameCount = 0; lastFpsTime = now
                statsText.text = "초당 가속 연산: ${String.format("%,d", opsPerSec)} OPS | 화면: ${currentFps} FPS"
            }
            invalidate()
        }
    }

    override fun onDestroy() {
        isRunning.set(false); try { serialPort?.close() } catch (_: Exception) {}
        unregisterReceiver(usbReceiver); super.onDestroy()
    }
}
