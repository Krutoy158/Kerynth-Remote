package com.kerynth.remote.console

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.aspectRatio
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class MainActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val screenExecutor = Executors.newSingleThreadScheduledExecutor()
    private val preferences by lazy { getSharedPreferences("kerynth_console", MODE_PRIVATE) }
    private var screenTask: ScheduledFuture<*>? = null

    private var host by mutableStateOf("")
    private var pairCode by mutableStateOf("")
    private var status by mutableStateOf("Не подключено")
    private var deviceInfo by mutableStateOf("Добавь Agent из той же Wi-Fi-сети.")
    private var locationInfo by mutableStateOf("")
    private var messageText by mutableStateOf("")
    private var paired by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var screenStreaming by mutableStateOf(false)
    private var screenFrame by mutableStateOf<Bitmap?>(null)
    private var typedText by mutableStateOf("")
    private var installedApps by mutableStateOf<List<AppEntry>>(emptyList())

    private data class AppEntry(val label: String, val packageName: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = preferences.getString("host", "") ?: ""
        paired = preferences.getString("auth_token", null) != null
        setContent {
            ConsoleScreen(
                host = host,
                pairCode = pairCode,
                status = status,
                deviceInfo = deviceInfo,
                locationInfo = locationInfo,
                messageText = messageText,
                typedText = typedText,
                paired = paired,
                busy = busy,
                screenStreaming = screenStreaming,
                screenFrame = screenFrame,
                installedApps = installedApps,
                onHostChange = {
                    host = it
                    preferences.edit().putString("host", it.trim()).apply()
                },
                onPairCodeChange = { pairCode = it.trim() },
                onMessageChange = { messageText = it },
                onConnect = ::connectOrPair,
                onForgetDevice = ::forgetDevice,
                onLock = { runCommand(JSONObject().put("command", "lock")) },
                onLocate = { runCommand(JSONObject().put("command", "locate")) },
                onSignal = { runCommand(JSONObject().put("command", "signal")) },
                onSendMessage = ::sendMessage,
                onSendImage = ::sendImage,
                onReboot = { runCommand(JSONObject().put("command", "reboot")) },
                onStartScreenStream = ::startScreenStream,
                onStopScreenStream = ::stopScreenStream,
                onScreenTap = ::sendTap,
                onScreenSwipe = ::sendSwipe,
                onNavigate = ::sendNavigation,
                onTypedTextChange = { typedText = it },
                onSendTypedText = ::sendTypedText,
                onLoadApps = ::loadApps,
                onOpenApp = ::openApp
            )
        }
    }

    override fun onDestroy() {
        screenStreaming = false
        screenTask?.cancel(true)
        screenExecutor.shutdownNow()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun connectOrPair() {
        if (busy) return
        val target = host.trim()
        if (target.isBlank()) {
            status = "Введи IP-адрес Agent."
            return
        }
        val existingToken = preferences.getString("auth_token", null)
        if (existingToken == null && pairCode.length < 8) {
            status = "Введи одноразовый код с экрана Agent."
            return
        }

        busy = true
        status = "Подключение…"
        executor.execute {
            try {
                var token = existingToken
                if (token == null) {
                    val derivedToken = Socket().use { socket ->
                        socket.connect(InetSocketAddress(target, SERVER_PORT), 5000)
                        socket.soTimeout = 10000
                        val input = BufferedInputStream(socket.getInputStream())
                        val output = socket.getOutputStream()
                        val greeting = JSONObject(readLine(input, 16_384))
                        val challenge = decode(greeting.getString("challenge"))
                        val deviceId = greeting.getString("deviceId")
                        val proof = encode(hmac(pairCode.toByteArray(StandardCharsets.UTF_8), challenge))
                        writeJson(output, JSONObject().put("pairProof", proof))
                        val response = JSONObject(readLine(input, 16_384))
                        if (!response.optBoolean("paired")) {
                            throw IllegalStateException(errorText(response.optString("error")))
                        }
                        val derived = deriveToken(pairCode, deviceId)
                        preferences.edit().putString("auth_token", derived).putString("host", target).apply()
                        derived
                    }
                    token = derivedToken
                    runOnUiThread {
                        paired = true
                        pairCode = ""
                        status = "Сопряжение выполнено. Проверяю устройство…"
                    }
                }

                val response = sendAuthenticated(target, token!!, JSONObject().put("command", "ping"))
                runOnUiThread {
                    paired = true
                    if (response.optBoolean("ok")) {
                        deviceInfo = buildString {
                            append(response.optString("device", "Устройство"))
                            append(" • Android ")
                            append(response.optString("android", "?"))
                            val battery = response.optInt("battery", -1)
                            if (battery >= 0) append(" • Батарея $battery%")
                        }
                        status = "Подключено"
                    } else {
                        status = errorText(response.optString("error"))
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    status = error.message ?: "Не удалось подключиться"
                    if (preferences.getString("auth_token", null) == null) paired = false
                }
            } finally {
                runOnUiThread { busy = false }
            }
        }
    }

    private fun runCommand(request: JSONObject) {
        if (busy) return
        val target = host.trim()
        val token = preferences.getString("auth_token", null)
        if (target.isBlank() || token.isNullOrBlank()) {
            status = "Сначала подключи и сопряги Agent."
            return
        }
        busy = true
        status = "Выполняется команда…"
        executor.execute {
            try {
                val response = sendAuthenticated(target, token, request)
                runOnUiThread {
                    if (request.optString("command") == "locate") {
                        if (response.optBoolean("ok")) {
                            val timestamp = response.optLong("timestamp")
                            val ageSeconds = ((System.currentTimeMillis() - timestamp).coerceAtLeast(0L) / 1000L)
                            locationInfo = "Широта: " + response.optDouble("latitude") +
                                "\nДолгота: " + response.optDouble("longitude") +
                                "\nТочность: " + response.optDouble("accuracy").toInt() + " м" +
                                "\nОбновлено: " + ageSeconds + " сек. назад"
                        } else {
                            locationInfo = errorText(response.optString("error"))
                        }
                    }
                    status = if (response.optBoolean("ok")) {
                        response.optString("result").ifBlank { "Команда выполнена" }
                    } else {
                        errorText(response.optString("error"))
                    }
                }
            } catch (error: Exception) {
                runOnUiThread { status = error.message ?: "Ошибка соединения" }
            } finally {
                runOnUiThread { busy = false }
            }
        }
    }

    private fun sendMessage() {
        val text = messageText.trim()
        if (text.isEmpty() || text.length > 1500) {
            status = "Сообщение должно содержать от 1 до 1500 символов."
            return
        }
        runCommand(JSONObject().put("command", "message").put("text", text))
        messageText = ""
    }

    private fun sendImage(uri: Uri) {
        if (busy) return
        val target = host.trim()
        val token = preferences.getString("auth_token", null)
        if (target.isBlank() || token.isNullOrBlank()) {
            status = "Сначала подключи и сопряги Agent."
            return
        }
        busy = true
        status = "Читаю изображение…"
        executor.execute {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { readLimited(it, MAX_IMAGE_BYTES) }
                    ?: throw IllegalStateException("Не удалось открыть изображение.")
                val encoded = encode(bytes)
                val response = sendAuthenticated(
                    target,
                    token,
                    JSONObject().put("command", "image").put("data", encoded)
                )
                runOnUiThread {
                    status = if (response.optBoolean("ok")) "Изображение передано" else errorText(response.optString("error"))
                }
            } catch (error: Exception) {
                runOnUiThread { status = error.message ?: "Не удалось передать изображение" }
            } finally {
                runOnUiThread { busy = false }
            }
        }
    }

    private fun forgetDevice() {
        preferences.edit().remove("auth_token").apply()
        paired = false
        pairCode = ""
        status = "Сопряжение удалено на Console. Для новой пары нужен новый код Agent."
        deviceInfo = "Нет сопряжённого устройства."
        locationInfo = ""
    }

    private fun sendAuthenticated(target: String, token: String, request: JSONObject): JSONObject {
        return Socket().use { socket ->
            socket.connect(InetSocketAddress(target, SERVER_PORT), 5000)
            socket.soTimeout = 30_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            val greeting = JSONObject(readLine(input, 16_384))
            val challenge = decode(greeting.getString("challenge"))
            val proof = encode(hmac(decode(token), challenge))
            writeJson(output, JSONObject().put("auth", proof))
            val accepted = JSONObject(readLine(input, 16_384))
            if (!accepted.optBoolean("ok")) {
                throw IllegalStateException(errorText(accepted.optString("error")))
            }
            writeJson(output, encryptEnvelope(token, request))
            decryptEnvelope(token, JSONObject(readLine(input, MAX_REQUEST_BYTES)))
        }
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            if (output.size() > limit) throw IllegalArgumentException("Изображение больше 3 МБ.")
        }
        return output.toByteArray()
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
                if (output.size() > maxBytes) throw IllegalArgumentException("Ответ сервера слишком большой.")
            }
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

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

    private fun deriveToken(code: String, deviceId: String): String {
        return encode(
            hmac(
                code.toByteArray(StandardCharsets.UTF_8),
                ("KerynthRemote|" + deviceId).toByteArray(StandardCharsets.UTF_8)
            )
        )
    }

    private fun encryptEnvelope(token: String, payload: JSONObject): JSONObject {
        val key = MessageDigest.getInstance("SHA-256").digest(decode(token))
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(payload.toString().toByteArray(StandardCharsets.UTF_8))
        return JSONObject().put("iv", encode(iv)).put("data", encode(encrypted))
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

    private fun errorText(code: String): String {
        return when (code) {
            "invalid_pair_code" -> "Неверный код сопряжения."
            "pair_code_expired" -> "Код истёк. Создай новый на Agent."
            "try_later" -> "Слишком много попыток. Подожди минуту."
            "already_paired" -> "Agent уже сопряжён. Отзови старый доступ на Agent."
            "unauthorized" -> "Доступ отозван или ключ не совпадает."
            "device_admin_required" -> "На Agent не активирован Device Admin."
            "device_owner_required" -> "Для перезагрузки нужен Device Owner."
            "location_permission_required" -> "На Agent не выдан доступ к геолокации."
            "location_unavailable" -> "Последняя известная геопозиция недоступна."
            "notification_permission_required" -> "На Agent не разрешены уведомления."
            "image_too_large" -> "Изображение превышает лимит 3 МБ."
            else -> code.ifBlank { "Неизвестная ошибка" }
        }
    }

    companion object {
        private const val SERVER_PORT = 43177
        private const val MAX_IMAGE_BYTES = 3 * 1024 * 1024
        private const val MAX_REQUEST_BYTES = 6 * 1024 * 1024
    }
}

@Composable
private fun ConsoleScreen(
    host: String,
    pairCode: String,
    status: String,
    deviceInfo: String,
    locationInfo: String,
    messageText: String,
    paired: Boolean,
    busy: Boolean,
    onHostChange: (String) -> Unit,
    onPairCodeChange: (String) -> Unit,
    onMessageChange: (String) -> Unit,
    onConnect: () -> Unit,
    onForgetDevice: () -> Unit,
    onLock: () -> Unit,
    onLocate: () -> Unit,
    onSignal: () -> Unit,
    onSendMessage: () -> Unit,
    onSendImage: (Uri) -> Unit,
    onReboot: () -> Unit
) {
    var confirmReboot by remember { mutableStateOf(false) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onSendImage(uri)
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0A1019)) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("KERYNTH", color = Color(0xFF9CCBFF), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                    Text("Remote Console", color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.SemiBold)
                    Text("Личная панель управления", color = Color(0xFF9EADBF))
                }

                SectionCard("Подключение", "Введи локальный IP Agent, показанный на втором телефоне.") {
                    OutlinedTextField(
                        value = host,
                        onValueChange = onHostChange,
                        label = { Text("IP-адрес Agent") },
                        placeholder = { Text("192.168.1.20") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (!paired) {
                        OutlinedTextField(
                            value = pairCode,
                            onValueChange = onPairCodeChange,
                            label = { Text("Одноразовый код с Agent") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Button(onClick = onConnect, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (busy) "Подождите…" else if (paired) "Подключиться" else "Сопрячь устройства")
                    }
                    if (paired) {
                        OutlinedButton(onClick = onForgetDevice, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text("Забыть сопряжённое устройство")
                        }
                    }
                    Text(status, color = Color(0xFF9CCBFF), fontSize = 13.sp)
                    Text(deviceInfo, color = Color(0xFFB5C2D2), fontSize = 12.sp)
                    Text(
                        "Текущая версия работает напрямую в одной Wi-Fi-сети. Удалённое подключение через мобильный интернет требует размещённого relay-сервера.",
                        color = Color(0xFF8193A9),
                        fontSize = 12.sp
                    )
                }

                SectionCard("Lost Mode", "Команды отправляются только на ранее сопряжённый Agent.") {
                    Button(onClick = onLock, enabled = paired && !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Заблокировать устройство")
                    }
                    OutlinedButton(onClick = onLocate, enabled = paired && !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Получить последнюю геопозицию")
                    }
                    if (locationInfo.isNotBlank()) Text(locationInfo, color = Color.White, fontSize = 13.sp)
                    OutlinedButton(
                        onClick = { confirmReboot = true },
                        enabled = paired && !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Перезагрузить устройство")
                    }
                }

                SectionCard("Сообщения и сигналы", "Сообщение появится в обычном уведомлении Agent. Сигнал короткий и слышимый.") {
                    OutlinedTextField(
                        value = messageText,
                        onValueChange = onMessageChange,
                        label = { Text("Текст сообщения") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4
                    )
                    Button(
                        onClick = onSendMessage,
                        enabled = paired && !busy && messageText.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Отправить уведомление")
                    }
                    OutlinedButton(onClick = onSignal, enabled = paired && !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Воспроизвести звуковой сигнал")
                    }
                }

                SectionCard("Изображения и экран", "Изображения можно отправлять в Agent. Захват экрана требует отдельного системного подтверждения на управляемом телефоне.") {
                    Button(
                        onClick = { imagePicker.launch("image/*") },
                        enabled = paired && !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Выбрать и отправить изображение")
                    }
                    OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                        Text("Трансляция экрана — следующий этап")
                    }
                }

                Text("Console • 0.5.0", color = Color(0xFF71839A), fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }

    if (confirmReboot) {
        AlertDialog(
            onDismissRequest = { confirmReboot = false },
            title = { Text("Перезагрузить Agent?") },
            text = { Text("Команда сработает только при наличии Device Owner на управляемом телефоне.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReboot = false
                    onReboot()
                }) { Text("Перезагрузить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReboot = false }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun SectionCard(title: String, description: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF162334))
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Text(description, color = Color(0xFFB5C2D2), fontSize = 13.sp)
            content()
        }
    }
}
