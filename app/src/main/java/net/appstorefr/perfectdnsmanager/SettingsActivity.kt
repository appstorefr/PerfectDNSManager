package net.appstorefr.perfectdnsmanager

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import net.appstorefr.perfectdnsmanager.data.ProfileManager
import net.appstorefr.perfectdnsmanager.service.ShizukuManager
import net.appstorefr.perfectdnsmanager.util.LocaleHelper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import rikka.shizuku.Shizuku

class SettingsActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    private lateinit var prefs: SharedPreferences
    private var shizukuManager: ShizukuManager? = null
    private var layoutShizukuSection: LinearLayout? = null
    private var tvShizukuStatus: TextView? = null
    private var btnShizukuAction: Button? = null

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { updateShizukuUI() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener { updateShizukuUI() }
    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == ShizukuManager.SHIZUKU_PERMISSION_REQUEST_CODE) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, getString(R.string.shizuku_permission_granted), Toast.LENGTH_SHORT).show()
            }
            updateShizukuUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = getSharedPreferences("prefs", MODE_PRIVATE)

        val btnBack: Button = findViewById(R.id.btnBack)
        val btnAbout: Button = findViewById(R.id.btnAbout)
        val btnHowTo: Button = findViewById(R.id.btnHowTo)
        val btnSupport: Button = findViewById(R.id.btnSupport)
        val tvAdbStatus: TextView = findViewById(R.id.tvAdbStatus)
        val switchAutoStart: Switch = findViewById(R.id.switchAutoStart)
        val switchAutoReconnect: Switch = findViewById(R.id.switchAutoReconnect)
        val switchDisableIpv6: Switch = findViewById(R.id.switchDisableIpv6)

        // ── Toggle fonctions avancées ──
        val switchAdvanced: Switch = findViewById(R.id.switchAdvanced)
        val layoutAdvanced: LinearLayout = findViewById(R.id.layoutAdvancedSection)
        val btnRestoreDns: Button = findViewById(R.id.btnRestoreDns)
        val btnUrlRewrite: Button = findViewById(R.id.btnUrlRewrite)
        val switchOperatorDns: Switch = findViewById(R.id.switchOperatorDns)

        // ── Toggles simplification profils DNS ──
        val switchStandardDns: Switch = findViewById(R.id.switchStandardDns)
        val rowStandardDns: LinearLayout = findViewById(R.id.rowStandardDns)
        val switchProfileVariants: Switch = findViewById(R.id.switchProfileVariants)
        val rowProfileVariants: LinearLayout = findViewById(R.id.rowProfileVariants)

        // ── Toggle DNS DoT via ADB (dans la section avancée) ──
        val switchAdbDot: Switch = findViewById(R.id.switchAdbDot)
        val rowAdbDot: LinearLayout = findViewById(R.id.rowAdbDot)
        val layoutAdbDotSection: LinearLayout = findViewById(R.id.layoutAdbDotSection)

        // Statut ADB
        val adbEnabled = Settings.Global.getInt(contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        tvAdbStatus.text = if (adbEnabled) getString(R.string.adb_status_active) else getString(R.string.adb_status_inactive)
        tvAdbStatus.setTextColor(if (adbEnabled) getColor(android.R.color.holo_green_light) else getColor(android.R.color.holo_red_light))

        // Switches démarrage auto
        switchAutoStart.isChecked = prefs.getBoolean("auto_start_enabled", false)
        switchAutoReconnect.isChecked = prefs.getBoolean("auto_reconnect_dns", false)
        switchAutoReconnect.isEnabled = switchAutoStart.isChecked

        switchAutoStart.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_start_enabled", isChecked).apply()
            switchAutoReconnect.isEnabled = isChecked
            if (!isChecked) {
                switchAutoReconnect.isChecked = false
                prefs.edit().putBoolean("auto_reconnect_dns", false).apply()
            }
        }
        // Info DNS auto-reconnect
        val tvAutoReconnectDns: TextView = findViewById(R.id.tvAutoReconnectDns)
        fun updateAutoReconnectDnsInfo() {
            if (!switchAutoReconnect.isChecked) {
                tvAutoReconnectDns.visibility = View.GONE
                return
            }
            val defaultJson = prefs.getString("default_profile_json", null)
            val selectedJson = prefs.getString("selected_profile_json", null)
            val profileJson = defaultJson ?: selectedJson
            if (profileJson != null) {
                try {
                    val profile = com.google.gson.Gson().fromJson(profileJson, net.appstorefr.perfectdnsmanager.data.DnsProfile::class.java)
                    val prefix = if (defaultJson != null) getString(R.string.auto_reconnect_default_prefix) else ""
                    val typeLabel = when (profile.type) {
                        net.appstorefr.perfectdnsmanager.data.DnsType.DOH -> "DoH"
                        net.appstorefr.perfectdnsmanager.data.DnsType.DOT -> "DoT"
                        net.appstorefr.perfectdnsmanager.data.DnsType.DEFAULT -> "Standard"
                    }
                    val methodLabel = if (profile.type == net.appstorefr.perfectdnsmanager.data.DnsType.DOT) "ADB" else "VPN"
                    tvAutoReconnectDns.text = getString(R.string.auto_reconnect_dns_info, "$prefix${profile.providerName} - ${profile.name} ($typeLabel / $methodLabel)")
                    tvAutoReconnectDns.visibility = View.VISIBLE
                } catch (_: Exception) {
                    tvAutoReconnectDns.visibility = View.GONE
                }
            } else {
                tvAutoReconnectDns.text = getString(R.string.auto_reconnect_no_dns)
                tvAutoReconnectDns.visibility = View.VISIBLE
            }
        }
        updateAutoReconnectDnsInfo()

        switchAutoReconnect.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_reconnect_dns", isChecked).apply()
            updateAutoReconnectDnsInfo()
        }

        // IPv6
        switchDisableIpv6.isChecked = prefs.getBoolean("disable_ipv6", false)
        switchDisableIpv6.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("disable_ipv6", isChecked).apply()
            // Auto-reconnexion VPN si actif
            if (net.appstorefr.perfectdnsmanager.service.DnsVpnService.isVpnRunning) {
                val profileJson = prefs.getString("selected_profile_json", null)
                if (profileJson != null) {
                    try {
                        val profile = com.google.gson.Gson().fromJson(profileJson, net.appstorefr.perfectdnsmanager.data.DnsProfile::class.java)
                        if (profile.type != net.appstorefr.perfectdnsmanager.data.DnsType.DOT) {
                            val intent = Intent(this, net.appstorefr.perfectdnsmanager.service.DnsVpnService::class.java).apply {
                                action = net.appstorefr.perfectdnsmanager.service.DnsVpnService.ACTION_START
                                putExtra(net.appstorefr.perfectdnsmanager.service.DnsVpnService.EXTRA_DNS_PRIMARY, profile.primary)
                                profile.secondary?.let { putExtra(net.appstorefr.perfectdnsmanager.service.DnsVpnService.EXTRA_DNS_SECONDARY, it) }
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
                            else startService(intent)
                            Toast.makeText(this, getString(R.string.vpn_reconnecting_ipv6), Toast.LENGTH_SHORT).show()
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        // ── DNS DoT via ADB : toggle show/hide ADB sub-section ──
        // Vérification SDK : Private DNS (DoT) nécessite Android 9+ (API 28)
        val isAdbCompatible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        if (!isAdbCompatible) {
            switchAdbDot.isChecked = false
            switchAdbDot.isEnabled = false
            rowAdbDot.alpha = 0.4f
            rowAdbDot.isClickable = false
            prefs.edit().putBoolean("adb_dot_enabled", false).apply()
            layoutAdbDotSection.visibility = View.GONE
            // Afficher un avertissement sous le toggle
            val tvAdbCompat = TextView(this).apply {
                text = getString(R.string.adb_requires_android9)
                setTextColor(0xFFFF8A80.toInt())
                textSize = 12f
                setPadding(12, 4, 12, 8)
            }
            (rowAdbDot.parent as? LinearLayout)?.let { parent ->
                val idx = parent.indexOfChild(rowAdbDot) + 1
                parent.addView(tvAdbCompat, idx)
            }
        } else {
            val adbDotEnabled = prefs.getBoolean("adb_dot_enabled", false)
            switchAdbDot.isChecked = adbDotEnabled
            layoutAdbDotSection.visibility = if (adbDotEnabled) View.VISIBLE else View.GONE
        }

        rowAdbDot.setOnClickListener {
            if (isAdbCompatible) switchAdbDot.isChecked = !switchAdbDot.isChecked
        }

        switchAdbDot.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("adb_dot_enabled", isChecked).apply()
            layoutAdbDotSection.visibility = if (isChecked) View.VISIBLE else View.GONE

            if (isChecked) {
                val adbNow = Settings.Global.getInt(contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
                tvAdbStatus.text = if (adbNow) getString(R.string.adb_status_active) else getString(R.string.adb_status_inactive)
                tvAdbStatus.setTextColor(if (adbNow) getColor(android.R.color.holo_green_light) else getColor(android.R.color.holo_red_light))
            }
        }

        // ── Shizuku (Android 11+) ──
        layoutShizukuSection = findViewById(R.id.layoutShizukuSection)
        tvShizukuStatus = findViewById(R.id.tvShizukuStatus)
        btnShizukuAction = findViewById(R.id.btnShizukuAction)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            shizukuManager = ShizukuManager(this)
            layoutShizukuSection?.visibility = View.VISIBLE

            shizukuManager?.addBinderReceivedListener(binderReceivedListener)
            shizukuManager?.addBinderDeadListener(binderDeadListener)
            shizukuManager?.addPermissionResultListener(permissionResultListener)

            updateShizukuUI()

            btnShizukuAction?.setOnClickListener {
                val mgr = shizukuManager ?: return@setOnClickListener
                when {
                    !mgr.isShizukuInstalled() -> {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=moe.shizuku.privileged.api")))
                        } catch (_: Exception) {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api")))
                        }
                    }
                    !mgr.isShizukuRunning() -> {
                        val launchIntent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                        if (launchIntent != null) startActivity(launchIntent)
                        else Toast.makeText(this, getString(R.string.shizuku_cannot_open), Toast.LENGTH_SHORT).show()
                    }
                    !mgr.isShizukuPermissionGranted() -> {
                        mgr.requestPermission()
                    }
                    else -> {
                        Toast.makeText(this, getString(R.string.shizuku_ready), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // ── Fonctions avancées : toggle cache/montre la section ──
        val advancedEnabled = prefs.getBoolean("advanced_features_enabled", false)
        switchAdvanced.isChecked = advancedEnabled
        layoutAdvanced.visibility = if (advancedEnabled) View.VISIBLE else View.GONE

        switchAdvanced.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("advanced_features_enabled", isChecked).apply()
            layoutAdvanced.visibility = if (isChecked) View.VISIBLE else View.GONE
            // Cascade : si on désactive les fonctions avancées, tout ce qui est dedans se désactive
            if (!isChecked) {
                switchOperatorDns.isChecked = false
                prefs.edit().putBoolean("operator_dns_enabled", false).apply()

                switchStandardDns.isChecked = false
                prefs.edit().putBoolean("show_standard_dns", false).apply()

                switchProfileVariants.isChecked = false
                prefs.edit().putBoolean("show_profile_variants", false).apply()

                switchAdbDot.isChecked = false
                prefs.edit().putBoolean("adb_dot_enabled", false).apply()
                layoutAdbDotSection.visibility = View.GONE

                // Désactiver les rewrite rules
                val repo = net.appstorefr.perfectdnsmanager.data.DnsRewriteRepository(this)
                val rules = repo.getAllRules()
                rules.filter { it.isEnabled }.forEach { repo.updateRule(it.copy(isEnabled = false)) }
                reloadVpnRewriteRules()
            }
        }

        // Sous-toggle DNS opérateur (dans la section avancée)
        val rowOperatorDns = findViewById<LinearLayout>(R.id.rowOperatorDns)
        switchOperatorDns.isChecked = prefs.getBoolean("operator_dns_enabled", false)
        rowOperatorDns.setOnClickListener { switchOperatorDns.isChecked = !switchOperatorDns.isChecked }
        switchOperatorDns.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("operator_dns_enabled", isChecked).apply()
        }

        // ── Standard DNS toggle ──
        switchStandardDns.isChecked = prefs.getBoolean("show_standard_dns", false)
        rowStandardDns.setOnClickListener { switchStandardDns.isChecked = !switchStandardDns.isChecked }
        switchStandardDns.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("show_standard_dns", isChecked).apply()
        }

        // ── Profile variants toggle ──
        switchProfileVariants.isChecked = prefs.getBoolean("show_profile_variants", false)
        rowProfileVariants.setOnClickListener { switchProfileVariants.isChecked = !switchProfileVariants.isChecked }
        switchProfileVariants.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("show_profile_variants", isChecked).apply()
        }

        // URL Rewrite (fonction avancée)
        btnUrlRewrite.setOnClickListener { showUrlRewriteDialog() }

        // Import / Export configuration (collapsible)
        val switchImportExport: Switch = findViewById(R.id.switchImportExport)
        val layoutImportExportContent: LinearLayout = findViewById(R.id.layoutImportExportContent)
        val rowImportExport: LinearLayout = findViewById(R.id.rowImportExport)

        switchImportExport.setOnCheckedChangeListener { _, isChecked ->
            layoutImportExportContent.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        rowImportExport.setOnClickListener { switchImportExport.isChecked = !switchImportExport.isChecked }

        val btnExportConfig: Button = findViewById(R.id.btnExportConfig)
        val btnImportConfig: Button = findViewById(R.id.btnImportConfig)

        btnExportConfig.setOnClickListener { exportConfiguration() }
        btnImportConfig.setOnClickListener { showImportOptions() }

        // Restaurer les DNS par défaut (fonction avancée)
        btnRestoreDns.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.restore_dns_title))
                .setMessage(getString(R.string.restore_dns_message))
                .setPositiveButton(getString(R.string.restore_defaults)) { _, _ ->
                    ProfileManager(this).restoreDefaults()
                    Toast.makeText(this, getString(R.string.defaults_restored), Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton(getString(R.string.reset_all)) { _, _ ->
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.confirm_title))
                        .setMessage(getString(R.string.reset_all_confirm))
                        .setPositiveButton(getString(R.string.yes_reset_all)) { _, _ ->
                            ProfileManager(this).resetAll()
                            getSharedPreferences("nextdns_profiles", MODE_PRIVATE).edit().clear().apply()
                            Toast.makeText(this, getString(R.string.all_reset), Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton(getString(R.string.cancel), null)
                        .show()
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }

        btnBack.requestFocus()
        btnBack.setOnClickListener { finish() }
        btnAbout.setOnClickListener { startActivity(Intent(this, AboutActivity::class.java)) }
        btnHowTo.setOnClickListener { startActivity(Intent(this, HowToActivity::class.java)) }
        btnSupport.setOnClickListener { startActivity(Intent(this, SupportActivity::class.java)) }
    }

    private fun showUrlRewriteDialog() {
        val repo = net.appstorefr.perfectdnsmanager.data.DnsRewriteRepository(this)
        val rules = repo.getAllRules()

        val items = mutableListOf<String>()
        items.add(getString(R.string.add_new_rule))
        rules.forEach { r ->
            val status = if (r.isEnabled) "✅" else "❌"
            items.add("$status ${r.fromDomain} → ${r.toDomain}")
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.url_rewrite_title))
            .setItems(items.toTypedArray()) { _, which ->
                if (which == 0) {
                    showAddRewriteRuleDialog(repo)
                } else {
                    val rule = rules[which - 1]
                    showEditRewriteRuleDialog(repo, rule)
                }
            }
            .setNegativeButton(getString(R.string.close), null)
            .show()
    }

    private fun showAddRewriteRuleDialog(repo: net.appstorefr.perfectdnsmanager.data.DnsRewriteRepository) {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 40, 60, 20)
        }

        val tvExplain = TextView(this).apply {
            text = getString(R.string.rewrite_explain)
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 12f
        }
        layout.addView(tvExplain)

        val lbl = { text: String ->
            TextView(this).apply {
                this.text = text
                setTextColor(0xFFCCCCCC.toInt())
                setPadding(0, 16, 0, 4)
                textSize = 13f
            }
        }

        val etDomain = android.widget.EditText(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF888888.toInt())
            hint = getString(R.string.source_domain_hint)
            isSingleLine = true
            setBackgroundColor(0xFF333333.toInt())
            setPadding(20, 15, 20, 15)
        }
        val etTarget = android.widget.EditText(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF888888.toInt())
            hint = getString(R.string.dest_domain_hint)
            isSingleLine = true
            setBackgroundColor(0xFF333333.toInt())
            setPadding(20, 15, 20, 15)
        }

        layout.addView(lbl(getString(R.string.source_domain)))
        layout.addView(etDomain)
        layout.addView(lbl(getString(R.string.dest_domain)))
        layout.addView(etTarget)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.new_rewrite_rule_title))
            .setView(layout)
            .setPositiveButton(getString(R.string.add_button)) { _, _ ->
                val domain = etDomain.text.toString().trim()
                val target = etTarget.text.toString().trim()
                if (domain.isNotEmpty() && target.isNotEmpty()) {
                    val rule = net.appstorefr.perfectdnsmanager.data.DnsRewriteRule(
                        id = System.currentTimeMillis(),
                        fromDomain = domain,
                        toDomain = target,
                        isEnabled = true
                    )
                    repo.addRule(rule)
                    reloadVpnRewriteRules()
                    Toast.makeText(this, getString(R.string.rewrite_added, domain, target), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showEditRewriteRuleDialog(repo: net.appstorefr.perfectdnsmanager.data.DnsRewriteRepository, rule: net.appstorefr.perfectdnsmanager.data.DnsRewriteRule) {
        val statusText = if (rule.isEnabled) getString(R.string.enabled) else getString(R.string.disabled)
        AlertDialog.Builder(this)
            .setTitle("${rule.fromDomain} → ${rule.toDomain}")
            .setMessage(getString(R.string.status_label, statusText))
            .setPositiveButton(if (rule.isEnabled) getString(R.string.disable_button) else getString(R.string.enable_button)) { _, _ ->
                repo.updateRule(rule.copy(isEnabled = !rule.isEnabled))
                reloadVpnRewriteRules()
                val newStatus = if (!rule.isEnabled) getString(R.string.rule_enabled) else getString(R.string.rule_disabled)
                Toast.makeText(this, newStatus, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(getString(R.string.delete)) { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.confirm_title))
                    .setMessage(getString(R.string.delete_rule_confirm, rule.fromDomain, rule.toDomain))
                    .setPositiveButton(getString(R.string.delete)) { _, _ ->
                        repo.deleteRule(rule)
                        reloadVpnRewriteRules()
                        Toast.makeText(this, getString(R.string.rule_deleted), Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
            .setNegativeButton(getString(R.string.close), null)
            .show()
    }

    private fun reloadVpnRewriteRules() {
        try {
            val intent = android.content.Intent(this, net.appstorefr.perfectdnsmanager.service.DnsVpnService::class.java)
            intent.action = net.appstorefr.perfectdnsmanager.service.DnsVpnService.ACTION_RELOAD_RULES
            startService(intent)
        } catch (_: Exception) { }
    }

    private fun exportConfiguration() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 40, 60, 20)
        }

        val cbProfiles = android.widget.CheckBox(this).apply {
            text = getString(R.string.export_toggle_profiles)
            isChecked = true; isEnabled = false
            setTextColor(0xFFCCCCCC.toInt())
        }
        val cbFavorites = android.widget.CheckBox(this).apply {
            text = getString(R.string.export_toggle_favorites); isChecked = true
            setTextColor(0xFFFFFFFF.toInt())
        }
        val cbDefault = android.widget.CheckBox(this).apply {
            text = getString(R.string.export_toggle_default_dns); isChecked = true
            setTextColor(0xFFFFFFFF.toInt())
        }
        val cbNextDns = android.widget.CheckBox(this).apply {
            text = getString(R.string.export_toggle_nextdns); isChecked = true
            setTextColor(0xFFFFFFFF.toInt())
        }
        val cbRewrite = android.widget.CheckBox(this).apply {
            text = getString(R.string.export_toggle_rewrite); isChecked = true
            setTextColor(0xFFFFFFFF.toInt())
        }
        val cbSettings = android.widget.CheckBox(this).apply {
            text = getString(R.string.export_toggle_settings); isChecked = true
            setTextColor(0xFFFFFFFF.toInt())
        }

        layout.addView(cbProfiles)
        layout.addView(cbFavorites)
        layout.addView(cbDefault)
        layout.addView(cbNextDns)
        layout.addView(cbRewrite)
        layout.addView(cbSettings)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.export_select_title))
            .setView(layout)
            .setPositiveButton(getString(R.string.export_button)) { _, _ ->
                performExport(
                    includeProfiles = true,
                    includeFavorites = cbFavorites.isChecked,
                    includeDefaultProfile = cbDefault.isChecked,
                    includeNextDnsProfiles = cbNextDns.isChecked,
                    includeRewriteRules = cbRewrite.isChecked,
                    includeSettings = cbSettings.isChecked
                )
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun performExport(
        includeProfiles: Boolean,
        includeFavorites: Boolean,
        includeDefaultProfile: Boolean,
        includeNextDnsProfiles: Boolean,
        includeRewriteRules: Boolean,
        includeSettings: Boolean
    ) {
        try {
            val configManager = net.appstorefr.perfectdnsmanager.data.ConfigManager(this)
            val json = configManager.exportConfigSelective(
                includeProfiles, includeFavorites, includeDefaultProfile,
                includeNextDnsProfiles, includeRewriteRules, includeSettings
            )

            val dir = getExternalFilesDir(null) ?: filesDir
            val file = java.io.File(dir, "PerfectDNS-config.json")
            file.writeText(json)

            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("PerfectDNS Config", json))

            AlertDialog.Builder(this)
                .setTitle(getString(R.string.config_exported_title))
                .setMessage(getString(R.string.export_choose_dest, file.absolutePath))
                .setPositiveButton(getString(R.string.export_save_local)) { _, _ ->
                    Toast.makeText(this, getString(R.string.export_saved_locally, file.absolutePath), Toast.LENGTH_LONG).show()
                }
                .setNeutralButton(getString(R.string.upload_to_tmpfiles)) { _, _ ->
                    uploadToTmpfiles(file)
                }
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.export_error, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private fun uploadToTmpfiles(file: java.io.File) {
        Toast.makeText(this, getString(R.string.uploading), Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val body = okhttp3.MultipartBody.Builder()
                    .setType(okhttp3.MultipartBody.FORM)
                    .addFormDataPart("file", file.name,
                        file.asRequestBody("application/json".toMediaType()))
                    .build()
                val request = okhttp3.Request.Builder()
                    .url("https://tmpfiles.org/api/v1/upload")
                    .post(body)
                    .build()
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                response.close()
                // Response: {"status":"success","data":{"url":"https://tmpfiles.org/12345/file.json"}}
                // Need to insert /dl/ for direct download: https://tmpfiles.org/dl/12345/file.json
                val jsonObj = org.json.JSONObject(responseBody)
                if (jsonObj.optString("status") == "success") {
                    val pageUrl = jsonObj.getJSONObject("data").getString("url")
                    val directUrl = pageUrl.replace("tmpfiles.org/", "tmpfiles.org/dl/")
                    runOnUiThread { showUploadSuccess(directUrl) }
                } else {
                    runOnUiThread { Toast.makeText(this, getString(R.string.upload_error, responseBody), Toast.LENGTH_LONG).show() }
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, getString(R.string.upload_error, e.message ?: ""), Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun showUploadSuccess(url: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Config URL", url))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.upload_success_title))
            .setMessage(getString(R.string.upload_success_message, url))
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showImportOptions() {
        val options = arrayOf(
            getString(R.string.import_from_clipboard),
            getString(R.string.import_from_url),
            getString(R.string.import_from_file),
            getString(R.string.import_from_tmpfiles)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.import_config_button))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> importFromClipboard()
                    1 -> showImportFromUrlDialog()
                    2 -> importFromLocalFile()
                    3 -> showImportFromTmpfilesDialog()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun importFromClipboard() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        if (text.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.clipboard_empty), Toast.LENGTH_SHORT).show()
            return
        }
        confirmAndImport(text)
    }

    private fun showImportFromUrlDialog() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 40, 60, 20)
        }
        val tvExplain = TextView(this).apply {
            text = getString(R.string.enter_config_url)
            setTextColor(0xFFCCCCCC.toInt()); textSize = 13f
        }
        layout.addView(tvExplain)
        val editUrl = android.widget.EditText(this).apply {
            hint = "https://example.com/config.json"
            setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFF888888.toInt())
            setBackgroundColor(0xFF333333.toInt()); setPadding(20, 15, 20, 15)
            isSingleLine = true
        }
        layout.addView(editUrl)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.import_from_url_title))
            .setView(layout)
            .setPositiveButton(getString(R.string.download_button)) { _, _ ->
                val url = editUrl.text.toString().trim()
                if (url.isNotEmpty()) {
                    Toast.makeText(this, getString(R.string.downloading), Toast.LENGTH_SHORT).show()
                    Thread {
                        try {
                            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                            conn.connectTimeout = 10000
                            conn.readTimeout = 10000
                            val json = conn.inputStream.bufferedReader().readText()
                            conn.disconnect()
                            runOnUiThread { confirmAndImport(json) }
                        } catch (e: Exception) {
                            runOnUiThread {
                                Toast.makeText(this, getString(R.string.read_error, e.message ?: ""), Toast.LENGTH_LONG).show()
                            }
                        }
                    }.start()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun importFromLocalFile() {
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            val file = java.io.File(dir, "PerfectDNS-config.json")
            if (!file.exists()) {
                Toast.makeText(this, getString(R.string.no_file_found, file.absolutePath), Toast.LENGTH_LONG).show()
                return
            }
            val json = file.readText()
            confirmAndImport(json)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.read_error, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private fun showImportFromTmpfilesDialog() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 40, 60, 20)
        }
        val tvExplain = TextView(this).apply {
            text = getString(R.string.enter_tmpfiles_number)
            setTextColor(0xFFCCCCCC.toInt()); textSize = 13f
        }
        layout.addView(tvExplain)
        val editNumber = android.widget.EditText(this).apply {
            hint = getString(R.string.tmpfiles_number_hint)
            setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFF888888.toInt())
            setBackgroundColor(0xFF333333.toInt()); setPadding(20, 15, 20, 15)
            isSingleLine = true; inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        layout.addView(editNumber)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.import_from_tmpfiles_title))
            .setView(layout)
            .setPositiveButton(getString(R.string.download_button)) { _, _ ->
                val number = editNumber.text.toString().trim()
                if (number.isNotEmpty()) {
                    Toast.makeText(this, getString(R.string.downloading), Toast.LENGTH_SHORT).show()
                    Thread {
                        try {
                            val client = okhttp3.OkHttpClient.Builder()
                                .followRedirects(true)
                                .followSslRedirects(true)
                                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                                .build()
                            val request = okhttp3.Request.Builder()
                                .url("https://tmpfiles.org/dl/$number/PerfectDNS-config.json")
                                .build()
                            val response = client.newCall(request).execute()
                            val json = response.body?.string() ?: ""
                            response.close()
                            if (json.isNotBlank()) {
                                runOnUiThread { confirmAndImport(json) }
                            } else {
                                runOnUiThread {
                                    Toast.makeText(this, getString(R.string.read_error, "Empty response"), Toast.LENGTH_LONG).show()
                                }
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                Toast.makeText(this, getString(R.string.read_error, e.message ?: ""), Toast.LENGTH_LONG).show()
                            }
                        }
                    }.start()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun confirmAndImport(json: String) {
        try {
            val configManager = net.appstorefr.perfectdnsmanager.data.ConfigManager(this)
            val summary = configManager.getConfigSummary(json)

            AlertDialog.Builder(this)
                .setTitle(getString(R.string.confirm_import_title))
                .setMessage(getString(R.string.confirm_import_message, summary))
                .setPositiveButton(getString(R.string.import_with_settings)) { _, _ ->
                    val result = configManager.importConfig(json, importSettings = true)
                    showImportResult(result)
                }
                .setNeutralButton(getString(R.string.import_without_settings)) { _, _ ->
                    val result = configManager.importConfig(json, importSettings = false)
                    showImportResult(result)
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.invalid_json, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private fun updateShizukuUI() {
        val mgr = shizukuManager ?: return
        val tv = tvShizukuStatus ?: return
        val btn = btnShizukuAction ?: return

        runOnUiThread {
            when (mgr.getStatusString()) {
                "not_installed" -> {
                    tv.text = getString(R.string.shizuku_not_installed)
                    tv.setTextColor(getColor(android.R.color.holo_red_light))
                    btn.text = getString(R.string.shizuku_install)
                }
                "not_running" -> {
                    tv.text = getString(R.string.shizuku_not_running)
                    tv.setTextColor(0xFFFF9800.toInt())
                    btn.text = getString(R.string.shizuku_open)
                }
                "no_permission" -> {
                    tv.text = getString(R.string.shizuku_no_permission)
                    tv.setTextColor(0xFFFF9800.toInt())
                    btn.text = getString(R.string.shizuku_grant_permission)
                }
                "ready" -> {
                    tv.text = getString(R.string.shizuku_ready)
                    tv.setTextColor(getColor(android.R.color.holo_green_light))
                    btn.text = getString(R.string.shizuku_ready)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateShizukuUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        shizukuManager?.removeBinderReceivedListener(binderReceivedListener)
        shizukuManager?.removeBinderDeadListener(binderDeadListener)
        shizukuManager?.removePermissionResultListener(permissionResultListener)
    }

    private fun showImportResult(result: net.appstorefr.perfectdnsmanager.data.ConfigManager.ImportResult) {
        val msg = StringBuilder()
        msg.appendLine(getString(R.string.import_done))
        msg.appendLine()
        msg.appendLine(getString(R.string.import_profiles_count, result.profileCount))
        msg.appendLine(getString(R.string.import_favorites_count, result.favoriteCount))
        msg.appendLine(getString(R.string.import_rewrite_count, result.rewriteRuleCount))
        msg.appendLine(getString(R.string.import_nextdns_count, result.nextDnsProfileCount))
        if (result.hasDefaultProfile) msg.appendLine(getString(R.string.import_default_restored))
        if (result.settingsRestored) msg.appendLine(getString(R.string.import_settings_restored))

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.import_done_title))
            .setMessage(msg.toString())
            .setPositiveButton("OK", null)
            .show()
    }
}
