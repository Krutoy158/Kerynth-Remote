package com.kerynth.remote.agent

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    private var adminActive by mutableStateOf(false)
    private var deviceOwner by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        devicePolicyManager = getSystemService(DevicePolicyManager::class.java)
        adminComponent = ComponentName(this, KerynthDeviceAdminReceiver::class.java)
        refreshAdminState()

        setContent {
            AgentScreen(
                adminActive = adminActive,
                deviceOwner = deviceOwner,
                onRequestAdmin = ::requestAdmin,
                onLockDevice = ::lockDevice,
                onRebootDevice = ::rebootDevice
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (::devicePolicyManager.isInitialized) {
            refreshAdminState()
        }
    }

    private fun refreshAdminState() {
        adminActive = devicePolicyManager.isAdminActive(adminComponent)
        deviceOwner = devicePolicyManager.isDeviceOwnerApp(packageName)
    }

    private fun requestAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Kerynth Remote uses this permission only to lock this device when you activate Lost Mode."
            )
        }
        startActivity(intent)
    }

    private fun lockDevice() {
        if (devicePolicyManager.isAdminActive(adminComponent)) {
            devicePolicyManager.lockNow()
        }
    }

    private fun rebootDevice() {
        if (devicePolicyManager.isDeviceOwnerApp(packageName)) {
            devicePolicyManager.reboot(adminComponent)
        }
    }
}

@Composable
private fun AgentScreen(
    adminActive: Boolean,
    deviceOwner: Boolean,
    onRequestAdmin: () -> Unit,
    onLockDevice: () -> Unit,
    onRebootDevice: () -> Unit
) {
    var acceptingSessions by rememberSaveable { mutableStateOf(false) }

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF0A1019)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "KERYNTH",
                        color = Color(0xFF9CCBFF),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 3.sp
                    )
                    Text(
                        text = "Remote Agent",
                        color = Color.White,
                        fontSize = 29.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Приложение управляемого устройства",
                        color = Color(0xFF9EADBF)
                    )
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF162334)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = Icons.Rounded.Devices,
                                contentDescription = null,
                                tint = Color(0xFF9CCBFF),
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                text = "Подключение",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            text = if (acceptingSessions) {
                                "Приём запросов включён на этом устройстве"
                            } else {
                                "Устройство не принимает новые сеансы"
                            },
                            color = Color(0xFFB5C2D2)
                        )
                        Button(
                            onClick = { acceptingSessions = !acceptingSessions },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (acceptingSessions) {
                                    "Приостановить приём"
                                } else {
                                    "Разрешить запросы на подключение"
                                }
                            )
                        }
                        Text(
                            text = "Пока это локальный демонстрационный переключатель. Сопряжение будет подключено следующим этапом.",
                            color = Color(0xFF8193A9),
                            fontSize = 12.sp
                        )
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF162334)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = Icons.Rounded.NotificationsActive,
                                contentDescription = null,
                                tint = Color(0xFF9CCBFF)
                            )
                            Text(
                                text = "Входящие материалы",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            text = "Сообщения будут приходить как обычные уведомления Kerynth Remote. Изображения открываются в приложении после нажатия.",
                            color = Color(0xFFB5C2D2)
                        )
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF162334)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = Icons.Rounded.Lock,
                                contentDescription = null,
                                tint = Color(0xFF9CCBFF)
                            )
                            Text(
                                text = "Lost Mode",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            text = if (adminActive) {
                                "Разрешение Device Admin активно. Устройство можно заблокировать."
                            } else {
                                "Для блокировки владелец должен один раз включить разрешение Device Admin."
                            },
                            color = Color(0xFFB5C2D2)
                        )
                        if (!adminActive) {
                            OutlinedButton(
                                onClick = onRequestAdmin,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Разрешить блокировку устройства")
                            }
                        } else {
                            Button(
                                onClick = onLockDevice,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Заблокировать это устройство")
                            }
                        }
                        Text(
                            text = if (deviceOwner) {
                                "Режим Device Owner активен. Доступна системная перезагрузка."
                            } else {
                                "Перезагрузка доступна только после отдельной настройки Device Owner. Обычное разрешение приложения её не открывает."
                            },
                            color = Color(0xFF8193A9),
                            fontSize = 12.sp
                        )
                        if (deviceOwner) {
                            OutlinedButton(
                                onClick = onRebootDevice,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Перезагрузить устройство")
                            }
                        }
                        Text(
                            text = "Геолокация появится после отдельного включения владельцем фонового доступа. В Lost Mode будет показываться последняя доступная позиция и время её получения.",
                            color = Color(0xFF8193A9),
                            fontSize = 12.sp
                        )
                    }
                }

                Text(
                    text = "Agent • 0.3.0 • Удалённые команды ещё не подключены",
                    color = Color(0xFF71839A),
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }
}
