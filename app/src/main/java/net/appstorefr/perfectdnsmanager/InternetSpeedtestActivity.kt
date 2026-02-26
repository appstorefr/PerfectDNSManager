package net.appstorefr.perfectdnsmanager

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import net.appstorefr.perfectdnsmanager.util.LocaleHelper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * LibreSpeed server data class.
 */
data class LibreSpeedServer(
    val id: Int,
    val name: String,
    val server: String,
    val dlURL: String,
    val ulURL: String,
    val pingURL: String,
    val getIpURL: String
) {
    override fun toString(): String = name
}

/**
 * Native LibreSpeed speed test activity.
 *
 * Uses the LibreSpeed protocol (garbage.php / empty.php / getIP.php) with OkHttp
 * to measure download, upload, ping and jitter — no WebView needed.
 */
class InternetSpeedtestActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "InternetSpeedtest"

        // Dark theme colors
        private const val COLOR_BG = 0xFF1A1A2E.toInt()
        private const val COLOR_BG_CARD = 0xFF16213E.toInt()
        private const val COLOR_WHITE = 0xFFFFFFFF.toInt()
        private const val COLOR_LIGHT_GREY = 0xFFCCCCCC.toInt()
        private const val COLOR_CYAN = 0xFF00E5FF.toInt()
        private const val COLOR_GREEN = 0xFF4CAF50.toInt()
        private const val COLOR_RED = 0xFFF44336.toInt()
        private const val COLOR_VIOLET = 0xFFBB86FC.toInt()
        private const val COLOR_DIM = 0xFF888888.toInt()

        // Test parameters
        private const val PING_COUNT = 10
        private const val DL_CHUNK_SIZE = 25         // MB for garbage.php?ckSize=
        private const val DL_CONNECTIONS = 4
        private const val DL_DURATION_SEC = 10
        private const val UL_CHUNK_SIZE = 10 * 1024 * 1024  // 10 MB payload
        private const val UL_CONNECTIONS = 3
        private const val UL_DURATION_SEC = 10

        private const val SERVER_LIST_URL =
            "https://librespeed.org/backend-servers/servers.php"

        /** Hardcoded premium servers added before the remote list. */
        private val HARDCODED_SERVERS = listOf(
            LibreSpeedServer(
                id = 9001,
                name = "\u2B50 Premium 1 (FR)",
                server = "https://1.firstcloud.me/",
                dlURL = "garbage.php",
                ulURL = "empty.php",
                pingURL = "empty.php",
                getIpURL = "getIP.php"
            ),
            LibreSpeedServer(
                id = 9002,
                name = "\u2B50 Premium 2 (FR)",
                server = "https://2.firstcloud.me/",
                dlURL = "garbage.php",
                ulURL = "empty.php",
                pingURL = "empty.php",
                getIpURL = "getIP.php"
            ),
            LibreSpeedServer(
                id = 9003,
                name = "\u2B50 Premium 3 (FR)",
                server = "https://3.firstcloud.me/",
                dlURL = "garbage.php",
                ulURL = "empty.php",
                pingURL = "empty.php",
                getIpURL = "getIP.php"
            ),
            LibreSpeedServer(
                id = 9004,
                name = "\u2B50 Premium 4 (FR)",
                server = "https://4.firstcloud.me/",
                dlURL = "garbage.php",
                ulURL = "empty.php",
                pingURL = "empty.php",
                getIpURL = "getIP.php"
            )
        )
    }

    // ── UI widgets ───────────────────────────────────────────────────────────
    private lateinit var spinnerServer: Spinner
    private lateinit var btnStartStop: Button
    private lateinit var tvPing: TextView
    private lateinit var tvJitter: TextView
    private lateinit var tvDownload: TextView
    private lateinit var tvUpload: TextView
    private lateinit var tvClientIp: TextView
    private lateinit var pbDownload: ProgressBar
    private lateinit var pbUpload: ProgressBar
    private lateinit var tvConsole: TextView
    private lateinit var scrollConsole: ScrollView

    // ── State ────────────────────────────────────────────────────────────────
    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private var testThread: Thread? = null
    private val servers = mutableListOf<LibreSpeedServer>()
    private var selectedServer: LibreSpeedServer? = null

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUI())
        loadServerList()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelled.set(true)
        testThread?.interrupt()
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  UI CONSTRUCTION (fully programmatic)
    // ═════════════════════════════════════════════════════════════════════════

    private fun buildUI(): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = ScrollView(this).apply {
            setBackgroundColor(COLOR_BG)
            isFillViewport = true
        }

        val mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        // ── Header ───────────────────────────────────────────────────────
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(matchParent, wrapContent).apply { bottomMargin = dp(12) }
        }

        val btnBack = Button(this).apply {
            text = getString(R.string.back_arrow)
            setTextColor(COLOR_WHITE)
            setBackgroundResource(R.drawable.focusable_item_background)
            foreground = resources.getDrawable(R.drawable.btn_focus_foreground, theme)
            isFocusable = true
            setPadding(dp(20), dp(10), dp(20), dp(10))
            setOnClickListener { finish() }
        }
        header.addView(btnBack)

        val tvTitle = TextView(this).apply {
            text = "Testeur de d\u00e9bit (avanc\u00e9)"
            setTextColor(COLOR_WHITE)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, wrapContent, 1f)
        }
        header.addView(tvTitle)
        mainColumn.addView(header)

        // ── Server selector ──────────────────────────────────────────────
        val serverRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(matchParent, wrapContent).apply { bottomMargin = dp(12) }
        }

        serverRow.addView(TextView(this).apply {
            text = "Serveur :"
            setTextColor(COLOR_LIGHT_GREY)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(wrapContent, wrapContent).apply {
                marginEnd = dp(8)
            }
        })

        spinnerServer = Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, wrapContent, 1f)
            setBackgroundResource(R.drawable.focusable_item_background)
            isFocusable = true
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        serverRow.addView(spinnerServer)
        mainColumn.addView(serverRow)

        // ── Start / Stop button ──────────────────────────────────────────
        btnStartStop = Button(this).apply {
            text = "D\u00e9marrer le test"
            setTextColor(COLOR_WHITE)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            isFocusable = true
            foreground = resources.getDrawable(R.drawable.btn_focus_foreground, theme)
            background = greenPill(dp(8))
            setPadding(dp(24), dp(14), dp(24), dp(14))
            layoutParams = lp(matchParent, wrapContent).apply { bottomMargin = dp(16) }
            setOnClickListener { toggleTest() }
        }
        mainColumn.addView(btnStartStop)

        // ── Results card ─────────────────────────────────────────────────
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(COLOR_BG_CARD); cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = lp(matchParent, wrapContent).apply { bottomMargin = dp(16) }
        }

        // Ping / Jitter
        val pingRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(matchParent, wrapContent).apply { bottomMargin = dp(12) }
        }
        tvPing = metricBlock(pingRow, "PING", "-- ms", COLOR_CYAN, dp(0))
        tvJitter = metricBlock(pingRow, "JITTER", "-- ms", COLOR_CYAN, dp(0))
        card.addView(pingRow)

        // Download
        val dlSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lp(matchParent, wrapContent).apply { bottomMargin = dp(12) }
        }
        val dlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(matchParent, wrapContent).apply { bottomMargin = dp(4) }
        }
        dlRow.addView(label("\u2B07 DOWNLOAD", COLOR_GREEN, 13f, dp(0)))
        tvDownload = TextView(this).apply {
            text = "-- Mbps"; setTextColor(COLOR_WHITE); textSize = 20f
            setTypeface(typeface, Typeface.BOLD); gravity = Gravity.END
        }
        dlRow.addView(tvDownload)
        dlSection.addView(dlRow)
        pbDownload = horizontalBar(COLOR_GREEN, dp(6))
        dlSection.addView(pbDownload)
        card.addView(dlSection)

        // Upload
        val ulSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lp(matchParent, wrapContent).apply { bottomMargin = dp(12) }
        }
        val ulRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(matchParent, wrapContent).apply { bottomMargin = dp(4) }
        }
        ulRow.addView(label("\u2B06 UPLOAD", COLOR_VIOLET, 13f, dp(0)))
        tvUpload = TextView(this).apply {
            text = "-- Mbps"; setTextColor(COLOR_WHITE); textSize = 20f
            setTypeface(typeface, Typeface.BOLD); gravity = Gravity.END
        }
        ulRow.addView(tvUpload)
        ulSection.addView(ulRow)
        pbUpload = horizontalBar(COLOR_VIOLET, dp(6))
        ulSection.addView(pbUpload)
        card.addView(ulSection)

        // Client IP
        tvClientIp = TextView(this).apply {
            text = "IP : --"; setTextColor(COLOR_LIGHT_GREY); textSize = 13f
            gravity = Gravity.CENTER
        }
        card.addView(tvClientIp)
        mainColumn.addView(card)

        // ── Console ──────────────────────────────────────────────────────
        mainColumn.addView(TextView(this).apply {
            text = "Journal du test"; setTextColor(COLOR_DIM); textSize = 12f
            layoutParams = lp(matchParent, wrapContent).apply { bottomMargin = dp(4) }
        })

        scrollConsole = ScrollView(this).apply {
            layoutParams = lp(matchParent, dp(200))
            background = GradientDrawable().apply {
                setColor(0xFF0D0D1A.toInt()); cornerRadius = dp(8).toFloat()
            }
        }
        tvConsole = TextView(this).apply {
            setTextColor(COLOR_LIGHT_GREY); textSize = 11f
            setPadding(dp(8), dp(8), dp(8), dp(8))
            text = "En attente...\n"
        }
        scrollConsole.addView(tvConsole)
        mainColumn.addView(scrollConsole)

        root.addView(mainColumn)
        btnBack.requestFocus()
        return root
    }

    /* ── tiny layout helpers ────────────────────────────────────────────── */

    private val matchParent get() = LinearLayout.LayoutParams.MATCH_PARENT
    private val wrapContent get() = LinearLayout.LayoutParams.WRAP_CONTENT

    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)

    private fun greenPill(r: Int): GradientDrawable =
        GradientDrawable().apply { setColor(COLOR_GREEN); cornerRadius = r.toFloat() }

    private fun redPill(r: Int): GradientDrawable =
        GradientDrawable().apply { setColor(COLOR_RED); cornerRadius = r.toFloat() }

    /** Create a "label + big value" column inside [parent] and return the value TextView. */
    private fun metricBlock(
        parent: LinearLayout, title: String, initial: String, color: Int, margin: Int
    ): TextView {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, wrapContent, 1f)
        }
        col.addView(TextView(this).apply {
            text = title; setTextColor(COLOR_DIM); textSize = 12f; gravity = Gravity.CENTER
        })
        val tv = TextView(this).apply {
            text = initial; setTextColor(color); textSize = 22f
            setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        }
        col.addView(tv)
        parent.addView(col)
        return tv
    }

    private fun label(text: String, color: Int, size: Float, margin: Int): TextView =
        TextView(this).apply {
            this.text = text; setTextColor(color); textSize = size
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, wrapContent, 1f)
        }

    @Suppress("DEPRECATION")
    private fun horizontalBar(color: Int, height: Int): ProgressBar =
        ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progress = 0
            layoutParams = lp(matchParent, height)
            progressDrawable.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
        }

    // ═════════════════════════════════════════════════════════════════════════
    //  SERVER LIST
    // ═════════════════════════════════════════════════════════════════════════

    private fun loadServerList() {
        logConsole("Chargement de la liste des serveurs...")
        Thread {
            val fetched = mutableListOf<LibreSpeedServer>()
            try {
                val client = plainClient(10)
                val req = Request.Builder()
                    .url(SERVER_LIST_URL)
                    .header("Accept", "application/json")
                    .build()
                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) {
                    val json = resp.body?.string() ?: "[]"
                    resp.close()
                    val type = object : TypeToken<List<LibreSpeedServer>>() {}.type
                    fetched.addAll(Gson().fromJson<List<LibreSpeedServer>>(json, type))
                } else {
                    resp.close()
                    ui { logConsole("Erreur serveur : ${resp.code}") }
                }
                shutdown(client)
            } catch (e: Exception) {
                Log.w(TAG, "Server list fetch failed", e)
                ui { logConsole("Erreur chargement : ${e.message}") }
            }

            ui {
                servers.clear()
                servers.addAll(HARDCODED_SERVERS)
                servers.addAll(fetched)

                if (servers.isEmpty()) {
                    logConsole("Aucun serveur disponible.")
                    return@ui
                }
                logConsole("${servers.size} serveur(s) charg\u00e9(s).")

                val adapter = object : ArrayAdapter<LibreSpeedServer>(
                    this@InternetSpeedtestActivity,
                    android.R.layout.simple_spinner_item,
                    servers
                ) {
                    override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
                        val tv = super.getView(pos, cv, parent) as TextView
                        tv.setTextColor(COLOR_WHITE); tv.textSize = 14f; return tv
                    }
                    override fun getDropDownView(pos: Int, cv: View?, parent: ViewGroup): View {
                        val tv = super.getDropDownView(pos, cv, parent) as TextView
                        tv.setTextColor(COLOR_WHITE); tv.setBackgroundColor(COLOR_BG_CARD)
                        tv.setPadding(24, 16, 24, 16); tv.textSize = 14f; return tv
                    }
                }
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinnerServer.adapter = adapter
                spinnerServer.onItemSelectedListener =
                    object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                            selectedServer = servers[pos]
                        }
                        override fun onNothingSelected(p: AdapterView<*>?) {}
                    }
                selectedServer = servers[0]
            }
        }.start()
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  TEST CONTROL
    // ═════════════════════════════════════════════════════════════════════════

    private fun toggleTest() {
        if (running.get()) stopTest() else startTest()
    }

    private fun startTest() {
        val server = selectedServer ?: run {
            logConsole("Aucun serveur s\u00e9lectionn\u00e9."); return
        }
        running.set(true); cancelled.set(false)
        updateButton(true); resetResults()

        logConsole("=== D\u00e9but du test ===")
        logConsole("Serveur : ${server.name}")
        logConsole("URL : ${server.server}")

        testThread = Thread {
            try {
                if (!cancelled.get()) fetchClientIp(server)
                if (!cancelled.get()) runPingTest(server)
                if (!cancelled.get()) runDownloadTest(server)
                if (!cancelled.get()) runUploadTest(server)
                if (!cancelled.get()) ui { logConsole("=== Test termin\u00e9 ===") }
            } catch (_: InterruptedException) {
                ui { logConsole("Test interrompu.") }
            } catch (e: Exception) {
                Log.e(TAG, "Test error", e)
                ui { logConsole("Erreur : ${e.message}") }
            } finally {
                running.set(false)
                ui { updateButton(false) }
            }
        }.also { it.start() }
    }

    private fun stopTest() {
        logConsole("Arr\u00eat du test...")
        cancelled.set(true)
        testThread?.interrupt()
    }

    private fun updateButton(isRunning: Boolean) {
        val dp8 = (8 * resources.displayMetrics.density).toInt()
        btnStartStop.text = if (isRunning) "Arr\u00eater" else "D\u00e9marrer le test"
        btnStartStop.background = if (isRunning) redPill(dp8) else greenPill(dp8)
        btnStartStop.foreground = resources.getDrawable(R.drawable.btn_focus_foreground, theme)
    }

    private fun resetResults() {
        tvPing.text = "-- ms"; tvJitter.text = "-- ms"
        tvDownload.text = "-- Mbps"; tvUpload.text = "-- Mbps"
        tvClientIp.text = "IP : --"
        pbDownload.progress = 0; pbUpload.progress = 0
        tvConsole.text = ""
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  CLIENT IP
    // ═════════════════════════════════════════════════════════════════════════

    private fun fetchClientIp(server: LibreSpeedServer) {
        ui { logConsole("R\u00e9cup\u00e9ration de l'IP...") }
        try {
            val client = plainClient(10)
            val resp = client.newCall(
                Request.Builder().url(server.server + server.getIpURL).build()
            ).execute()
            if (resp.isSuccessful) {
                val raw = resp.body?.string().orEmpty().trim()
                resp.close(); shutdown(client)
                // getIP.php may return JSON {"processedString":"x.x.x.x", ...} or plain text
                val ip = try {
                    @Suppress("UNCHECKED_CAST")
                    val map = Gson().fromJson(raw, Map::class.java)
                    (map["processedString"] as? String) ?: raw
                } catch (_: Exception) { raw }
                ui { tvClientIp.text = "IP : $ip"; logConsole("IP client : $ip") }
            } else {
                val code = resp.code; resp.close(); shutdown(client)
                ui { logConsole("IP : erreur $code") }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getIP failed", e)
            ui { logConsole("IP : ${e.message}") }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  PING TEST
    // ═════════════════════════════════════════════════════════════════════════

    private fun runPingTest(server: LibreSpeedServer) {
        ui { logConsole("\n--- Ping ($PING_COUNT requ\u00eates) ---") }
        val client = plainClient(5)
        val url = server.server + server.pingURL
        val pings = mutableListOf<Double>()

        for (i in 1..PING_COUNT) {
            if (cancelled.get()) break
            try {
                val req = Request.Builder()
                    .url("$url?r=${System.nanoTime()}")
                    .header("Cache-Control", "no-cache")
                    .build()
                val t0 = System.nanoTime()
                val resp = client.newCall(req).execute()
                val ms = (System.nanoTime() - t0) / 1_000_000.0
                resp.body?.close(); resp.close()
                if (resp.isSuccessful) {
                    pings.add(ms)
                    ui { logConsole("  #$i : ${"%.1f".format(ms)} ms") }
                }
            } catch (e: Exception) {
                if (cancelled.get()) break
                ui { logConsole("  #$i : \u00e9chec") }
            }
        }
        shutdown(client)

        if (pings.isNotEmpty() && !cancelled.get()) {
            val avg = pings.average()
            val jitter = if (pings.size > 1)
                pings.zipWithNext { a, b -> abs(b - a) }.average() else 0.0
            ui {
                tvPing.text = "${"%.1f".format(avg)} ms"
                tvJitter.text = "${"%.1f".format(jitter)} ms"
                logConsole("  Moy : ${"%.1f".format(avg)} ms | Jitter : ${"%.1f".format(jitter)} ms")
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  DOWNLOAD TEST
    // ═════════════════════════════════════════════════════════════════════════

    private fun runDownloadTest(server: LibreSpeedServer) {
        ui { logConsole("\n--- Download ($DL_CONNECTIONS conn., ${DL_DURATION_SEC}s) ---") }

        val url = server.server + server.dlURL + "?ckSize=$DL_CHUNK_SIZE"
        val totalBytes = AtomicLong(0)
        val t0 = System.nanoTime()
        val deadline = t0 + DL_DURATION_SEC * 1_000_000_000L

        val workers = (0 until DL_CONNECTIONS).map { idx ->
            Thread {
                val c = plainClient(DL_DURATION_SEC.toLong() + 5)
                try {
                    while (!cancelled.get() && System.nanoTime() < deadline) {
                        val req = Request.Builder()
                            .url("$url&r=${System.nanoTime()}")
                            .header("Cache-Control", "no-store, no-cache")
                            .build()
                        val resp = c.newCall(req).execute()
                        if (resp.isSuccessful) {
                            resp.body?.byteStream()?.use { stream ->
                                val buf = ByteArray(65536)
                                while (!cancelled.get() && System.nanoTime() < deadline) {
                                    val n = stream.read(buf)
                                    if (n == -1) break
                                    totalBytes.addAndGet(n.toLong())
                                }
                            }
                        }
                        resp.close()
                    }
                } catch (_: Exception) {
                    /* expected when deadline reached */
                } finally { shutdown(c) }
            }.also { it.isDaemon = true; it.start() }
        }

        // Progress reporter
        monitorProgress(totalBytes, t0, deadline, DL_DURATION_SEC) { mbps, progress ->
            tvDownload.text = "${"%.2f".format(mbps)} Mbps"
            pbDownload.progress = progress
        }

        workers.forEach { it.join(2000); if (it.isAlive) it.interrupt() }

        if (!cancelled.get()) {
            val elapsed = (System.nanoTime() - t0) / 1e9
            val bytes = totalBytes.get()
            val mbps = if (elapsed > 0) (bytes * 8.0) / (elapsed * 1e6) else 0.0
            ui {
                tvDownload.text = "${"%.2f".format(mbps)} Mbps"; pbDownload.progress = 1000
                logConsole("  ${"%.2f".format(mbps)} Mbps (${"%.1f".format(bytes / 1_048_576.0)} Mo / ${"%.1f".format(elapsed)}s)")
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  UPLOAD TEST
    // ═════════════════════════════════════════════════════════════════════════

    private fun runUploadTest(server: LibreSpeedServer) {
        ui { logConsole("\n--- Upload ($UL_CONNECTIONS conn., ${UL_DURATION_SEC}s) ---") }

        val url = server.server + server.ulURL
        val totalBytes = AtomicLong(0)
        val t0 = System.nanoTime()
        val deadline = t0 + UL_DURATION_SEC * 1_000_000_000L
        val payload = ByteArray(UL_CHUNK_SIZE) { (it % 256).toByte() }

        val workers = (0 until UL_CONNECTIONS).map { idx ->
            Thread {
                val c = plainClient(UL_DURATION_SEC.toLong() + 5)
                try {
                    while (!cancelled.get() && System.nanoTime() < deadline) {
                        val body = object : okhttp3.RequestBody() {
                            override fun contentType() = "application/octet-stream".toMediaType()
                            override fun contentLength() = payload.size.toLong()
                            override fun writeTo(sink: okio.BufferedSink) {
                                var off = 0; val chunk = 65536
                                while (off < payload.size && !cancelled.get() && System.nanoTime() < deadline) {
                                    val len = minOf(chunk, payload.size - off)
                                    sink.write(payload, off, len)
                                    sink.flush()
                                    totalBytes.addAndGet(len.toLong())
                                    off += len
                                }
                            }
                        }
                        val req = Request.Builder()
                            .url("$url?r=${System.nanoTime()}")
                            .post(body)
                            .build()
                        try {
                            c.newCall(req).execute().close()
                        } catch (_: Exception) {
                            /* expected near deadline */
                        }
                    }
                } catch (_: Exception) {
                } finally { shutdown(c) }
            }.also { it.isDaemon = true; it.start() }
        }

        monitorProgress(totalBytes, t0, deadline, UL_DURATION_SEC) { mbps, progress ->
            tvUpload.text = "${"%.2f".format(mbps)} Mbps"
            pbUpload.progress = progress
        }

        workers.forEach { it.join(2000); if (it.isAlive) it.interrupt() }

        if (!cancelled.get()) {
            val elapsed = (System.nanoTime() - t0) / 1e9
            val bytes = totalBytes.get()
            val mbps = if (elapsed > 0) (bytes * 8.0) / (elapsed * 1e6) else 0.0
            ui {
                tvUpload.text = "${"%.2f".format(mbps)} Mbps"; pbUpload.progress = 1000
                logConsole("  ${"%.2f".format(mbps)} Mbps (${"%.1f".format(bytes / 1_048_576.0)} Mo / ${"%.1f".format(elapsed)}s)")
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Polls [totalBytes] every 500 ms until [deadline] and posts UI updates
     * through [onTick] (called on UI thread with current Mbps and progress 0-1000).
     */
    private fun monitorProgress(
        totalBytes: AtomicLong,
        t0: Long,
        deadline: Long,
        durationSec: Int,
        onTick: (mbps: Double, progress: Int) -> Unit
    ) {
        while (!cancelled.get() && System.nanoTime() < deadline) {
            Thread.sleep(500)
            val now = System.nanoTime()
            val elapsed = (now - t0) / 1e9
            val bytes = totalBytes.get()
            val mbps = if (elapsed > 0) (bytes * 8.0) / (elapsed * 1e6) else 0.0
            val pct = ((elapsed / durationSec) * 1000).toInt().coerceAtMost(1000)
            ui { onTick(mbps, pct) }
        }
    }

    /** Post to UI thread. */
    private fun ui(block: () -> Unit) = runOnUiThread(block)

    /** Append a line to the console TextView (must be called on UI thread). */
    private fun logConsole(msg: String) {
        if (Thread.currentThread() != mainLooper.thread) {
            ui { logConsole(msg) }; return
        }
        tvConsole.append(msg + "\n")
        scrollConsole.post { scrollConsole.fullScroll(View.FOCUS_DOWN) }
    }

    /** Fresh OkHttpClient that does NOT use the app's VPN/DNS tunnel. */
    private fun plainClient(timeoutSec: Long): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(timeoutSec, TimeUnit.SECONDS)
            .readTimeout(timeoutSec, TimeUnit.SECONDS)
            .writeTimeout(timeoutSec, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()

    private fun shutdown(c: OkHttpClient) {
        try { c.dispatcher.executorService.shutdown(); c.connectionPool.evictAll() }
        catch (_: Exception) {}
    }
}
