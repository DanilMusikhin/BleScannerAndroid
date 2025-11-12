package com.techinfocom.blescannerkotlin

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.setMargins
import androidx.core.view.setPadding

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

            // if (address !in WHITELIST_ADDRESSES) {
            //     return  // Пропускаем устройство, если его нет в белом списке
            // }

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

        // Инициализацию Bluetooth
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager;
        bluetoothAdapter = bluetoothManager.adapter;

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

        // Подлючаем сканер BLE меток
        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner;

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
    // Проверка необходимых разрешений для работы программы
    private fun checkPermission(): Boolean {
        // С проверкой версии android (android 12 (SDK 31) - BLUETOOTH_SCAN и BLUETOOTH_CONNECT
        // для более старых - разрешения на локацию
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
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
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
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
            isScanning = true
            scanButton.text = "Остановить сканирование"

            // Еще одна проверка на нужные разрешения
            val hasPermissions = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_SCAN
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            }

            if (!hasPermissions) {
                return
            }

            bluetoothLeScanner.startScan(scanCallback)

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
            val hasPermissions = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_SCAN
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            }

            if (!hasPermissions) {
                return
            }

            bluetoothLeScanner.stopScan(scanCallback)
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

            devicesMap.values.forEach { result ->
                var device = result.device
                
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

                // Получаем адрес с проверкой разрешений
                val address = if (checkPermission()) {
                    try {
                        device.address
                    } catch (e: SecurityException) {
                        "N/A"
                    }
                } else {
                    "N/A"
                }

                val rssi = result.rssi
                val displayText = "$name\nMAC: $address\nRSSI: $rssi дБм\n"

                // Если view уже существует, просто обновляем текст
                val existingView = deviceViews[address]
                if (existingView != null) {
                    existingView.text = displayText
                } else {
                    val deviceView = TextView(this).apply {
                        text = "$name\nMAC: $address\nRSSI: $rssi дБм\n"
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
    }
}