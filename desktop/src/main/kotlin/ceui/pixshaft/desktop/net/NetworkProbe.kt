package ceui.pixshaft.desktop.net

import ceui.pixshaft.desktop.AppGraph
import ceui.pixshaft.desktop.Chromium
import ceui.pixshaft.desktop.ChromiumHttp
import ceui.pixshaft.shared.net.DesktopClient
import ceui.pixshaft.shared.net.DirectTls
import ceui.pixshaft.shared.net.ImageHosts
import ceui.pixshaft.shared.net.NetCidr
import ceui.pixshaft.shared.net.PixivClientIdentity
import ceui.pixshaft.shared.net.PixivDns
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class StepStatus { INFO, OK, WARN, FAIL, RUNNING, HIGH_LATENCY, EXTREME_LATENCY }

enum class TargetStatus { RUNNING, OK, HIGH_LATENCY, EXTREME_LATENCY, DEGRADED, POLLUTED, POLLUTED_BYPASSED, FAILED }

data class TestStep(
    val label: String,
    val detail: String? = null,
    val status: StepStatus = StepStatus.INFO,
)

data class TargetReport(
    val title: String,
    val subtitle: String,
    val status: TargetStatus = TargetStatus.RUNNING,
    val steps: List<TestStep> = emptyList(),
    val extraPill: String? = null,
    val statusPillOverride: String? = null,
)

data class NetworkTestSnapshot(
    val running: Boolean = false,
    val targets: List<TargetReport> = emptyList(),
    val overall: NetCidr.Overall? = null,
    val overallSub: String? = null,
    val rawLog: String = "",
    val imageReport: TargetReport? = null,
    val illustReport: TargetReport? = null,
    val pollutionBypassed: Boolean = false,
    val imageTargetFailed: Boolean = false,
    val envDirect: Boolean = true,
    val envDoh: Boolean = true,
    val envImageHost: String = "Pixiv 官方",
    val envChromium: String = "",
    val envProxy: String = "直连（无系统代理）",
)

private enum class TargetKind { APP_API, WEB_API, IMAGE, PIXSHAFT }

private data class TargetConfig(
    val host: String,
    val subtitle: String,
    val cidrs: List<String>?,
    val kind: TargetKind,
    val displayName: String? = null,
    val plainHttp: Boolean = false,
)

class NetworkProbe(
    private val graph: AppGraph,
    private val onUpdate: (NetworkTestSnapshot) -> Unit,
) {
    private val cancelled = AtomicBoolean(false)
    private val work = mutableListOf<TargetReport>()
    private val log = StringBuilder()
    private var imageReport: TargetReport? = null
    private var illustReport: TargetReport? = null
    private var imageFailed = false
    private var imageLatency: TargetStatus? = null
    private var imageDownloadFailed = false

    fun cancel() {
        cancelled.set(true)
    }

    fun snapshotEnv(): NetworkTestSnapshot {
        val s = graph.settings.current
        return NetworkTestSnapshot(
            envDirect = s.directConnect,
            envDoh = s.useSecureDns,
            envImageHost = AppGraph.imageHostLabel(s.imageHostMode),
            envChromium = Chromium.findBrowser()?.fileName?.toString() ?: "未找到 Chrome / Edge",
            envProxy = describeProxy(s.directConnect),
        )
    }

    fun run(illustId: Long? = null) {
        cancelled.set(false)
        work.clear()
        log.setLength(0)
        imageReport = null
        illustReport = null
        imageFailed = false
        imageLatency = null
        imageDownloadFailed = false
        val s = graph.settings.current
        val direct = s.directConnect
        val doh = s.useSecureDns
        emit(running = true)
        try {
            line("环境: 安全 DNS(DoH) ${onOff(doh)} · 直连 ${onOff(direct)} · 图片源 ${AppGraph.imageHostLabel(s.imageHostMode)}")
            line("Chromium: ${Chromium.findBrowser() ?: "未找到"}")
            line("代理: ${describeProxy(direct)}")
            line("")

            val imageCfg = imageConfig()
            val configs = listOf(
                TargetConfig(
                    host = NetCidr.APP_API_HOST,
                    subtitle = "App API · 推荐/详情/收藏",
                    cidrs = NetCidr.PIXIV_CIDRS,
                    kind = TargetKind.APP_API,
                ),
                TargetConfig(
                    host = "www.pixiv.net",
                    subtitle = "网页 ajax · CSRF / 作品页",
                    cidrs = NetCidr.PIXIV_CIDRS,
                    kind = TargetKind.WEB_API,
                ),
                imageCfg,
                TargetConfig(
                    host = "pixshaft.com",
                    subtitle = "更新检查 · 不受 Pixiv 直连影响",
                    cidrs = null,
                    kind = TargetKind.PIXSHAFT,
                ),
            )
            val polluted = mutableListOf<String>()
            val bypassOk = mutableListOf<Boolean>()
            for (cfg in configs) {
                if (cancelled.get()) return
                val idx = addTarget(TargetReport(cfg.displayName ?: cfg.host, cfg.subtitle))
                val (isPolluted, hsOk) = testTarget(idx, cfg, doh, direct)
                if (isPolluted) {
                    polluted.add(cfg.host)
                    bypassOk.add(hsOk)
                }
            }
            if (cancelled.get()) return
            runImageDownload(imageCfg)
            if (illustId != null && illustId > 0) {
                runIllustApi(illustId)
            }

            val appTitle = NetCidr.APP_API_HOST
            val imageTitle = imageCfg.displayName ?: imageCfg.host
            val appApiFailed = work.any {
                it.title == appTitle && (it.status == TargetStatus.FAILED || it.status == TargetStatus.POLLUTED)
            }
            val imageTargetFailed = work.any {
                it.title == imageTitle && (it.status == TargetStatus.FAILED || it.status == TargetStatus.POLLUTED)
            }
            imageFailed = imageTargetFailed
            val anyFailed = work.any { it.status == TargetStatus.FAILED }
            val anyHigh = work.any { it.status == TargetStatus.HIGH_LATENCY } ||
                imageLatency == TargetStatus.HIGH_LATENCY
            val anyExtreme = work.any { it.status == TargetStatus.EXTREME_LATENCY } ||
                imageLatency == TargetStatus.EXTREME_LATENCY
            val anyDegraded = work.any { it.status == TargetStatus.DEGRADED } || imageDownloadFailed
            val bypassActive = polluted.isNotEmpty() && bypassOk.all { it } && !appApiFailed && !imageTargetFailed
            val ov = NetCidr.overall(
                appApiFailed = appApiFailed,
                polluted = polluted.isNotEmpty(),
                anyFailed = anyFailed || anyDegraded,
                anyDegraded = anyDegraded,
                extremeLatency = anyExtreme,
                highLatency = anyHigh,
            )
            val sub = overallSub(ov, appApiFailed, imageTargetFailed, imageCfg, bypassActive)
            emit(
                running = false,
                overall = ov,
                overallSub = sub,
                pollutionBypassed = bypassActive,
                imageTargetFailed = imageTargetFailed,
            )
        } catch (error: Throwable) {
            line("测试中断: ${error.javaClass.simpleName}: ${error.message}")
            emit(running = false)
        }
    }

    private fun imageConfig(): TargetConfig {
        val rewritten = ImageHosts.rewrite("https://i.pximg.net/img-master/img/x.jpg")
        val host = rewritten.substringAfter("://").substringBefore('/')
        val custom = ImageHosts.mode == ImageHosts.MODE_CUSTOM
        val plain = custom && ImageHosts.customHost.startsWith("http://", ignoreCase = true)
        return if (host != "i.pximg.net") {
            TargetConfig(
                host = host,
                subtitle = "图片反代 · ${AppGraph.imageHostLabel(ImageHosts.mode)}",
                cidrs = null,
                kind = TargetKind.IMAGE,
                displayName = if (custom) mask(host) else host,
                plainHttp = plain,
            )
        } else {
            TargetConfig(
                host = "i.pximg.net",
                subtitle = "图片 CDN · 官方 i.pximg.net",
                cidrs = NetCidr.PXIMG_CIDRS,
                kind = TargetKind.IMAGE,
            )
        }
    }

    private fun testTarget(idx: Int, cfg: TargetConfig, doh: Boolean, direct: Boolean): Pair<Boolean, Boolean> {
        line("── ${cfg.displayName ?: cfg.host} ──")
        if (cfg.plainHttp) {
            addStep(idx, TestStep("跳过 HTTPS 握手", "明文 http 反代，以图片下载为准", StepStatus.INFO))
            setStatus(idx, TargetStatus.OK)
            return false to true
        }
        val dns = lookupSystem(cfg.host)
        val fakeIps = dns.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }.filter { NetCidr.isFakeIp(it) }
        if (fakeIps.isNotEmpty()) {
            addStep(
                idx,
                TestStep(
                    "系统 DNS · ${dns.size} 条",
                    "检测到 fake-ip：${fakeIps.joinToString()}\nClash / Surge 的 fake-ip 下 DNS/ICMP 无参考价值，只测握手。",
                    StepStatus.WARN,
                ),
            )
            line("fake-ip: ${fakeIps.joinToString()}")
            val hs = handshake(idx, cfg, null, direct = false, fakeIp = true)
            setStatus(
                idx,
                when {
                    !hs.ok -> TargetStatus.FAILED
                    hs.maxMs > NetCidr.EXTREME_LATENCY_MS -> TargetStatus.EXTREME_LATENCY
                    hs.avgMs > NetCidr.HIGH_LATENCY_MS -> TargetStatus.HIGH_LATENCY
                    else -> TargetStatus.OK
                },
            )
            return false to hs.ok
        }

        val ipv4 = dns.filterIsInstance<Inet4Address>()
        val ipv6 = dns.filterIsInstance<Inet6Address>()
        val publicV6 = ipv6.filter { NetCidr.isPublicIpv6(it, cfg.cidrs) }
        val pixivV6 = !direct && NetCidr.isPixivDomain(cfg.host) && publicV6.isNotEmpty()
        var polluted = pixivV6
        val cidrs = cfg.cidrs
        val cleanV4 = if (cidrs != null) {
            ipv4.filter { a -> NetCidr.ipv4InOfficialCidrs(a.hostAddress ?: "", cidrs) }
        } else {
            ipv4
        }
        if (cidrs != null) {
            val sb = StringBuilder()
            for (a in ipv4) {
                val ip = a.hostAddress ?: continue
                val hit = cidrs.firstOrNull { NetCidr.isIpInCidr(ip, it) }
                sb.appendLine(if (hit != null) "✓ $ip ∈ $hit" else "✗ $ip 不在官方段")
            }
            for (a in ipv6) {
                val ip = a.hostAddress ?: continue
                sb.appendLine(if (pixivV6 && a in publicV6) "✗ IPv6 $ip 视为污染" else "· IPv6 $ip（跳过）")
            }
            polluted = polluted || (ipv4.isNotEmpty() && cleanV4.isEmpty())
            addStep(
                idx,
                TestStep(
                    "系统 DNS · ${dns.size} 条",
                    sb.toString().trimEnd().ifBlank { "无记录" },
                    when {
                        polluted -> StepStatus.FAIL
                        ipv4.isEmpty() -> StepStatus.WARN
                        cleanV4.size < ipv4.size -> StepStatus.WARN
                        else -> StepStatus.OK
                    },
                ),
            )
        } else {
            addStep(
                idx,
                TestStep(
                    "系统 DNS · ${dns.size} 条",
                    dns.joinToString("\n") { it.hostAddress ?: "?" }.ifBlank { "无记录" },
                    StepStatus.OK,
                ),
            )
        }
        line("DNS: " + dns.joinToString { it.hostAddress ?: "?" })

        var appIp: Inet4Address? = null
        if ((doh || direct) && cidrs != null) {
            val appAddrs = runCatching { PixivDns.lookup(cfg.host) }.getOrDefault(emptyList())
            val appV4 = appAddrs.filterIsInstance<Inet4Address>()
            val sb = StringBuilder()
            var clean = 0
            for (a in appV4) {
                val ip = a.hostAddress ?: continue
                val hit = cidrs.firstOrNull { NetCidr.isIpInCidr(ip, it) }
                if (hit != null) {
                    clean++
                    sb.appendLine("✓ $ip ∈ $hit")
                } else {
                    sb.appendLine("✗ $ip 不在官方段")
                }
            }
            addStep(
                idx,
                TestStep(
                    "应用内解析 · PixivDns(DoH/直连) · ${appAddrs.size} 条",
                    sb.toString().trimEnd().ifBlank { "无 IPv4" },
                    when {
                        appV4.isEmpty() -> StepStatus.WARN
                        clean == 0 -> StepStatus.FAIL
                        clean < appV4.size -> StepStatus.WARN
                        else -> StepStatus.OK
                    },
                ),
            )
            line("PixivDns: " + appAddrs.joinToString { it.hostAddress ?: "?" })
            appIp = appV4.firstOrNull { a -> NetCidr.ipv4InOfficialCidrs(a.hostAddress ?: "", cidrs) }
        }

        val targetIp = cleanV4.firstOrNull() ?: appIp
        if (targetIp == null) {
            if (polluted && direct && (cfg.kind == TargetKind.APP_API || cfg.kind == TargetKind.WEB_API)) {
                addStep(
                    idx,
                    TestStep(
                        "本机 DNS 不可信，改走 Chromium QUIC",
                        "${cfg.host} 由 host-resolver-rules 钉 Cloudflare IP",
                        StepStatus.WARN,
                    ),
                )
                val hs = handshake(idx, cfg, null, direct, bypassDns = true)
                setStatus(
                    idx,
                    when {
                        !hs.ok -> TargetStatus.POLLUTED
                        hs.maxMs > NetCidr.EXTREME_LATENCY_MS -> TargetStatus.EXTREME_LATENCY
                        hs.avgMs > NetCidr.HIGH_LATENCY_MS -> TargetStatus.HIGH_LATENCY
                        else -> TargetStatus.POLLUTED_BYPASSED
                    },
                )
                return polluted to hs.ok
            }
            addStep(
                idx,
                TestStep("跳过连通性", if (polluted) "解析被污染且没有干净 IPv4" else "没有 IPv4", StepStatus.WARN),
            )
            setStatus(idx, if (polluted) TargetStatus.POLLUTED else TargetStatus.FAILED)
            return polluted to false
        }
        if (polluted && cleanV4.isEmpty() && targetIp === appIp) {
            addStep(
                idx,
                TestStep("绕过本机 DNS", "改用应用内 ${targetIp.hostAddress}", StepStatus.WARN),
            )
        }
        tcpPing(idx, targetIp.hostAddress ?: "")
        val hs = handshake(idx, cfg, targetIp, direct)
        var webDegraded = false
        if (cfg.kind == TargetKind.WEB_API && hs.ok) {
            webDegraded = !probeWeb(idx, direct)
        }
        setStatus(
            idx,
            when {
                polluted && !hs.ok -> TargetStatus.POLLUTED
                polluted -> TargetStatus.POLLUTED_BYPASSED
                !hs.ok -> TargetStatus.FAILED
                hs.maxMs > NetCidr.EXTREME_LATENCY_MS -> TargetStatus.EXTREME_LATENCY
                hs.avgMs > NetCidr.HIGH_LATENCY_MS -> TargetStatus.HIGH_LATENCY
                webDegraded -> TargetStatus.DEGRADED
                cidrs != null && cleanV4.size < ipv4.size -> TargetStatus.DEGRADED
                else -> TargetStatus.OK
            },
        )
        line("")
        return polluted to hs.ok
    }

    private data class Handshake(val ok: Boolean, val avgMs: Int, val maxMs: Int)

    private fun handshake(
        idx: Int,
        cfg: TargetConfig,
        ip: Inet4Address?,
        direct: Boolean,
        fakeIp: Boolean = false,
        bypassDns: Boolean = false,
    ): Handshake {
        val path = when {
            fakeIp -> "系统 DNS + 标准 TLS（fake-ip / 代理）"
            bypassDns && direct -> "直连 Chromium/QUIC（绕过本机 DNS）"
            cfg.kind == TargetKind.IMAGE && direct && ImageHosts.requiresOfficialPximg() -> "无 SNI TLS · HTTP/1.1"
            cfg.kind == TargetKind.PIXSHAFT -> "标准 TLS"
            direct && (cfg.kind == TargetKind.APP_API || cfg.kind == TargetKind.WEB_API) -> "直连 Chromium/QUIC"
            else -> "标准 TLS"
        }
        val stepIdx = work[idx].steps.size
        addStep(idx, TestStep("HTTPS 握手 · $path", "采样中…", StepStatus.RUNNING))
        val client = buildHandshakeClient(cfg, ip, if (fakeIp) false else direct, pin = !fakeIp && !bypassDns)
        val probeUrl = if (cfg.kind == TargetKind.APP_API || cfg.kind == TargetKind.WEB_API) {
            Chromium.originWarmupUrl("https://${cfg.host}")
        } else {
            "https://${cfg.host}/"
        }
        val request = if (cfg.kind == TargetKind.APP_API || cfg.kind == TargetKind.WEB_API) {
            Request.Builder().url(probeUrl).get().build()
        } else {
            Request.Builder().url(probeUrl).head().build()
        }
        val samples = mutableListOf<Long>()
        var fail = 0
        var proto: String? = null
        var firstErr: String? = null
        val deadline = System.currentTimeMillis() + 12_000
        var n = 0
        try {
            while (System.currentTimeMillis() < deadline && n < 8 && !cancelled.get()) {
                n++
                val t0 = System.currentTimeMillis()
                try {
                    client.newCall(request).execute().use { resp ->
                        samples.add(System.currentTimeMillis() - t0)
                        proto = resp.protocol.toString()
                    }
                } catch (e: Exception) {
                    fail++
                    if (firstErr == null) firstErr = "${e.javaClass.simpleName}: ${e.message}"
                }
                updateStep(idx, stepIdx, handshakeDetail(samples, fail, proto, firstErr), StepStatus.RUNNING)
            }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
        val ok = samples.isNotEmpty()
        val avg = if (ok) samples.average().toInt() else 0
        val max = if (ok) samples.max().toInt() else 0
        updateStep(
            idx,
            stepIdx,
            handshakeDetail(samples, fail, proto, firstErr),
            when {
                !ok -> StepStatus.FAIL
                fail > 0 -> StepStatus.WARN
                else -> StepStatus.OK
            },
        )
        line("HTTPS($path): 成功 ${samples.size} / 失败 $fail" + if (ok) " · avg ${avg}ms max ${max}ms · $proto" else " · $firstErr")
        return Handshake(ok, avg, max)
    }

    private fun handshakeDetail(samples: List<Long>, fail: Int, proto: String?, firstErr: String?): String {
        if (samples.isEmpty()) return firstErr ?: "握手失败"
        val min = samples.min()
        val max = samples.max()
        val avg = samples.average().toInt()
        return "成功 ${samples.size}" +
            (if (fail > 0) " / 失败 $fail" else "") +
            " · min ${min} avg $avg max $max ms · 抖动 ${max - min}ms" +
            (proto?.let { "\n协议 $it" } ?: "")
    }

    private fun buildHandshakeClient(
        cfg: TargetConfig,
        ip: Inet4Address?,
        direct: Boolean,
        pin: Boolean,
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .proxy(AppGraph.proxyFor(graph.settings.current))
            .dns(AppGraph.dnsFor(graph.settings.current))
        if (pin && ip != null) {
            builder.dns(object : Dns {
                override fun lookup(hostname: String) = listOf<InetAddress>(ip)
            })
        }
        when (cfg.kind) {
            TargetKind.APP_API, TargetKind.WEB_API -> {
                if (cfg.kind == TargetKind.WEB_API) builder.protocols(listOf(Protocol.HTTP_1_1))
                if (direct) builder.addInterceptor(ChromiumHttp.interceptor)
            }
            TargetKind.IMAGE -> {
                builder.protocols(listOf(Protocol.HTTP_1_1))
                if (direct && ImageHosts.requiresOfficialPximg()) {
                    builder.sslSocketFactory(DirectTls.noSniFactory, DirectTls.trustAll)
                    builder.hostnameVerifier(DirectTls.hostnameVerifier)
                }
            }
            TargetKind.PIXSHAFT -> {
                builder.protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                builder.dns(Dns.SYSTEM)
                builder.proxy(java.net.Proxy.NO_PROXY)
            }
        }
        return builder.build()
    }

    private fun tcpPing(idx: Int, ip: String) {
        try {
            val t0 = System.currentTimeMillis()
            Socket().use { it.connect(InetSocketAddress(ip, 443), 3000) }
            val ms = System.currentTimeMillis() - t0
            addStep(
                idx,
                TestStep(
                    "TCP 443",
                    if (ms <= 10) "${ms}ms（过快，可能被代理接管，参考性低）" else "${ms}ms",
                    if (ms <= 10) StepStatus.WARN else StepStatus.OK,
                ),
            )
            line("TCP 443: ${ms}ms")
        } catch (e: Exception) {
            addStep(idx, TestStep("TCP 443", "不可达: ${e.message}", StepStatus.FAIL))
            line("TCP 443 不可达: ${e.message}")
        }
    }

    private fun probeWeb(idx: Int, direct: Boolean): Boolean {
        val stepIdx = work[idx].steps.size
        addStep(idx, TestStep("网页端点 · /ajax/illust/$SAMPLE_ILLUST_ID", "请求中…", StepStatus.RUNNING))
        val client = buildHandshakeClient(
            TargetConfig("www.pixiv.net", "", NetCidr.PIXIV_CIDRS, TargetKind.WEB_API),
            null,
            direct,
            pin = false,
        )
        val t0 = System.currentTimeMillis()
        return try {
            val req = Request.Builder()
                .url("https://www.pixiv.net/ajax/illust/$SAMPLE_ILLUST_ID?lang=zh")
                .header("User-Agent", PixivClientIdentity.ANDROID_CHROME_UA)
                .header("Referer", "https://www.pixiv.net/")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val ms = System.currentTimeMillis() - t0
                val body = resp.body?.string().orEmpty()
                val json = runCatching { Gson().fromJson(body, JsonObject::class.java) }.getOrNull()
                val error = json?.get("error")?.asBoolean ?: true
                val ok = resp.isSuccessful && !error
                updateStep(
                    idx,
                    stepIdx,
                    if (ok) "HTTP ${resp.code} · ${ms}ms · 作品 $SAMPLE_ILLUST_ID" else "HTTP ${resp.code} · ${ms}ms",
                    if (ok) StepStatus.OK else StepStatus.WARN,
                )
                line("web ajax: HTTP ${resp.code} ${ms}ms ok=$ok")
                ok
            }
        } catch (e: Exception) {
            updateStep(idx, stepIdx, "${e.javaClass.simpleName}: ${e.message}", StepStatus.WARN)
            line("web ajax 失败: ${e.message}")
            false
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun runImageDownload(imageCfg: TargetConfig) {
        val steps = mutableListOf<TestStep>()
        var slow = false
        var fail = false
        val urls = listOf(
            "插画缩略图" to FALLBACK_ILLUST_URL,
            "画师头像" to FALLBACK_AVATAR_URL,
        )
        urls.forEachIndexed { i, (label, raw) ->
            if (cancelled.get()) return
            val rewritten = ImageHosts.rewrite(raw)
            val client = DesktopClient.imageHttp(directPximg = AppGraph.useDirectPximg(graph.settings.current))
                .newBuilder()
                .proxy(AppGraph.proxyFor(graph.settings.current))
                .dns(AppGraph.dnsFor(graph.settings.current))
                .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
                .build()
            val t0 = System.currentTimeMillis()
            try {
                val req = Request.Builder()
                    .url(rewritten)
                    .header("Referer", PixivClientIdentity.IMAGE_REFERER)
                    .header("User-Agent", PixivClientIdentity.USER_AGENT)
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    val ttfb = System.currentTimeMillis() - t0
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    val ms = System.currentTimeMillis() - t0
                    val transfer = (ms - ttfb).coerceAtLeast(1)
                    val kbs = bytes.size * 1000L / transfer / 1024
                    val magic = imageMagic(bytes)
                    val ok = resp.isSuccessful && magic != null
                    val thisSlow = i == 0 && bytes.size >= NetCidr.MIN_SPEED_SAMPLE_BYTES && kbs < NetCidr.SLOW_THROUGHPUT_KBS
                    slow = slow || thisSlow
                    fail = fail || !ok
                    val st = when {
                        !ok -> StepStatus.FAIL
                        ttfb > NetCidr.IMAGE_TTFB_EXTREME_MS -> StepStatus.EXTREME_LATENCY
                        ttfb > NetCidr.IMAGE_TTFB_HIGH_MS -> StepStatus.HIGH_LATENCY
                        thisSlow -> StepStatus.WARN
                        else -> StepStatus.OK
                    }
                    if (st == StepStatus.EXTREME_LATENCY) imageLatency = TargetStatus.EXTREME_LATENCY
                    else if (st == StepStatus.HIGH_LATENCY && imageLatency == null) imageLatency = TargetStatus.HIGH_LATENCY
                    steps += TestStep(
                        label,
                        "HTTP ${resp.code} · ${formatBytes(bytes.size)} · TTFB ${ttfb}ms · ${kbs}KB/s · ${magic ?: "非图片"} · ${rewritten.substringAfter("://").substringBefore('/')}",
                        st,
                    )
                    line("$label: HTTP ${resp.code} ${bytes.size}B TTFB ${ttfb}ms $kbs KB/s $magic")
                }
            } catch (e: Exception) {
                fail = true
                steps += TestStep(label, "${e.javaClass.simpleName}: ${e.message}", StepStatus.FAIL)
                line("$label 失败: ${e.message}")
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
        imageDownloadFailed = fail
        val status = when {
            fail -> TargetStatus.FAILED
            imageLatency == TargetStatus.EXTREME_LATENCY -> TargetStatus.EXTREME_LATENCY
            imageLatency == TargetStatus.HIGH_LATENCY -> TargetStatus.HIGH_LATENCY
            slow -> TargetStatus.DEGRADED
            else -> TargetStatus.OK
        }
        imageReport = TargetReport(
            title = "图片下载探测",
            subtitle = "样例 $SAMPLE_ILLUST_ID · ${imageCfg.displayName ?: imageCfg.host}",
            status = status,
            steps = steps,
            statusPillOverride = if (fail) "图片无法加载" else null,
        )
        emit(running = true)
    }

    private fun runIllustApi(id: Long) {
        val t0 = System.currentTimeMillis()
        illustReport = try {
            val detail = kotlinx.coroutines.runBlocking { graph.client.api.illustDetail(id) }.illust
            val ms = System.currentTimeMillis() - t0
            TargetReport(
                title = "作品 API 探测",
                subtitle = "/v1/illust/detail · $id",
                status = if (ms > NetCidr.EXTREME_LATENCY_MS) TargetStatus.EXTREME_LATENCY
                else if (ms > NetCidr.HIGH_LATENCY_MS) TargetStatus.HIGH_LATENCY
                else TargetStatus.OK,
                steps = listOf(
                    TestStep(
                        "illust/detail",
                        "HTTP 成功 · ${ms}ms · ${detail?.title ?: id} · user ${detail?.user?.name ?: "-"}",
                        StepStatus.OK,
                    ),
                ),
            )
        } catch (e: Exception) {
            TargetReport(
                title = "作品 API 探测",
                subtitle = "/v1/illust/detail · $id",
                status = TargetStatus.FAILED,
                steps = listOf(TestStep("illust/detail", "${e.javaClass.simpleName}: ${e.message}", StepStatus.FAIL)),
            )
        }
        line("illust $id: ${illustReport?.steps?.firstOrNull()?.detail}")
        emit(running = true)
    }

    private fun lookupSystem(host: String): List<InetAddress> =
        runCatching { InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())

    private fun addTarget(report: TargetReport): Int {
        work += report
        emit(running = true)
        return work.lastIndex
    }

    private fun addStep(idx: Int, step: TestStep) {
        val cur = work[idx]
        work[idx] = cur.copy(steps = cur.steps + step)
        emit(running = true)
    }

    private fun updateStep(idx: Int, stepIdx: Int, detail: String, status: StepStatus) {
        val cur = work[idx]
        val steps = cur.steps.toMutableList()
        if (stepIdx in steps.indices) {
            steps[stepIdx] = steps[stepIdx].copy(detail = detail, status = status)
            work[idx] = cur.copy(steps = steps)
            emit(running = true)
        }
    }

    private fun setStatus(idx: Int, status: TargetStatus) {
        work[idx] = work[idx].copy(status = status)
        emit(running = true)
    }

    private fun line(text: String) {
        if (log.isNotEmpty()) log.append('\n')
        log.append(text)
    }

    private fun emit(
        running: Boolean,
        overall: NetCidr.Overall? = null,
        overallSub: String? = null,
        pollutionBypassed: Boolean = false,
        imageTargetFailed: Boolean = false,
    ) {
        val s = graph.settings.current
        onUpdate(
            NetworkTestSnapshot(
                running = running,
                targets = work.toList(),
                overall = overall,
                overallSub = overallSub,
                rawLog = log.toString(),
                imageReport = imageReport,
                illustReport = illustReport,
                pollutionBypassed = pollutionBypassed,
                imageTargetFailed = imageTargetFailed || imageFailed,
                envDirect = s.directConnect,
                envDoh = s.useSecureDns,
                envImageHost = AppGraph.imageHostLabel(s.imageHostMode),
                envChromium = Chromium.findBrowser()?.fileName?.toString() ?: "未找到 Chrome / Edge",
                envProxy = describeProxy(s.directConnect),
            ),
        )
    }

    private fun overallSub(
        ov: NetCidr.Overall,
        appApiFailed: Boolean,
        imageFailed: Boolean,
        imageCfg: TargetConfig,
        bypass: Boolean,
    ): String {
        val bits = mutableListOf<String>()
        when (ov) {
            NetCidr.Overall.NETWORK_DOWN -> bits += "主 API（app-api.pixiv.net）不可达。直连请确认本机有 Chrome/Edge；否则打开代理后关掉直连。"
            NetCidr.Overall.POLLUTED -> bits += if (bypass) {
                "本机 DNS 被污染，但已通过安全 DNS / 直连绕过，握手正常。"
            } else {
                "本机 DNS 被污染。建议同时开启直连和安全 DNS。"
            }
            NetCidr.Overall.DEGRADED -> bits += "部分端点失败或降级，浏览可能不稳定。"
            NetCidr.Overall.EXTREME_LATENCY -> bits += "延迟极高，建议换图片源或检查线路。"
            NetCidr.Overall.HIGH_LATENCY -> bits += "延迟偏高。"
            NetCidr.Overall.CLEAN -> bits += "DNS / 握手正常。"
        }
        if (imageFailed) bits += "图片源 ${imageCfg.displayName ?: imageCfg.host} 失败，可在设置里换成 pixiv.cat / pixiv.re / pixiv.nl。"
        return bits.joinToString("\n")
    }

    companion object {
        const val SAMPLE_ILLUST_ID = 73949833L
        const val FALLBACK_ILLUST_URL =
            "https://i.pximg.net/c/540x540_70/img-master/img/2019/03/30/16/33/50/73949833_p0_master1200.jpg"
        const val FALLBACK_AVATAR_URL =
            "https://i.pximg.net/user-profile/img/2017/04/27/10/00/38/12474975_a0a699ea19f387df0f98bc5a9b7d26d3_170.png"

        fun onOff(v: Boolean): String = if (v) "开" else "关"

        fun describeProxy(direct: Boolean): String {
            if (direct) return "直连（已强制 --no-proxy-server）"
            return Chromium.detectProxy() ?: "未检测到系统代理"
        }

        fun mask(host: String): String {
            val trimmed = host.trim()
            return if (trimmed.length <= 8) "$trimmed***" else trimmed.take(8) + "***"
        }

        fun formatBytes(n: Int): String = when {
            n < 1024 -> "${n}B"
            n < 1024 * 1024 -> "${n / 1024}KB"
            else -> String.format("%.1fMB", n / 1024.0 / 1024.0)
        }

        fun imageMagic(data: ByteArray): String? {
            if (data.size < 8) return null
            return when {
                data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() -> "JPEG"
                data[0] == 0x89.toByte() && data[1] == 0x50.toByte() -> "PNG"
                data[0] == 'G'.code.toByte() && data[1] == 'I'.code.toByte() -> "GIF"
                data[0] == 'R'.code.toByte() && data[1] == 'I'.code.toByte() -> "WEBP"
                else -> null
            }
        }

        fun statusLabel(status: TargetStatus): String = when (status) {
            TargetStatus.RUNNING -> "检测中"
            TargetStatus.OK -> "正常"
            TargetStatus.HIGH_LATENCY -> "高延迟"
            TargetStatus.EXTREME_LATENCY -> "延迟极高"
            TargetStatus.DEGRADED -> "降级"
            TargetStatus.POLLUTED -> "DNS 污染"
            TargetStatus.POLLUTED_BYPASSED -> "污染已绕过"
            TargetStatus.FAILED -> "失败"
        }

        fun overallLabel(ov: NetCidr.Overall): String = when (ov) {
            NetCidr.Overall.CLEAN -> "网络通畅"
            NetCidr.Overall.HIGH_LATENCY -> "延迟偏高"
            NetCidr.Overall.EXTREME_LATENCY -> "延迟极高"
            NetCidr.Overall.DEGRADED -> "部分不可用"
            NetCidr.Overall.POLLUTED -> "检测到 DNS 污染"
            NetCidr.Overall.NETWORK_DOWN -> "网络不可用"
        }
    }
}
