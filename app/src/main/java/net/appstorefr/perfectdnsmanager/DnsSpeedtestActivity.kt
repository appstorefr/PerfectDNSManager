package net.appstorefr.perfectdnsmanager

import android.content.Context
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import net.appstorefr.perfectdnsmanager.data.DnsProfile
import net.appstorefr.perfectdnsmanager.data.DnsType
import net.appstorefr.perfectdnsmanager.util.DnsTester
import net.appstorefr.perfectdnsmanager.util.LocaleHelper
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class DnsSpeedtestActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    private lateinit var tvProgress: TextView
    private lateinit var tvResults: TextView
    private lateinit var tvTop5: TextView
    private lateinit var scrollProgress: ScrollView
    private lateinit var scrollResults: ScrollView
    private lateinit var scrollTop5: ScrollView
    private lateinit var btnStartStop: Button
    private lateinit var btnBack: Button

    @Volatile
    private var cancelled = false
    @Volatile private var running = false
    private var testThread: Thread? = null

    companion object {
        private const val COLOR_GREEN = 0xFF4CAF50.toInt()
        private const val COLOR_YELLOW = 0xFFFFEB3B.toInt()
        private const val COLOR_ORANGE = 0xFFFF9800.toInt()
        private const val COLOR_RED = 0xFFF44336.toInt()
        private const val COLOR_GREY = 0xFF888888.toInt()
        private const val COLOR_WHITE = 0xFFEEEEEE.toInt()
        private const val COLOR_CYAN = 0xFF00BCD4.toInt()
        private const val COLOR_GOLD = 0xFFFFD700.toInt()
    }

    private data class SpeedResult(
        val provider: String,
        val address: String,
        val latency: Long?,
        val type: String
    )

    private val progressBuffer = SpannableStringBuilder()
    private val resultsBuffer = SpannableStringBuilder()
    private val top5Buffer = SpannableStringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dns_speedtest)

        tvProgress = findViewById(R.id.tvProgress)
        tvResults = findViewById(R.id.tvResults)
        tvTop5 = findViewById(R.id.tvTop5)
        scrollProgress = findViewById(R.id.scrollProgress)
        scrollResults = findViewById(R.id.scrollResults)
        scrollTop5 = findViewById(R.id.scrollTop5)
        btnStartStop = findViewById(R.id.btnStartStop)
        btnBack = findViewById(R.id.btnBack)

        btnBack.setOnClickListener { finish() }
        btnStartStop.setOnClickListener { toggleStartStop() }

        // Force focus sur btnStartStop dès que la fenêtre est prête
        btnStartStop.isFocusable = true
        btnStartStop.isFocusableInTouchMode = false
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            btnStartStop.focusable = android.view.View.FOCUSABLE
        }

        appendProgress("DNS Speedtest", COLOR_CYAN)
        appendProgress(getString(R.string.dns_speedtest_button), COLOR_WHITE)

        // Setup scroll zones for Android TV D-pad navigation
        setupScrollZone(scrollResults, tvResults)
        setupScrollZone(scrollProgress, tvProgress)
        setupScrollZone(scrollTop5, tvTop5)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !running) {
            btnStartStop.requestFocus()
        }
    }

    /**
     * Setup a scroll zone for Android TV D-pad navigation.
     * When the user presses Enter/DpadCenter on a focused ScrollView,
     * the inner TextView becomes focusable and receives focus for D-pad scrolling.
     * Pressing Back while inside returns focus to the ScrollView itself.
     */
    private fun setupScrollZone(scroll: ScrollView, tv: TextView) {
        scroll.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                // Enter the scroll zone - allow descendants and give focus to content
                scroll.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                tv.isFocusable = true
                tv.requestFocus()
                true
            } else false
        }
        tv.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_BACK) {
                // Exit the scroll zone - block descendants and return focus to ScrollView
                tv.isFocusable = false
                scroll.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                scroll.requestFocus()
                true
            } else false
        }
    }

    private fun toggleStartStop() {
        if (running) {
            cancelled = true
            btnStartStop.text = "Annulation..."
            btnStartStop.isEnabled = false
            // Garder le focus sur le bouton même désactivé
            btnStartStop.requestFocus()
        } else {
            startSpeedtest()
        }
    }

    private fun setButtonStart() {
        runOnUiThread {
            btnStartStop.text = getString(R.string.dns_speedtest_button)
            btnStartStop.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF4CAF50.toInt())
            btnStartStop.isEnabled = true
            running = false
            btnStartStop.requestFocus()
        }
    }

    private fun setButtonStop() {
        runOnUiThread {
            btnStartStop.text = "\u25A0  Stop"
            btnStartStop.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFF44336.toInt())
            btnStartStop.isEnabled = true
            running = true
            btnStartStop.requestFocus()
        }
    }

    private fun startSpeedtest() {
        progressBuffer.clear()
        resultsBuffer.clear()
        top5Buffer.clear()
        tvProgress.text = ""
        tvResults.text = ""
        tvTop5.text = ""
        cancelled = false

        setButtonStop()

        appendProgress("========================================", COLOR_CYAN)
        appendProgress("  DNS Speedtest", COLOR_CYAN)
        appendProgress("========================================", COLOR_CYAN)
        appendProgress("", COLOR_WHITE)

        testThread = Thread {
            try {
                val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
                val advancedEnabled = prefs.getBoolean("advanced_features_enabled", false)
                val operatorEnabled = prefs.getBoolean("operator_dns_enabled", false)

                val presets = DnsProfile.getDefaultPresets()
                val seenKeys = mutableSetOf<String>()
                val testProfiles = presets.filter { p ->
                    if (p.isOperatorDns && !(advancedEnabled && operatorEnabled)) return@filter false
                    if (p.type == DnsType.DOT && !advancedEnabled) return@filter false
                    val key = "${p.providerName}|${p.type}"
                    if (key in seenKeys) return@filter false
                    seenKeys.add(key)
                    true
                }

                appendProgress("${testProfiles.size} fournisseurs", COLOR_WHITE)
                appendProgress("", COLOR_WHITE)

                val sharedClient = OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .writeTimeout(5, TimeUnit.SECONDS)
                    .build()

                val results = mutableListOf<SpeedResult>()

                for ((index, profile) in testProfiles.withIndex()) {
                    if (cancelled) {
                        appendProgress("", COLOR_WHITE)
                        appendProgress(">> Annule.", COLOR_RED)
                        break
                    }

                    val typeLabel = when (profile.type) {
                        DnsType.DOH -> "DoH"
                        DnsType.DOQ -> "DoQ"
                        DnsType.DOT -> "DoT"
                        else -> "UDP"
                    }

                    appendProgress("[${index + 1}/${testProfiles.size}] ${profile.providerName} ($typeLabel)...", COLOR_WHITE)

                    val latency = try {
                        when (profile.type) {
                            DnsType.DOH -> {
                                DnsTester.measureDohLatency(profile.primary, client = sharedClient)
                                DnsTester.measureDohLatency(profile.primary, client = sharedClient)
                            }
                            DnsType.DOT -> {
                                DnsTester.measureDotLatency(profile.primary)
                                DnsTester.measureDotLatency(profile.primary)
                            }
                            DnsType.DOQ -> DnsTester.measureDoqLatency(profile.primary)
                            else -> DnsTester.measureLatency(profile.primary)
                        }
                    } catch (_: Exception) { null }

                    if (latency != null) {
                        val color = when {
                            latency < 50 -> COLOR_GREEN
                            latency < 100 -> COLOR_YELLOW
                            latency < 200 -> COLOR_ORANGE
                            else -> COLOR_RED
                        }
                        appendProgress("   -> ${latency} ms", color)
                    } else {
                        appendProgress("   -> Erreur / Timeout", COLOR_GREY)
                    }

                    results.add(SpeedResult(profile.providerName, profile.primary, latency, typeLabel))
                    updatePanels(results, final = false)
                }

                try { sharedClient.dispatcher.executorService.shutdown() } catch (_: Exception) {}
                try { sharedClient.connectionPool.evictAll() } catch (_: Exception) {}

                if (!cancelled) {
                    appendProgress("", COLOR_WHITE)
                    appendProgress("Termine.", COLOR_GREEN)
                    updatePanels(results, final = true)
                } else {
                    appendProgress("${results.size}/${testProfiles.size} testes.", COLOR_ORANGE)
                    updatePanels(results, final = true)
                }

            } catch (e: Exception) {
                appendProgress("ERREUR : ${e.message}", COLOR_RED)
            } finally {
                setButtonStart()
            }
        }.also { it.start() }
    }

    private fun updatePanels(results: List<SpeedResult>, final: Boolean) {
        runOnUiThread {
            val sorted = results.sortedWith(compareBy(nullsLast()) { it.latency })

            // --- Panel classement (gauche) ---
            resultsBuffer.clear()
            if (final) {
                appendToBuf(resultsBuffer, "Classement complet", COLOR_CYAN)
            } else {
                appendToBuf(resultsBuffer, "Classement provisoire", COLOR_CYAN)
            }
            appendToBuf(resultsBuffer, "────────────────────", COLOR_CYAN)

            for ((i, r) in sorted.withIndex()) {
                val latencyStr = if (r.latency != null) "${r.latency} ms" else "Erreur"
                val color = if (r.latency != null) {
                    when {
                        r.latency < 50 -> COLOR_GREEN
                        r.latency < 100 -> COLOR_YELLOW
                        r.latency < 200 -> COLOR_ORANGE
                        else -> COLOR_RED
                    }
                } else COLOR_GREY
                appendToBuf(resultsBuffer, "${String.format("%2d", i + 1)}. ${r.provider} (${r.type})", color)
                appendToBuf(resultsBuffer, "    $latencyStr", color)
            }

            tvResults.text = resultsBuffer
            scrollResults.post { scrollResults.scrollTo(0, tvResults.bottom) }

            // --- Panel TOP 5 (droite) ---
            top5Buffer.clear()
            val podiumEmojis = listOf("\uD83E\uDD47", "\uD83E\uDD48", "\uD83E\uDD49", "4.", "5.")
            val top5 = sorted.filter { it.latency != null }.take(5)

            if (top5.isNotEmpty()) {
                appendToBuf(top5Buffer, "Meilleurs DNS", COLOR_GOLD)
                appendToBuf(top5Buffer, "────────────────────", COLOR_GOLD)
                appendToBuf(top5Buffer, "", COLOR_WHITE)

                for ((i, r) in top5.withIndex()) {
                    val medal = podiumEmojis.getOrElse(i) { "${i + 1}." }
                    val color = when (i) {
                        0 -> COLOR_GOLD
                        1 -> 0xFFC0C0C0.toInt() // silver
                        2 -> 0xFFCD7F32.toInt() // bronze
                        else -> COLOR_WHITE
                    }
                    appendToBuf(top5Buffer, "$medal ${r.provider}", color)
                    appendToBuf(top5Buffer, "   (${r.type}) ${r.latency} ms", color)
                    appendToBuf(top5Buffer, "", COLOR_WHITE)
                }

                if (final && top5.isNotEmpty()) {
                    appendToBuf(top5Buffer, "────────────────────", COLOR_GREEN)
                    appendToBuf(top5Buffer, "Recommande :", COLOR_GREEN)
                    appendToBuf(top5Buffer, top5[0].provider, COLOR_GOLD)
                    appendToBuf(top5Buffer, "(${top5[0].type}) ${top5[0].latency} ms", COLOR_GOLD)
                }
            } else {
                appendToBuf(top5Buffer, "En attente...", COLOR_GREY)
            }

            tvTop5.text = top5Buffer
        }
    }

    private fun appendToBuf(buf: SpannableStringBuilder, text: String, color: Int) {
        val start = buf.length
        buf.append(text)
        buf.append("\n")
        buf.setSpan(
            ForegroundColorSpan(color),
            start,
            start + text.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    private fun appendProgress(text: String, color: Int) {
        runOnUiThread {
            appendToBuf(progressBuffer, text, color)
            tvProgress.text = progressBuffer
            // Scroll sans voler le focus du bouton
            scrollProgress.post {
                scrollProgress.scrollTo(0, tvProgress.bottom)
            }
        }
    }

    override fun onDestroy() {
        cancelled = true
        super.onDestroy()
    }
}
