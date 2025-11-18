package com.techinfocom.blescannerkotlin

import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray
import org.json.JSONObject
import java.util.*
import kotlin.math.pow
import kotlin.math.round

class BleScannerService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val restartRunnable = Runnable { restartScanning() }
    private lateinit var bluetoothLeScanner: BluetoothLeScanner
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var isScanning = false
    private lateinit var mqttClient: MqttClient

    // MQTT настройки
    private val mqttBroker = "tcp://192.168.1.195:1883"
    private val mqttTopic = "ble-topic-data/device"
    private var scannerDeviceId: String = ""

    companion object {
        private const val TAG = "BleScannerService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "ble_scanner_channel"
        private const val RESTART_INTERVAL = 4 * 60 * 1000L // 4 минуты (меньше 5)

        private val WHITELIST_ADDRESSES = setOf(
            "48:87:2D:9C:DF:04",
            "48:87:2D:9C:FA:8B",
            "48:87:2D:9D:58:69",
            "C6:43:0D:ED:FC:F2",
            "48:E7:29:A2:7C:A0",
            "48:E7:29:A2:7C:A1"
        )
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result ?: return

            val device = result.device
            val address = device.address

            if (address !in WHITELIST_ADDRESSES) {
                Log.v(TAG, "Устройство не в белом списке: $address")
                return

            }
            Log.d(TAG, "Найдено BLE устройство: $address, RSSI: ${result.rssi}")
            processScanResult(result)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Ошибка сканирования: $errorCode")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        acquireWakeLock() // Добавьте эту строку
        initializeBluetooth()
        initializeMqtt()
        startForegroundService()
        // Планируем периодический перезапуск
        scheduleRestart()
    }
    private fun scheduleRestart() {
        handler.removeCallbacks(restartRunnable)
        handler.postDelayed(restartRunnable, RESTART_INTERVAL)
    }
    private fun restartScanning() {
        Log.d(TAG, "Restarting BLE scanning")
        stopBleScan()
        handler.postDelayed({
            startBleScan()
            scheduleRestart() // Планируем следующий перезапуск
        }, 1000) // Ждем 2 секунды перед перезапуском
    }
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BleScannerService::WakeLock"
        ).apply {
            acquire(10 * 60 * 1000L /*10 minutes*/) // Захватываем на 10 минут
        }
        Log.d(TAG, "WakeLock acquired")
    }
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WakeLock released")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")

        when (intent?.action) {
            "START_SCAN" -> {
                if (!isScanning) {
                    startBleScan()
                }
            }
            "STOP_SCAN" -> {
                stopBleScan()
                stopSelf()
            }
            "RESTART_SCAN" -> {
                restartScanning()
            }
            else -> {
                if (!isScanning) {
                    startBleScan()
                }
            }
        }

        return START_STICKY
    }

    private fun initializeBluetooth() {
        try {
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bluetoothAdapter = bluetoothManager.adapter
            bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
            Log.d(TAG, "Bluetooth инициализирован")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка инициализации Bluetooth: ${e.message}")
        }
    }

    private fun initializeMqtt() {
        try {
            val clientId = "BLE_SCANNER_SERVICE_" + System.currentTimeMillis()
            mqttClient = MqttClient(mqttBroker, clientId, MemoryPersistence())

            val options = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 10
                keepAliveInterval = 60
                isAutomaticReconnect = true
            }

            mqttClient.connect(options)
            Log.d(TAG, "MQTT подключен успешно")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка подключения MQTT: ${e.message}")
        }
    }

    private fun startBleScan() {
        if (!checkPermissions()) {
            Log.e(TAG, "Нет необходимых разрешений для сканирования BLE")
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth выключен")
            return
        }

        try {
            bluetoothLeScanner.startScan(scanCallback)
            isScanning = true
            Log.d(TAG, "BLE сканирование запущено")
        } catch (e: SecurityException) {
            Log.e(TAG, "Ошибка безопасности при сканировании: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запуска сканирования: ${e.message}")
        }
    }

    private fun stopBleScan() {
        if (isScanning) {
            try {
                bluetoothLeScanner.stopScan(scanCallback)
                isScanning = false
                Log.d(TAG, "BLE сканирование остановлено")
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка остановки сканирования: ${e.message}")
            }
        }
    }

    private fun processScanResult(result: ScanResult) {
        try {
            val device = result.device
            val address = formatMacAddress(device.address ?: "N/A")
            val rssi = result.rssi
            val distance = calculateDistance(rssi)

            sendDataToMqtt(listOf(result))
            Log.d(TAG, "Обнаружено устройство: $address, RSSI: $rssi, Расстояние: $distance")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обработки результата сканирования: ${e.message}")
        }
    }

    private fun calculateDistance(rssi: Int): Double {
        return round(10.0.pow((-69.0 - rssi) / (10.0 * 2.0)) * 100.0) / 100.0
    }

    private fun formatMacAddress(macAddress: String): String {
        val cleanMac = macAddress.replace(":", "").replace("-", "").uppercase()
        return if (cleanMac.length == 12) {
            "${cleanMac.substring(0, 2)}-${cleanMac.substring(2, 4)}-${cleanMac.substring(4, 6)}-" +
                    "${cleanMac.substring(6, 8)}-${cleanMac.substring(8, 10)}-${cleanMac.substring(10, 12)}"
        } else {
            cleanMac
        }
    }

    private fun sendDataToMqtt(devices: List<ScanResult>) {
        if (!::mqttClient.isInitialized || !mqttClient.isConnected) {
            Log.w(TAG, "MQTT клиент не подключен, пытаемся переподключиться...")
            try {
                initializeMqtt()
            } catch (e: Exception) {
                Log.e(TAG, "Не удалось переподключиться к MQTT: ${e.message}")
                return
            }
        }

        try {
            val jsonArray = JSONArray()

            devices.forEach { result ->
                val device = result.device
                val address = formatMacAddress(device.address ?: "N/A")
                val rssi = result.rssi
                val distance = calculateDistance(rssi)

                val jsonObject = JSONObject().apply {
                    put("uuid", UUID.randomUUID().toString())
                    put("device_id", getScannerDeviceId())
                    put("tag_id", address)
                    put("event_type", "NOTIFY")
                    put("event_dt", System.currentTimeMillis().toString())
                    put("rssi", rssi.toString())
                    put("distance", distance.toString())
                }
                jsonArray.put(jsonObject)
            }

            if (jsonArray.length() > 0) {
                val message = MqttMessage(jsonArray.toString().toByteArray())
                message.qos = 1
                mqttClient.publish(mqttTopic, message)
                Log.d(TAG, "Отправлено в MQTT: ${jsonArray.length()} устройств")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка отправки данных в MQTT: ${e.message}")
        }
    }

    private fun getScannerDeviceId(): String {
        // Здесь должна быть логика получения ID сканера
        // Пока возвращаем фиктивное значение
        return "SERVICE-SCANNER-${System.currentTimeMillis()}"
    }

    private fun checkPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun startForegroundService() {
        createNotificationChannel()

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BLE Scanner")
            .setContentText("Сканирование BLE устройств")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BLE Scanner Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Сканирование BLE устройств в фоне"
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(restartRunnable)
        stopBleScan()
        releaseWakeLock()
        disconnectMqtt()
        Log.d(TAG, "Service destroyed")
    }

    private fun disconnectMqtt() {
        if (::mqttClient.isInitialized && mqttClient.isConnected) {
            try {
                mqttClient.disconnect()
                Log.d(TAG, "MQTT отключен")
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка отключения MQTT: ${e.message}")
            }
        }
    }
}