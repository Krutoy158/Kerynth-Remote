package com.kerynth.remote.console

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Message
import androidx.compose.material.icons.rounded.Monitor
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ConsoleScreen()
        }
    }
}

@Composable
private fun ConsoleScreen() {
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
                        text = "Remote Console",
                        color = Color.White,
                        fontSize = 29.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Панель оператора",
                        color = Color(0xFF9EADBF)
                    )
                }

                SectionCard(
                    title = "Мои устройства",
                    description = "Пока нет сопряжённых устройств. Добавление будет доступно после настройки защищённого QR-сопряжения."
                ) {
                    Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                        Text("Добавить устройство")
                    }
                }

                Text(
                    text = "Действия",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )

                ActionCard(
                    icon = { androidx.compose.material3.Icon(Icons.Rounded.Message, null, tint = Color(0xFF9CCBFF)) },
                    title = "Сообщение",
                    description = "Отправить текст в обычное уведомление на Agent"
                )
                ActionCard(
                    icon = { androidx.compose.material3.Icon(Icons.Rounded.Image, null, tint = Color(0xFF9CCBFF)) },
                    title = "Изображение",
                    description = "Передать изображение для просмотра в приложении"
                )
                ActionCard(
                    icon = { androidx.compose.material3.Icon(Icons.Rounded.NotificationsActive, null, tint = Color(0xFF9CCBFF)) },
                    title = "Звуковой сигнал",
                    description = "Опциональный, видимый и отключаемый сигнал на Agent"
                )
                ActionCard(
                    icon = { androidx.compose.material3.Icon(Icons.Rounded.Monitor, null, tint = Color(0xFF9CCBFF)) },
                    title = "Трансляция экрана",
                    description = "Каждый сеанс требует системного подтверждения на Agent"
                )

                SectionCard(
                    title = "Lost Mode",
                    description = "Статус возможностей будет получен от Agent после сопряжения. Команды не будут отправляться на неподтверждённые устройства."
                ) {
                    ActionCard(
                        icon = { androidx.compose.material3.Icon(Icons.Rounded.Lock, null, tint = Color(0xFF9CCBFF)) },
                        title = "Заблокировать",
                        description = "Доступно, если владелец активировал Device Admin на Agent"
                    )
                    ActionCard(
                        icon = { androidx.compose.material3.Icon(Icons.Rounded.LocationOn, null, tint = Color(0xFF9CCBFF)) },
                        title = "Найти устройство",
                        description = "Последняя переданная позиция и время её обновления"
                    )
                    ActionCard(
                        icon = { androidx.compose.material3.Icon(Icons.Rounded.Refresh, null, tint = Color(0xFF9CCBFF)) },
                        title = "Перезагрузить",
                        description = "Только при Device Owner на Agent"
                    )
                }

                Text(
                    text = "Console • 0.4.0 • Сопряжение и команды ещё не подключены",
                    color = Color(0xFF71839A),
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    description: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF162334))
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Text(description, color = Color(0xFFB5C2D2), fontSize = 13.sp)
            content()
        }
    }
}

@Composable
private fun ActionCard(
    icon: @Composable () -> Unit,
    title: String,
    description: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2B3D))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            icon()
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(description, color = Color(0xFF9EADBF), fontSize = 12.sp)
            }
            OutlinedButton(onClick = {}, enabled = false) {
                Text("Позже")
            }
        }
    }
}
