package ceui.pixshaft.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ceui.pixshaft.desktop.AppGraph
import ceui.pixshaft.desktop.net.NetworkProbe
import ceui.pixshaft.desktop.net.NetworkTestSnapshot
import ceui.pixshaft.desktop.net.StepStatus
import ceui.pixshaft.desktop.net.TargetReport
import ceui.pixshaft.desktop.net.TargetStatus
import ceui.pixshaft.shared.net.NetCidr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NetworkTestScreen(graph: AppGraph) {
    val scope = rememberCoroutineScope()
    var probe by remember { mutableStateOf<NetworkProbe?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var snap by remember { mutableStateOf(NetworkProbe(graph) {}.snapshotEnv()) }
    var illustId by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }

    val s = graph.settings.current
    val envDirect = s.directConnect
    val envDoh = s.useSecureDns
    val envImageHost = AppGraph.imageHostLabel(s.imageHostMode)
    val envChromium = snap.envChromium.ifBlank {
        ceui.pixshaft.desktop.Chromium.findBrowser()?.fileName?.toString() ?: "未找到 Chrome / Edge"
    }
    val envProxy = NetworkProbe.describeProxy(envDirect)

    DisposableEffect(graph) {
        onDispose {
            probe?.cancel()
            job?.cancel()
        }
    }

    fun start() {
        if (snap.running) return
        copied = false
        val id = illustId.trim().toLongOrNull()
        val next = NetworkProbe(graph) { snap = it }
        probe?.cancel()
        probe = next
        job?.cancel()
        job = scope.launch {
            withContext(Dispatchers.IO) { next.run(id) }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text("网络测试", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "按桌面真实路径体检：API 走 Chromium QUIC，图片走无 SNI。结果和浏览能不能连上是同一套客户端。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EnvChip(if (envDoh) "安全 DNS 开" else "安全 DNS 关", envDoh)
            EnvChip(if (envDirect) "直连开" else "直连关", envDirect)
            EnvChip("图片 · $envImageHost", true)
            EnvChip("浏览器 · $envChromium", envChromium != "未找到 Chrome / Edge")
            EnvChip(envProxy, !envProxy.contains("未检测"))
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { start() }, enabled = !snap.running) {
                Text(if (snap.running) "检测中…" else "开始测试")
            }
            Spacer(Modifier.width(12.dp))
            OutlinedTextField(
                value = illustId,
                onValueChange = { illustId = it.filter { ch -> ch.isDigit() }.take(12) },
                modifier = Modifier.width(220.dp),
                singleLine = true,
                label = { Text("作品 ID（可选）") },
                placeholder = { Text(NetworkProbe.SAMPLE_ILLUST_ID.toString()) },
            )
        }
        if (snap.running) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        snap.overall?.let { ov ->
            Spacer(Modifier.height(16.dp))
            Surface(shape = RoundedCornerShape(18.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusPill(NetworkProbe.overallLabel(ov), overallColor(ov))
                        if (snap.pollutionBypassed) StatusPill("网络勉强可用", Color(0xFF2BB673))
                        if (snap.imageTargetFailed) StatusPill("图片无法加载", Color(0xFFE85D75))
                    }
                    snap.overallSub?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        snap.targets.forEach { TargetCard(it) }
        snap.imageReport?.let { TargetCard(it) }
        snap.illustReport?.let { TargetCard(it) }
        if (snap.rawLog.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Surface(shape = RoundedCornerShape(18.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("原始日志", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(
                            onClick = {
                                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(snap.rawLog), null)
                                copied = true
                            },
                        ) { Text(if (copied) "已复制" else "复制") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        snap.rawLog,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun TargetCard(report: TargetReport) {
    Spacer(Modifier.height(12.dp))
    Surface(shape = RoundedCornerShape(18.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(report.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(report.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusPill(report.statusPillOverride ?: NetworkProbe.statusLabel(report.status), targetColor(report.status))
                report.extraPill?.let {
                    Spacer(Modifier.width(8.dp))
                    StatusPill(it, Color(0xFFE8A317))
                }
            }
            report.steps.forEach { step ->
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier.padding(top = 4.dp).size(8.dp).clip(CircleShape).background(stepColor(step.status)),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(step.label, style = MaterialTheme.typography.bodyMedium)
                        step.detail?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EnvChip(text: String, on: Boolean) {
    val bg = if (on) Color(0xFF12B5A8).copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (on) Color(0xFF0E8E84) else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text,
        color = fg,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

private fun overallColor(ov: NetCidr.Overall): Color = when (ov) {
    NetCidr.Overall.CLEAN -> Color(0xFF2BB673)
    NetCidr.Overall.HIGH_LATENCY -> Color(0xFFE8A317)
    NetCidr.Overall.EXTREME_LATENCY -> Color(0xFFE85D75)
    NetCidr.Overall.DEGRADED -> Color(0xFFE8A317)
    NetCidr.Overall.POLLUTED -> Color(0xFFE8A317)
    NetCidr.Overall.NETWORK_DOWN -> Color(0xFFE85D75)
}

private fun targetColor(status: TargetStatus): Color = when (status) {
    TargetStatus.RUNNING -> Color(0xFF3D7EFF)
    TargetStatus.OK, TargetStatus.POLLUTED_BYPASSED -> Color(0xFF2BB673)
    TargetStatus.HIGH_LATENCY, TargetStatus.DEGRADED, TargetStatus.POLLUTED -> Color(0xFFE8A317)
    TargetStatus.EXTREME_LATENCY, TargetStatus.FAILED -> Color(0xFFE85D75)
}

private fun stepColor(status: StepStatus): Color = when (status) {
    StepStatus.OK -> Color(0xFF2BB673)
    StepStatus.WARN, StepStatus.HIGH_LATENCY -> Color(0xFFE8A317)
    StepStatus.FAIL, StepStatus.EXTREME_LATENCY -> Color(0xFFE85D75)
    StepStatus.RUNNING -> Color(0xFF3D7EFF)
    StepStatus.INFO -> Color(0xFF8892A4)
}
