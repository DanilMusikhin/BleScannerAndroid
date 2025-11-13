package com.techinfocom.blescannerkotlin

import android.content.Context
import android.provider.Settings
import android.util.Log
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class MqttManager(private val context: Context) {

    private var mqttClient: MqttClient? = null
    // Параметры подключения к mqtt
    private val mqttBroker = "tcp://192.168.1.195:1883"
    private val mqttTopic = "ble-topic-data/device"
    // Для формирования сообщений
    private val scannerUUID: String = UUID.randomUUID().toString() // Генератор UUID
    private val deviceId: String = getDeviceId() // MAC адрес текущего устройства

    private var isConnected = false

    companion object {
        private const val TAG = "MqttManager"
    }


    // Получение ID устройства (MAC адрес сканера или Android ID)
    private fun getDeviceId(): String {
        return try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка получения device ID: ${e.message}")
            "UNKNOWN_DEVICE"
        }.uppercase()
    }

    // Инициализация и подключение к MQTT брокеру
    fun connect() {
        try {
            if (mqttClient?.isConnected == true) {
                Log.d(TAG, "MQTT уже подключен")
                return
            }

            val clientId = "BLE_Scanner_${System.currentTimeMillis()}"
            mqttClient = MqttClient(mqttBroker, clientId, MemoryPersistence())

            val options = MqttConnectOptions().apply {
                isCleanSession = true
                isAutomaticReconnect = true
                connectionTimeout = 10
                keepAliveInterval = 60
            }

            mqttClient?.connect(options)
            isConnected = true
            Log.d(TAG, "MQTT подключен успешно")
        } catch (e: Exception) {
            isConnected = false
            Log.e(TAG, "Ошибка подключения MQTT: ${e.message}", e)
        }
    }

    // Отправка данных о найденном BLE устройстве
    fun sendBleDeviceData(tagId: String, rssi: Int) {
        try {
            // Проверяем подключение и переподключаемся при необходимости
            if (mqttClient?.isConnected != true) {
                connect()
                if (mqttClient?.isConnected != true) {
                    Log.w(TAG, "MQTT не подключен, пропускаем отправку")
                }
            }

            // Форматируем tag_id (заменяем двоеточия на дефисы и приходим к верхнему регистру)
            val formattedTagId = tagId.replace(":", "-").uppercase()

            // Создаем JSON объект с данными
            val eventData = JSONObject().apply {
                put("uuid", scannerUUID)
                put("device_id", deviceId)
                put("tag_id", formattedTagId)
                put("event_type", "NOTIFY")
                put("event_dt", System.currentTimeMillis().toString())
                put("rssi", rssi.toString())
                put("distance", 17)
            }

            // Оборачиваем в массив
            val jsonArray = JSONArray().apply {
                put(eventData)
            }

            // Создаем MQTT сообщение
            val message = MqttMessage(jsonArray.toString().toByteArray())
            message.qos = 1
            message.isRetained = false

            // Отправляем сообщение
            mqttClient?.publish(mqttTopic, message)
            Log.d(TAG, "Данные отправлены: tagId=$formattedTagId, rssi=$rssi")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка отправки данных: ${e.message}", e)
        }
    }

    // Отключение от MQTT брокера
    fun disconnect() {
        try {
            mqttClient?.disconnect()
            mqttClient?.close()
            isConnected = false
            Log.d(TAG, "MQTT отключен")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка отключения MQTT: ${e.message}", e)
        }
    }

    // Проверка статуса подклчюения
    fun isConnected(): Boolean {
        return mqttClient?.isConnected == true
    }
}