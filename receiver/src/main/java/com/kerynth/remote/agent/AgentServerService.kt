package com.kerynth.remote.agent

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.location.LocationManager
import android.media.AudioManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.media.ToneGenerator
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.util.Base64
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class AgentServerService : Service() {
    companion object {
        const val ACTION_START = "com.kerynth.remote.agent.START_SERVER"
        const val ACTION_STOP = "com.kerynth.remote.agent.STOP_SERVER"
        const val EXTRA_PAIR_CODE = "pair_code"
        const val SERVER_PORT = 43177
        private const val PREFS = "kerynth_agent"
        private const val CHANNEL_ID = "kerynth_remote_service"
        private const val NOTIFICATION_ID = 43177
        private const val MAX_REQUEST_BYTES = 6 * 1024 * 1024
        private const val MAX_IMAGE_BYTES = 3 * 1024 * 1024
    }

    private val random = SecureRandom()
    private val clients = Executors.newCachedThreadPool()
    private val pairLock = Any()
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private var nsdManager: NsdManager? = null
    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null
    private var toneGenerator: ToneGenerator? = null
    private var failedPairAttempts = 0
    @Volatile private var pairBlockedUntil = 0L
    private lateinit var acceptThread: Thread

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServer()
            stopSelf()
            return START_NOT_STICKY
        }

        

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildServiceNotification())
        if (!running) startServer()
        return START_STICKY
    }

    override fun onDestroy() {
        stopServer()
        clients.shutdownNow()
        toneGenerator?.release()
        toneGenerator = null
        super.onDestroy()
    }

    private fun startServer() {
        running = true
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("server_running", true).apply()
        acceptThread = Thread {
            try {
                val socket = ServerSocket()
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), SERVER_PORT), 8)
                serverSocket = socket
                registerNsdService()
                while (running) {
                    val client = socket.accept()
                    clients.execute {
                        client.use { connection ->
                            connection.soTimeout = 20_000
                            handleClient(connection)
                        }
                    }
                }
            } catch (_: Exception) {
                if (running) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putBoolean("server_running", false)
                        .putString("server_error", "Не удалось открыть локальный порт")
                        .apply()
                    running = false
                    stopSelf()
                }
            }
        }.apply {
            name = "KerynthAgentServer"
            isDaemon = true
            start()
        }
    }

    private fun registerNsdService() {
        val manager = getSystemService(NsdManager::class.java) ?: return
        nsdManager = manager
        val info = NsdServiceInfo().apply {
            serviceName = "Kerynth Remote Agent"
            serviceType = "_kerynthremote._tcp."
            port = SERVER_PORT
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        nsdRegistrationListener = listener
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    private fun stopServer() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        nsdRegistrationListener?.let { listener -> runCatching { nsdManager?.unregisterService(listener) } }
        nsdRegistrationListener = null
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("server_running", false).apply()
        toneGenerator?.stopTone()
    }

    private fun handleClient(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        try {
            val preferences = getSharedPreferences(PREFS, MODE_PRIVATE)
            val deviceId = preferences.getString("device_id", null) ?: UUID.randomUUID().toString().also {
                preferences.edit().putString("device_id", it).apply()
            }
            val challenge = randomBytes(32)
            writeJson(output, JSONObject()
                .put("challenge", encode(challenge))
                .put("deviceId", deviceId)
                .put("paired", preferences.getString("auth_token", null) != null))

            val handshake = JSONObject(readLine(input, 16_384))
            val storedToken = preferences.getString("auth_token", null)

            if (storedToken == null) {
                synchronized(pairLock) {
                    val currentToken = preferences.getString("auth_token", null)
                    if (currentToken != null) {
                        writeJson(output, JSONObject().put("ok", false).put("error", "already_paired"))
                        return
                    }
                    val approvedUntil = preferences.getLong("pairing_approved_until", 0L)
                    if (System.currentTimeMillis() > approvedUntil) {
                        writeJson(output, JSONObject().put("ok", false).put("error", "approval_required"))
                        return
                    }
                    val token = encode(randomBytes(32))
                    preferences.edit()
                        .putString("auth_token", token)
                        .remove("pairing_approved_until")
                        .apply()
                    writeJson(output, JSONObject().put("paired", true).put("token", token).put("deviceId", deviceId))
                    return
                }
            }

            val expected = encode(hmac(decode(storedToken), challenge))
            if (!constantTimeEquals(expected, handshake.optString("auth"))) {
                writeJson(output, JSONObject().put("ok", false).put("error", "unauthorized"))
                return
            }

            writeJson(output, JSONObject().put("ok", true))
            val envelope = JSONObject(readLine(input, MAX_REQUEST_BYTES))
            val request = decryptEnvelope(storedToken, envelope)
            val response = dispatch(request)
            writeJson(output, encryptEnvelope(storedToken, response))
        } catch (error: Exception) {
            runCatching {
                writeJson(output, JSONObject().put("ok", false).put("error", error.message ?: "request_failed"))
            }
        }
    }

    private fun dispatch(request: JSONObject): JSONObject {
        return when (request.optString("command")) {
            "ping" -> statusResponse()
            "lock" -> lockDevice()
            "reboot" -> rebootDevice()
            "locate" -> locateDevice()
            "message" -> receiveMessage(request)
            "signal" -> playSignal()
            "image" -> receiveImage(request)
            "tap" -> dispatchTap(request)
            "swipe" -> dispatchSwipe(request)
            "navigate" -> dispatchNavigation(request)
            "list_apps" -> listApps()
            "open_app" -> openApp(request)
            "text" -> setFocusedText(request)
            else -> JSONObject().put("ok", false).put("error", "unknown_command")
        }
    }

    private fun controlServiceRequired(): JSONObject {
        return JSONObject().put("ok", false).put("error", "accessibility_service_required")
    }

    private fun dispatchTap(request: JSONObject): JSONObject {
        if (!KerynthControlAccessibilityService.isEnabled(this)) return controlServiceRequired()
        val x = request.optDouble("x", -1.0)
        val y = request.optDouble("y", -1.0)
        if (x !in 0.0..1.0 || y !in 0.0..1.0) {
            return JSONObject().put("ok", false).put("error", "invalid_coordinates")
        }
        val accepted = KerynthControlAccessibilityService.tap(this, x, y)
        return JSONObject().put("ok", accepted).put("result", if (accepted) "tap_dispatched" else "gesture_rejected")
    }

    private fun dispatchSwipe(request: JSONObject): JSONObject {
        if (!KerynthControlAccessibilityService.isEnabled(this)) return controlServiceRequired()
        val values = listOf(
            request.optDouble("startX", -1.0),
            request.optDouble("startY", -1.0),
            request.optDouble("endX", -1.0),
            request.optDouble("endY", -1.0)
        )
        if (values.any { it !in 0.0..1.0 }) {
            return JSONObject().put("ok", false).put("error", "invalid_coordinates")
        }
        val accepted = KerynthControlAccessibilityService.swipe(
            this, values[0], values[1], values[2], values[3], request.optLong("duration", 350L)
        )
        return JSONObject().put("ok", accepted).put("result", if (accepted) "swipe_dispatched" else "gesture_rejected")
    }

    private fun dispatchNavigation(request: JSONObject): JSONObject {
        if (!KerynthControlAccessibilityService.isEnabled(this)) return controlServiceRequired()
        val action = request.optString("action")
        val accepted = KerynthControlAccessibilityService.globalAction(action)
        return JSONObject().put("ok", accepted).put("result", if (accepted) action else "navigation_rejected")
    }

    private fun listApps(): JSONObject {
        if (!KerynthControlAccessibilityService.isEnabled(this)) return controlServiceRequired()
        return JSONObject().put("ok", true).put("apps", KerynthControlAccessibilityService.installedApps(this))
    }

    private fun openApp(request: JSONObject): JSONObject {
        if (!KerynthControlAccessibilityService.isEnabled(this)) return controlServiceRequired()
        val packageName = request.optString("packageName")
        if (packageName.isBlank() || packageName.length > 255 || packageName == this.packageName) {
            return JSONObject().put("ok", false).put("error", "invalid_package")
        }
        val apps = KerynthControlAccessibilityService.installedApps(this)
        val isLaunchable = (0 until apps.length()).any { index ->
            apps.optJSONObject(index)?.optString("packageName") == packageName
        }
        if (!isLaunchable) return JSONObject().put("ok", false).put("error", "app_not_found")
        val accepted = KerynthControlAccessibilityService.openApp(this, packageName)
        return JSONObject().put("ok", accepted).put("result", if (accepted) "app_opened" else "app_open_failed")
    }

    private fun setFocusedText(request: JSONObject): JSONObject {
        if (!KerynthControlAccessibilityService.isEnabled(this)) return controlServiceRequired()
        val value = request.optString("value")
        if (value.length > 2000) return JSONObject().put("ok", false).put("error", "text_too_long")
        val accepted = KerynthControlAccessibilityService.setFocusedText(value)
        return JSONObject().put("ok", accepted).put("result", if (accepted) "text_entered" else "no_focused_text_field")
    }

    private fun screenFrame(): JSONObject {
        val frame = ScreenCaptureService.currentFrame()
            ?: return JSONObject().put("ok", false).put(
                "error",
                if (ScreenCaptureService.isActive()) "screen_frame_pending" else "screen_capture_not_active"
            )
        return JSONObject()
            .put("ok", true)
            .put("width", frame.second.first)
            .put("height", frame.second.second)
            .put("frame", encode(frame.first))
    }

    private fun stopScreenCapture(): JSONObject {
        if (!ScreenCaptureService.isActive()) {
            return JSONObject().put("ok", true).put("result", "screen_capture_already_stopped")
        }
        startService(Intent(this, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP))
        return JSONObject().put("ok", true).put("result", "screen_capture_stopped")
    }

    private fun statusResponse(): JSONObject {
        val battery = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val manager = getSystemService(DevicePolicyManager::class.java)
        val admin = manager.isAdminActive(android.content.ComponentName(this, KerynthDeviceAdminReceiver::class.java))
        val owner = manager.isDeviceOwnerApp(packageName)
        return JSONObject()
            .put("ok", true)
            .put("device", (Build.MANUFACTURER + " " + Build.MODEL).trim())
            .put("android", Build.VERSION.RELEASE ?: "unknown")
            .put("battery", batteryPercent)
            .put("admin", admin)
            .put("deviceOwner", owner)
            .put("locationPermission", hasLocationPermission())
            .put("touchControlEnabled", KerynthControlAccessibilityService.isEnabled(this))
            .put("screenCaptureActive", ScreenCaptureService.isActive())
    }

    private fun lockDevice(): JSONObject {
        val manager = getSystemService(DevicePolicyManager::class.java)
        val component = android.content.ComponentName(this, KerynthDeviceAdminReceiver::class.java)
        if (!manager.isAdminActive(component)) {
            return JSONObject().put("ok", false).put("error", "device_admin_required")
        }
        return runCatching {
            manager.lockNow()
            JSONObject().put("ok", true).put("result", "locked")
        }.getOrElse {
            JSONObject().put("ok", false).put("error", "lock_failed")
        }
    }

    private fun rebootDevice(): JSONObject {
        val manager = getSystemService(DevicePolicyManager::class.java)
        if (!manager.isDeviceOwnerApp(packageName)) {
            return JSONObject().put("ok", false).put("error", "device_owner_required")
        }
        return runCatching {
            manager.reboot(android.content.ComponentName(this, KerynthDeviceAdminReceiver::class.java))
            JSONObject().put("ok", true).put("result", "reboot_requested")
        }.getOrElse {
            JSONObject().put("ok", false).put("error", "reboot_failed")
        }
    }

    private fun locateDevice(): JSONObject {
        if (!hasLocationPermission()) {
            return JSONObject().put("ok", false).put("error", "location_permission_required")
        }
        return runCatching {
            val manager = getSystemService(LocationManager::class.java)
            val location = manager.getProviders(true)
                .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
                .maxByOrNull { it.time }
                ?: return JSONObject().put("ok", false).put("error", "location_unavailable")
            JSONObject()
                .put("ok", true)
                .put("latitude", location.latitude)
                .put("longitude", location.longitude)
                .put("accuracy", location.accuracy.toDouble())
                .put("timestamp", location.time)
                .put("provider", location.provider ?: "unknown")
        }.getOrElse {
            JSONObject().put("ok", false).put("error", "location_unavailable")
        }
    }

    private fun receiveMessage(request: JSONObject): JSONObject {
        val message = request.optString("text").trim()
        if (message.isEmpty() || message.length > 1500) {
            return JSONObject().put("ok", false).put("error", "invalid_message")
        }
        createNotificationChannel()
        val pendingIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Kerynth Remote")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return JSONObject().put("ok", false).put("error", "notification_permission_required")
        }
        manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
        return JSONObject().put("ok", true).put("result", "notification_sent")
    }

    private fun playSignal(): JSONObject {
        return runCatching {
            if (toneGenerator == null) {
                toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 75)
            }
            val started = toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 2500) == true
            if (started) JSONObject().put("ok", true).put("result", "signal_played")
            else JSONObject().put("ok", false).put("error", "signal_unavailable")
        }.getOrElse {
            JSONObject().put("ok", false).put("error", "signal_unavailable")
        }
    }

    private fun receiveImage(request: JSONObject): JSONObject {
        val encoded = request.optString("data")
        if (encoded.isBlank() || encoded.length > MAX_IMAGE_BYTES * 4 / 3 + 16) {
            return JSONObject().put("ok", false).put("error", "image_too_large")
        }
        return runCatching {
            val bytes = decode(encoded)
            if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) {
                return JSONObject().put("ok", false).put("error", "image_too_large")
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096) {
                return JSONObject().put("ok", false).put("error", "invalid_image")
            }
            val file = File(filesDir, "received_image")
            FileOutputStream(file).use { it.write(bytes) }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("last_image_path", file.absolutePath)
                .putLong("last_image_time", System.currentTimeMillis())
                .apply()
            showImageNotification()
            JSONObject().put("ok", true).put("result", "image_received")
        }.getOrElse {
            JSONObject().put("ok", false).put("error", "image_receive_failed")
        }
    }

    private fun showImageNotification() {
        createNotificationChannel()
        val pendingIntent = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("Изображение получено")
            .setContentText("Открой Kerynth Remote Agent для просмотра")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }

    private fun hasLocationPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun writeJson(output: OutputStream, json: JSONObject) {
        output.write((json.toString() + "\n").toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    private fun readLine(input: InputStream, maxBytes: Int): String {
        val output = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value == -1) throw EOFException()
            if (value == 10) break
            if (value != 13) {
                output.write(value)
                if (output.size() > maxBytes) throw IllegalArgumentException("request_too_large")
            }
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    private fun encode(value: ByteArray): String = Base64.encodeToString(
        value,
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    )

    private fun decode(value: String): ByteArray = Base64.decode(
        value,
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    )

    private fun hmac(secret: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(message)
    }

    private fun deriveToken(pairCode: String, deviceId: String): String {
        val value = hmac(
            pairCode.toByteArray(StandardCharsets.UTF_8),
            ("KerynthRemote|" + deviceId).toByteArray(StandardCharsets.UTF_8)
        )
        return encode(value)
    }

    private fun constantTimeEquals(first: String, second: String): Boolean {
        return MessageDigest.isEqual(
            first.toByteArray(StandardCharsets.UTF_8),
            second.toByteArray(StandardCharsets.UTF_8)
        )
    }

    private fun decryptEnvelope(token: String, envelope: JSONObject): JSONObject {
        val key = MessageDigest.getInstance("SHA-256").digest(decode(token))
        val iv = decode(envelope.getString("iv"))
        val encrypted = decode(envelope.getString("data"))
        require(iv.size == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return JSONObject(String(cipher.doFinal(encrypted), StandardCharsets.UTF_8))
    }

    private fun encryptEnvelope(token: String, payload: JSONObject): JSONObject {
        val key = MessageDigest.getInstance("SHA-256").digest(decode(token))
        val iv = randomBytes(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(payload.toString().toByteArray(StandardCharsets.UTF_8))
        return JSONObject().put("iv", encode(iv)).put("data", encode(encrypted))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Kerynth Remote",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Видимый статус локального подключения Kerynth Remote"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildServiceNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, AgentServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Kerynth Remote Agent активен")
            .setContentText("Локальное подключение доступно в этой Wi-Fi-сети")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Остановить", stopIntent)
            .build()
    }
}
