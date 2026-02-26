package net.appstorefr.perfectdnsmanager

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import net.appstorefr.perfectdnsmanager.data.DnsProfile
import net.appstorefr.perfectdnsmanager.service.DnsVpnService
import net.appstorefr.perfectdnsmanager.util.LocaleHelper
import net.appstorefr.perfectdnsmanager.util.UrlBlockingTester
import org.json.JSONArray
import org.json.JSONObject

class DomainTesterActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    private data class TestDomainEntry(val domain: String, val enabled: Boolean)

    private lateinit var listContainer: LinearLayout
    private lateinit var tvResult: TextView
    private lateinit var btnRunTest: Button
    private var testThread: Thread? = null
    private var isTesting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)

        val root = ScrollView(this).apply {
            setBackgroundColor(0xFF1E1E1E.toInt())
        }
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        root.addView(mainLayout)

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val btnBack = Button(this).apply {
            text = getString(R.string.back_arrow)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundResource(R.drawable.focusable_item_background)
            foreground = resources.getDrawable(R.drawable.btn_focus_foreground, theme)
            isFocusable = true
            setPadding(20, 10, 20, 10)
            setOnClickListener { finish() }
        }
        header.addView(btnBack)
        val tvTitle = TextView(this).apply {
            text = getString(R.string.domain_tester_title)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(tvTitle)
        mainLayout.addView(header)

        // Spacer
        mainLayout.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 24)
        })

        // DNS status indicator
        val tvStatus = TextView(this).apply {
            textSize = 13f
            setPadding(16, 8, 16, 8)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 16
            }

            val vpnRunning = DnsVpnService.isVpnRunning
            val dotMode = android.provider.Settings.Global.getString(contentResolver, "private_dns_mode")
            val isDotActive = dotMode == "hostname"

            if (vpnRunning) {
                val providerLabel = try {
                    val profileJson = getSharedPreferences("prefs", MODE_PRIVATE).getString("selected_profile_json", null)
                    if (profileJson != null) {
                        val profile = Gson().fromJson(profileJson, DnsProfile::class.java)
                        profile.providerName
                    } else null
                } catch (_: Exception) { null }
                val label = if (providerLabel != null) "\uD83D\uDFE2 VPN actif ($providerLabel)" else "\uD83D\uDFE2 VPN actif"
                text = label
                setTextColor(0xFF4CAF50.toInt())
            } else if (isDotActive) {
                text = "\uD83D\uDFE2 DNS priv\u00E9 actif"
                setTextColor(0xFF4CAF50.toInt())
            } else {
                text = "\uD83D\uDD34 Aucun DNS actif (r\u00E9solution FAI)"
                setTextColor(0xFFF44336.toInt())
            }
        }
        mainLayout.addView(tvStatus)

        // Add domain button
        val btnAdd = Button(this).apply {
            text = getString(R.string.domain_tester_add)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF4CAF50.toInt())
            foreground = resources.getDrawable(R.drawable.btn_focus_foreground, theme)
            isFocusable = true
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 16
            }
            setOnClickListener { showAddDomainDialog() }
        }
        mainLayout.addView(btnAdd)

        // Domain list container
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        mainLayout.addView(listContainer)

        // Spacer
        mainLayout.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 24)
        })

        // Run test button
        btnRunTest = Button(this).apply {
            text = getString(R.string.domain_tester_run)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF0D47A1.toInt())
            foreground = resources.getDrawable(R.drawable.btn_focus_foreground, theme)
            isFocusable = true
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 16
            }
            setOnClickListener {
                if (isTesting) stopTest() else runTest()
            }
        }
        mainLayout.addView(btnRunTest)

        // Results area
        tvResult = TextView(this).apply {
            setTextColor(0xFFAAAAAA.toInt())
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setBackgroundColor(0xFF121212.toInt())
            setPadding(16, 16, 16, 16)
            text = getString(R.string.domain_tester_no_result)
        }
        mainLayout.addView(tvResult)

        setContentView(root)
        refreshList()
        btnBack.requestFocus()
    }

    private fun loadEntries(): MutableList<TestDomainEntry> {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val json = prefs.getString("test_domains_json", null)
        if (json != null) {
            try {
                val arr = JSONArray(json)
                val list = mutableListOf<TestDomainEntry>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(TestDomainEntry(obj.getString("domain"), obj.optBoolean("enabled", true)))
                }
                return list
            } catch (_: Exception) {}
        }
        return mutableListOf(TestDomainEntry("ygg.re", true))
    }

    private fun saveEntries(entries: List<TestDomainEntry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("domain", e.domain)
                put("enabled", e.enabled)
            })
        }
        getSharedPreferences("prefs", MODE_PRIVATE).edit()
            .putString("test_domains_json", arr.toString())
            .apply()
    }

    private fun refreshList() {
        listContainer.removeAllViews()
        val entries = loadEntries()
        entries.forEachIndexed { index, entry ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.focusable_item_background)
                isFocusable = true
                setPadding(16, 8, 16, 8)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = 8
                }
            }

            val tvDomain = TextView(this).apply {
                text = entry.domain
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(tvDomain)

            val sw = Switch(this).apply {
                isChecked = entry.enabled
                setOnCheckedChangeListener { _, isChecked ->
                    val list = loadEntries()
                    if (index < list.size) {
                        list[index] = list[index].copy(enabled = isChecked)
                        saveEntries(list)
                    }
                }
            }
            row.addView(sw)

            // Long-press → edit/delete popup
            row.setOnLongClickListener {
                AlertDialog.Builder(this@DomainTesterActivity)
                    .setTitle(entry.domain)
                    .setItems(arrayOf("Modifier", "Supprimer")) { _, which ->
                        when (which) {
                            0 -> showEditDomainDialog(index, entry)
                            1 -> {
                                AlertDialog.Builder(this@DomainTesterActivity)
                                    .setTitle(getString(R.string.domain_tester_delete_title))
                                    .setMessage(entry.domain)
                                    .setPositiveButton(getString(R.string.delete)) { _, _ ->
                                        val list = loadEntries()
                                        if (index < list.size) {
                                            list.removeAt(index)
                                            saveEntries(list)
                                            refreshList()
                                        }
                                    }
                                    .setNegativeButton(getString(R.string.cancel), null)
                                    .show()
                            }
                        }
                    }
                    .show()
                true
            }

            listContainer.addView(row)
        }
    }

    private fun showAddDomainDialog() {
        val et = EditText(this).apply {
            hint = "example.com"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF888888.toInt())
            setBackgroundColor(0xFF333333.toInt())
            setPadding(30, 20, 30, 20)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.domain_tester_add_title))
            .setView(et)
            .setPositiveButton(getString(R.string.add_button)) { _, _ ->
                val domain = et.text.toString().trim()
                if (domain.isNotEmpty()) {
                    val list = loadEntries()
                    list.add(TestDomainEntry(domain, true))
                    saveEntries(list)
                    refreshList()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showEditDomainDialog(index: Int, entry: TestDomainEntry) {
        val et = EditText(this).apply {
            setText(entry.domain)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF333333.toInt())
            setPadding(30, 20, 30, 20)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.domain_tester_edit_title))
            .setView(et)
            .setPositiveButton(getString(R.string.save_button)) { _, _ ->
                val domain = et.text.toString().trim()
                if (domain.isNotEmpty()) {
                    val list = loadEntries()
                    if (index < list.size) {
                        list[index] = list[index].copy(domain = domain)
                        saveEntries(list)
                        refreshList()
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun runTest() {
        isTesting = true
        btnRunTest.text = "\u23F9 Stop"
        btnRunTest.setBackgroundColor(0xFFB71C1C.toInt())
        tvResult.text = getString(R.string.domain_tester_testing)

        testThread = Thread {
            val entries = loadEntries().filter { it.enabled }
            val sb = StringBuilder()
            for (entry in entries) {
                if (Thread.currentThread().isInterrupted) break
                try {
                    val result = UrlBlockingTester.testBeforeAfter(this, entry.domain)
                    sb.appendLine("${entry.domain} :")
                    val ispIcon = if (result.ispDns.isBlocked) "\u274C" else "\u2705"
                    val vpnIcon = if (result.activeDns.isBlocked) "\u274C" else "\u2705"
                    sb.appendLine("  DNS FAI   : $ispIcon ${result.ispDns.ip ?: "No A record"}")
                    sb.appendLine("  DNS actif : $vpnIcon ${result.activeDns.ip ?: "No A record"}")
                    if (result.ispDns.isBlocked && !result.activeDns.isBlocked) {
                        sb.appendLine("  \u2192 Ce DNS d\u00E9bloque le contenu")
                    } else if (!result.ispDns.isBlocked && !result.activeDns.isBlocked) {
                        sb.appendLine("  \u2192 Non bloqu\u00E9 par le FAI")
                    } else if (result.activeDns.isBlocked) {
                        sb.appendLine("  \u2192 Bloqu\u00E9 m\u00EAme avec le DNS actif")
                    }
                    sb.appendLine()
                } catch (e: Exception) {
                    sb.appendLine("${entry.domain} : Erreur - ${e.message}")
                    sb.appendLine()
                }
                runOnUiThread { tvResult.text = sb.toString() }
            }
            runOnUiThread {
                isTesting = false
                btnRunTest.text = getString(R.string.domain_tester_run)
                btnRunTest.setBackgroundColor(0xFF0D47A1.toInt())
                if (sb.isEmpty()) tvResult.text = getString(R.string.domain_tester_no_enabled)
            }
        }
        testThread?.start()
    }

    private fun stopTest() {
        testThread?.interrupt()
        isTesting = false
        btnRunTest.text = getString(R.string.domain_tester_run)
        btnRunTest.setBackgroundColor(0xFF0D47A1.toInt())
    }
}
