package com.fonecontrol.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fonecontrol.app.audio.BalanceController
import com.fonecontrol.app.bt.BatteryInfo
import com.fonecontrol.app.bt.HeadsetDriver
import com.fonecontrol.app.bt.SideBattery
import kotlin.math.roundToInt

@Composable
fun MainScreen(vm: MainViewModel) {
    val permissionsOk by vm.permissionsOk.collectAsStateWithLifecycle()
    val headsets by vm.headsets.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val driver by vm.driver.collectAsStateWithLifecycle()
    val volume by vm.volume.collectAsStateWithLifecycle()
    val micActive by vm.micActive.collectAsStateWithLifecycle()
    val micError by vm.micError.collectAsStateWithLifecycle()
    val forceAll by vm.forceAllApps.collectAsStateWithLifecycle()
    val micAvailable by vm.headsetMicAvailable.collectAsStateWithLifecycle()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { vm.refreshAll() }

    LaunchedEffect(Unit) {
        if (!permissionsOk) launcher.launch(vm.requiredPermissions())
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.safeDrawingPadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Fone Control",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            if (!permissionsOk) {
                item {
                    InfoCard(
                        title = "Permissão necessária",
                        text = "O app precisa da permissão de \"Dispositivos próximos\" para ver seus fones Bluetooth.",
                        action = "Permitir" to { launcher.launch(vm.requiredPermissions()) },
                    )
                }
            }

            item { DevicesCard(headsets, selected, onSelect = vm::select) }

            driver?.let { d -> item { BatteryCard(d, onRefresh = vm::refreshBattery) } }

            item {
                VolumeCard(
                    state = volume,
                    onLeft = vm::setLeft,
                    onRight = vm::setRight,
                    onLinked = vm::setLinked,
                    onCenter = vm::centerBalance,
                )
            }

            item {
                MicCard(
                    active = micActive,
                    available = micAvailable,
                    error = micError,
                    forceAll = forceAll,
                    onHeadset = vm::useHeadsetMic,
                    onPhone = vm::usePhoneMic,
                    onForceAll = vm::setForceAllApps,
                )
            }

            driver?.let { d -> item { DiagnosticsCard(d) } }
        }
    }
}

// ---------------------------------------------------------------- Fones

@Composable
private fun DevicesCard(headsets: List<HeadsetUi>, selected: String?, onSelect: (String) -> Unit) {
    val context = LocalContext.current
    SectionCard("Fone conectado") {
        if (headsets.isEmpty()) {
            Text("Nenhum fone Bluetooth conectado agora.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) {
                Text("Abrir configurações de Bluetooth")
            }
        } else {
            headsets.forEach { h ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(h.address) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = h.address == selected, onClick = { onSelect(h.address) })
                    Column {
                        Text(h.name, fontWeight = FontWeight.Medium)
                        Text(h.address, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Bateria

@Composable
private fun BatteryCard(driver: HeadsetDriver, onRefresh: () -> Unit) {
    val battery by driver.battery.collectAsStateWithLifecycle()
    SectionCard("Bateria", trailing = { TextButton(onClick = onRefresh) { Text("Atualizar") } }) {
        Text(driver.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        BatteryRow(battery)
        Spacer(Modifier.height(8.dp))
        Text(
            "Fonte: ${battery.source}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!battery.hasSides) {
            Text(
                "Este fone não informou bateria por lado. Fones Samsung e modelos com Google Fast Pair costumam informar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BatteryRow(b: BatteryInfo) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        if (b.hasSides) {
            Gauge("Esquerdo", b.left, LeftColor)
            Gauge("Direito", b.right, RightColor)
            Gauge("Estojo", b.case, MaterialTheme.colorScheme.tertiary)
        } else {
            Gauge("Geral", b.overall?.let { SideBattery(it) }, MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun Gauge(label: String, value: SideBattery?, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(84.dp)) {
            CircularProgressIndicator(
                progress = { (value?.level ?: 0) / 100f },
                modifier = Modifier.size(84.dp),
                color = color,
                strokeWidth = 7.dp,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Text(
                text = value?.let { "${it.level}%" } ?: "—",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(label + if (value?.charging == true) " ⚡" else "", style = MaterialTheme.typography.bodyMedium)
    }
}

// ---------------------------------------------------------------- Volume

@Composable
private fun VolumeCard(
    state: VolumeUi,
    onLeft: (Int) -> Unit,
    onRight: (Int) -> Unit,
    onLinked: (Boolean) -> Unit,
    onCenter: () -> Unit,
) {
    val context = LocalContext.current
    SectionCard("Volume por lado") {
        SideSlider("Esquerdo", state.left, LeftColor, onLeft)
        SideSlider("Direito", state.right, RightColor, onRight)

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Switch(checked = state.linked, onCheckedChange = onLinked)
            Spacer(Modifier.width(8.dp))
            Text("Mover os dois juntos", modifier = Modifier.weight(1f))
            TextButton(onClick = onCenter) { Text("Igualar") }
        }

        state.message?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        state.debug?.let {
            Text(
                it,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!state.supported) {
            Text(
                "Seu Android é anterior ao 10: só o volume geral pode ser alterado.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else if (!state.hasPermission) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text("Liberar volume por lado (uma vez só)", fontWeight = FontWeight.SemiBold)
            Text(
                "O Android só deixa um app mudar o balanço esquerda/direita com uma permissão especial, " +
                    "liberada pelo computador com o ADB (ou pelo app Shizuku/LADB direto no celular):",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            CodeBox(BalanceController.ADB_COMMAND) { copy(context, BalanceController.ADB_COMMAND) }
            Text(
                "Depois de rodar, volte ao app. Enquanto isso, o controle mexe só no volume geral.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text(
                "Se um dos lados não mudar, ative em Opções do desenvolvedor: \"Desativar descarga de hardware A2DP\".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SideSlider(label: String, value: Int, color: Color, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(76.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = 0f..100f,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color),
        )
        Text("$value", modifier = Modifier.width(36.dp), textAlign = TextAlign.End)
    }
}

// ---------------------------------------------------------------- Microfone

@Composable
private fun MicCard(
    active: Boolean,
    available: Boolean,
    error: String?,
    forceAll: Boolean,
    onHeadset: () -> Unit,
    onPhone: () -> Unit,
    onForceAll: (Boolean) -> Unit,
) {
    SectionCard("Microfone de entrada") {
        MicOption("Microfone do celular", selected = !active, onClick = onPhone)
        MicOption(
            "Microfone do fone" + if (!available) " (indisponível)" else "",
            selected = active,
            onClick = onHeadset,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = forceAll, onCheckedChange = onForceAll)
            Spacer(Modifier.width(8.dp))
            Text("Forçar para todos os apps")
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "Com o mic do fone ativo, o Bluetooth troca para o modo de ligação: o som fica mono e com " +
                "qualidade menor. Isso é limitação do Bluetooth. Sem \"Forçar\", vale para chamadas e apps " +
                "de voz (Discord, WhatsApp). Com \"Forçar\", vale também para gravadores e outros apps.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MicOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

// ---------------------------------------------------------------- Diagnóstico

@Composable
private fun DiagnosticsCard(driver: HeadsetDriver) {
    val lines by driver.log.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val context = LocalContext.current
    SectionCard(
        "Diagnóstico",
        trailing = { TextButton(onClick = { open = !open }) { Text(if (open) "Ocultar" else "Mostrar") } },
    ) {
        if (open) {
            if (lines.isEmpty()) {
                Text("Nada registrado ainda.", style = MaterialTheme.typography.bodySmall)
            } else {
                SelectionContainer {
                    Text(
                        lines.joinToString("\n"),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
                TextButton(onClick = { copy(context, lines.joinToString("\n")) }) { Text("Copiar log") }
            }
        } else {
            Text(
                "Mensagens técnicas do driver. Útil para adicionar suporte a novos modelos.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------- Peças comuns

@Composable
private fun SectionCard(
    title: String,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                trailing?.invoke()
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun InfoCard(title: String, text: String, action: Pair<String, () -> Unit>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Button(onClick = action.second) { Text(action.first) }
        }
    }
}

@Composable
private fun CodeBox(code: String, onCopy: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        SelectionContainer {
            Text(code, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
        TextButton(onClick = onCopy, modifier = Modifier.align(Alignment.End)) { Text("Copiar") }
    }
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Fone Control", text))
    Toast.makeText(context, "Copiado", Toast.LENGTH_SHORT).show()
}
