package com.techinfocom.blescannerkotlin

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.setMargins
import androidx.core.view.setPadding
import kotlin.math.pow
import kotlin.math.round
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface
import java.util.Collections
import java.util.UUID

class MainActivity : AppCompatActivity() {
    // Взаимодействие с bluetooth
    private lateinit var bluetoothAdapter: BluetoothAdapter // Доступ к Bluetooth соединениям
    private lateinit var bluetoothLeScanner: BluetoothLeScanner // Сканер Ble меток
    // Организация работы
    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper()) // Планирование задач
    private val scanPeriod: Long = 10000 // Период сканирования = 10 сек
    private val devicesMap = mutableMapOf<String, ScanResult>() // Список устройств
    // UI
    private lateinit var devicesContainer: LinearLayout // Отображение списка устройств
    private lateinit var scanButton: Button // Кнопка сканирования
    // Оптимизация: кэш view для устройств
    private val deviceViews = mutableMapOf<String, TextView>()
    // Оптимизация: тротлинг (снижения производительности устройства) для обновления UI
    private var lastUpdateTime = 0L
    private val UPDATE_THROTTLE_MS = 1000L // Обновляем UI не чаще, чем в 300мс
    private val updateRunnable = Runnable { updateDeviceList() }

    // MQTT
    private lateinit var mqttClient: MqttClient
    private val mqttBroker = "tcp://192.168.1.195:1883"
    private val mqttTopic = "ble-topic-data/device"
    private var scannerDeviceId: String = ""

    // Константы
    companion object {
        private const val REQUEST_PERMISSIONS = 1

        private val WHITELIST_ADDRESSES = setOf(
            "48:87:2D:9C:DF:04",
            "48:87:2D:9C:FA:8B",
            "48:87:2D:9D:58:69",
            "C6:43:0D:ED:FC:F2",
            "48:E7:29:A2:7C:A0",
            "48:E7:29:A2:7C:A1"  // ESP32 в режиме метки
        )
    }

    // Объект обратного вызова для получения BLE меток: при нахождении результат добавляет в devicesMap
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            // Проверяем, что result не null
            result ?: return

            val device = result.device
            val address = device.address

            if (address !in WHITELIST_ADDRESSES) {
                return  // Пропускаем устройство, если его нет в белом списке
            }

            // Обновляем или добавляем устройство
            devicesMap[address] = result

            // Тротилинг: обновляем UI не чаще чем раз в UPDATE_THROTTLE_MS
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastUpdateTime > UPDATE_THROTTLE_MS) {
                handler.removeCallbacks(updateRunnable)
                updateDeviceList()
                lastUpdateTime = currentTime
            } else {
                // Откладываем обновление, отменяя предыдущее
                handler.removeCallbacks(updateRunnable)
                handler.postDelayed(updateRunnable, UPDATE_THROTTLE_MS)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Toast.makeText(this@MainActivity, "Ошибка сканирования: $errorCode", Toast.LENGTH_SHORT).show()
        }
    }

    // Запуск приложения
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        devicesContainer = findViewById(R.id.devicesContainer)
        scanButton = findViewById(R.id.scanButton)

        // Инициализация Bluetooth
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        // Проверяем есть ли bluetooth на устройстве
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth не поддерживается", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // И включен ли bluetooth
        if (!bluetoothAdapter.isEnabled) {
            Toast.makeText(this, "Включите Bluetooth", Toast.LENGTH_LONG).show()
        }

        // Подключаем сканер BLE меток
        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner

        // Получаем device_id для сканера в формате MAC-адреса
        scannerDeviceId = getDeviceMacAddress()

        // Инициализация MQTT клиента
        initializeMqtt()

        scanButton.setOnClickListener {
            if (checkPermission()) {
                if (isScanning) {
                    stopScan()
                } else {
                    startScan()
                }
            } else {
                requestPermissions()
            }
        }
    }

    private fun initializeMqtt() {
        try {
            val clientId = "BLE_SCANNER_" + System.currentTimeMillis()
            mqttClient = MqttClient(mqttBroker, clientId, MemoryPersistence())

            val options = MqttConnectOptions()
            options.isCleanSession = true
            options.connectionTimeout = 10
            options.keepAliveInterval = 60

            // Используем правильный MqttCallback интерфейс
            mqttClient.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "MQTT соединение потеряно", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun messageArrived(topic: String, message: MqttMessage) {
                    // Не используется в данном контексте
                }

                override fun deliveryComplete(token: IMqttDeliveryToken) {
                    // Сообщение доставлено
                }
            })

            mqttClient.connect(options)
            runOnUiThread {
                Toast.makeText(this@MainActivity, "MQTT подключен успешно", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            runOnUiThread {
                Toast.makeText(this, "Ошибка подключения MQTT: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Получение MAC-адреса устройства
    private fun getDeviceMacAddress(): String {
        return try {
            // Сначала пробуем получить Bluetooth MAC-адрес
            if (checkPermission() && bluetoothAdapter.isEnabled) {
                try {
                    val bluetoothMac = bluetoothAdapter.address
                    if (bluetoothMac.isNotEmpty() && bluetoothMac != "02:00:00:00:00:00") {
                        return formatMacAddress(bluetoothMac)
                    }
                } catch (e: SecurityException) {
                    // Если нет разрешения, продолжаем пробовать другие методы
                }
            }

            // Пробуем получить WiFi MAC-адрес
            try {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val wifiInfo = wifiManager.connectionInfo
                val macAddress = wifiInfo.macAddress
                if (macAddress.isNotEmpty() && macAddress != "02:00:00:00:00:00") {
                    return formatMacAddress(macAddress)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Пробуем получить MAC-адрес из сетевых интерфейсов
            try {
                val all: List<NetworkInterface> = Collections.list(NetworkInterface.getNetworkInterfaces())
                for (nif in all) {
                    if (!nif.name.equals("wlan0", ignoreCase = true)) continue
                    val macBytes = nif.hardwareAddress ?: continue
                    val macBuilder = StringBuilder()
                    for (b in macBytes) {
                        macBuilder.append(String.format("%02X:", b))
                    }
                    if (macBuilder.isNotEmpty()) {
                        macBuilder.deleteCharAt(macBuilder.length - 1)
                        return formatMacAddress(macBuilder.toString())
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Если не удалось получить MAC-адрес, используем Android ID как запасной вариант
            getDeviceIdFromAndroidId()
        } catch (e: Exception) {
            e.printStackTrace()
            "UNKNOWN-DEVICE"
        }
    }

    // Форматирование MAC-адреса в нужный формат (XX-XX-XX-XX-XX-XX)
    private fun formatMacAddress(macAddress: String): String {
        // Убираем все разделители и приводим к верхнему регистру
        val cleanMac = macAddress.replace(":", "").replace("-", "").uppercase()

        // Форматируем в формат XX-XX-XX-XX-XX-XX
        return if (cleanMac.length == 12) {
            "${cleanMac.substring(0, 2)}-${cleanMac.substring(2, 4)}-${cleanMac.substring(4, 6)}-" +
                    "${cleanMac.substring(6, 8)}-${cleanMac.substring(8, 10)}-${cleanMac.substring(10, 12)}"
        } else {
            cleanMac // Возвращаем как есть, если длина не соответствует
        }
    }

    // Запасной метод: генерируем ID на основе Android ID
    private fun getDeviceIdFromAndroidId(): String {
        return try {
            val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            if (androidId != null && androidId.length >= 12) {
                // Используем часть Android ID для создания MAC-подобного идентификатора
                val macLike = androidId.take(12).uppercase()
                formatMacAddress(macLike)
            } else {
                // Генерируем случайный MAC-подобный идентификатор
                val randomMac = (1..12).map {
                    "0123456789ABCDEF".random()
                }.joinToString("")
                formatMacAddress(randomMac)
            }
        } catch (e: Exception) {
            // Фолбэк: случайный MAC
            val randomMac = (1..12).map {
                "0123456789ABCDEF".random()
            }.joinToString("")
            formatMacAddress(randomMac)
        }
    }

    private fun calculateDistance(rssi: Int): Double {
        return round(10.0.pow((-69.0 - rssi) / (10.0 * 2.0)) * 100.0) / 100.0
    }

    private fun sendDataToMqtt(devices: List<ScanResult>) {
        if (!::mqttClient.isInitialized || !mqttClient.isConnected) {
            return
        }

        try {
            val jsonArray = JSONArray()

            devices.forEach { result ->
                val device = result.device
                val address = if (checkPermission()) {
                    try {
                        // Форматируем MAC-адрес маячка в нужный формат
                        formatMacAddress(device.address ?: "N/A")
                    } catch (e: SecurityException) {
                        "N/A"
                    }
                } else {
                    "N/A"
                }

                val rssi = result.rssi
                val distance = calculateDistance(rssi)

                val jsonObject = JSONObject().apply {
                    put("uuid", UUID.randomUUID().toString())
                    put("device_id", scannerDeviceId) // Используем MAC-адрес сканера
                    put("tag_id", address) // MAC-адрес маячка уже отформатирован
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
                try {
                    mqttClient.publish(mqttTopic, message)
                    println("Отправлено в MQTT: ${jsonArray.length()} устройств")
                    println("Device ID: $scannerDeviceId")
                } catch (e: Exception) {
                    e.printStackTrace()
                    runOnUiThread {
                        Toast.makeText(this, "Ошибка публикации MQTT: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            runOnUiThread {
                Toast.makeText(this, "Ошибка формирования MQTT сообщения: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Проверка необходимых разрешений для работы программы
    private fun checkPermission(): Boolean {
        // С проверкой версии android (android 12 (SDK 31) - BLUETOOTH_SCAN и BLUETOOTH_CONNECT
        // для более старых - разрешения на локацию
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    // Запрос необходимых разрешений, если они не предоставлены
    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ),
                REQUEST_PERMISSIONS
            )
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_PERMISSIONS
            )
        }
    }

    // Функция обратного вызова: Если все разрешения получены, начинаем сканирование
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startScan()
            } else {
                Toast.makeText(this, "Разрешения необходимы для сканирования", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startScan() {
        if (!isScanning) {
            devicesMap.clear()
            devicesContainer.removeAllViews()
            deviceViews.clear()
            isScanning = true
            scanButton.text = "Остановить сканирование"

            // Еще одна проверка на нужные разрешения
            val hasPermissions = checkPermission()

            if (!hasPermissions) {
                return
            }

            try {
                bluetoothLeScanner.startScan(scanCallback)
                Toast.makeText(this, "Сканирование начато", Toast.LENGTH_SHORT).show()
            } catch (e: SecurityException) {
                Toast.makeText(this, "Ошибка безопасности: ${e.message}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Ошибка сканирования: ${e.message}", Toast.LENGTH_SHORT).show()
            }

            // Останавливаем сканирование через scanPeriod
            // handler.postDelayed({
            //     stopScan()
            // }, scanPeriod)
        }
    }

    private fun stopScan() {
        if (isScanning) {
            isScanning = false
            scanButton.text = "Начать сканирование"

            // Еще одна проверка на нужные разрешения
            val hasPermissions = checkPermission()

            if (!hasPermissions) {
                return
            }

            try {
                bluetoothLeScanner.stopScan(scanCallback)
                Toast.makeText(this, "Сканирование остановлено", Toast.LENGTH_SHORT).show()
            } catch (e: SecurityException) {
                Toast.makeText(this, "Ошибка безопасности: ${e.message}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Ошибка остановки сканирования: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Добавление UI-представления для Ble метки
    private fun updateDeviceList() {
        runOnUiThread {
            val currentAddress = devicesMap.keys.toSet()
            val viewAddresses = deviceViews.keys.toSet()

            // Удаляем view для устройств, которых больше нет
            val addressesToRemove = viewAddresses - currentAddress
            addressesToRemove.forEach { address ->
                deviceViews[address]?.let { view ->
                    devicesContainer.removeView(view)
                    deviceViews.remove(address)
                }
            }

            // Отправляем данные на MQTT брокер
            sendDataToMqtt(devicesMap.values.toList())

            devicesMap.values.forEach { result ->
                val device = result.device

                // Получаем имя из рекламных данных (не требует BLUETOOTH_CONNECT)
                val name = result.scanRecord?.deviceName
                    ?: if (checkPermission()) {
                        // Если есть разрешение, пытаемся получить имя устройства
                        try {
                            device.name ?: "Неизвестное устройство"
                        } catch (e: SecurityException) {
                            "Неизвестное устройство"
                        }
                    } else  {
                        "Неизвестное устройство"
                    }

                // Получаем адрес с проверкой разрешений и форматируем
                val address = if (checkPermission()) {
                    try {
                        formatMacAddress(device.address ?: "N/A")
                    } catch (e: SecurityException) {
                        "N/A"
                    }
                } else {
                    "N/A"
                }

                val rssi = result.rssi
                val distance = calculateDistance(rssi)
                val displayText = "$name\nMAC: $address\nRSSI: $rssi дБм\nРасстояние: $distance м"

                // Если view уже существует, просто обновляем текст
                val existingView = deviceViews[address]
                if (existingView != null) {
                    existingView.text = displayText
                } else {
                    val deviceView = TextView(this).apply {
                        text = displayText
                        textSize = 14f
                        setPadding(32, 16, 32, 16)
                        setBackgroundColor(ContextCompat.getColor(this@MainActivity, android.R.color.darker_gray))
                    }

                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(16, 8, 16, 8)
                    }

                    devicesContainer.addView(deviceView, params)
                    deviceViews[address] = deviceView
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScan()
        handler.removeCallbacks(updateRunnable)
        // Отключаем MQTT клиент
        if (::mqttClient.isInitialized && mqttClient.isConnected) {
            try {
                mqttClient.disconnect()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}