package net.appstorefr.perfectdnsmanager

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.VpnService
import android.os.Bundle
import android.graphics.Color
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import net.appstorefr.perfectdnsmanager.data.DnsProfile
import net.appstorefr.perfectdnsmanager.data.DnsType
import net.appstorefr.perfectdnsmanager.service.AdbDnsManager
import net.appstorefr.perfectdnsmanager.service.DnsVpnService
import net.appstorefr.perfectdnsmanager.service.UpdateManager
import net.appstorefr.perfectdnsmanager.util.LocaleHelper
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.net.InetAddress

class MainActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    private lateinit var btnToggle: Button
    private lateinit var btnLanguage: Button
    private lateinit var layoutSelectDns: LinearLayout
    private lateinit var ivSelectedDnsIcon: ImageView
    private lateinit var tvSelectDns: TextView
    private lateinit var btnSettings: ImageButton
    private lateinit var btnTestDns: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvWanIp: TextView
    private lateinit var tvWanIpv6: TextView
    private lateinit var adbManager: AdbDnsManager
    private lateinit var prefs: SharedPreferences

    private var selectedProfile: DnsProfile? = null
    private var pendingVpnProfile: DnsProfile? = null
    private var isActive = false
    private var isActivating = false

    /** Détermine la méthode d'application depuis le type de profil. DoT → ADB, sinon → VPN */
    private fun methodForProfile(profile: DnsProfile?): String {
        return if (profile?.type == DnsType.DOT) "ADB" else "VPN"
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        val profile = pendingVpnProfile ?: selectedProfile
        if (profile != null && VpnService.prepare(this) == null) {
            startVpnService(profile)
        } else if (profile != null) {
            isActivating = false
            btnToggle.isEnabled = true
            btnToggle.requestFocus()
            Toast.makeText(this, getString(R.string.vpn_permission_denied), Toast.LENGTH_SHORT).show()
        } else {
            isActivating = false
            btnToggle.isEnabled = true
            btnToggle.requestFocus()
        }
        pendingVpnProfile = null
    }

    private val dnsSelectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val profileJson = result.data?.getStringExtra("SELECTED_PROFILE_JSON")
            if (profileJson != null) {
                val newProfile = Gson().fromJson(profileJson, DnsProfile::class.java)
                val wasActive = isActive
                selectedProfile = newProfile
                prefs.edit().putString("selected_profile_json", profileJson).apply()
                updateSelectButtonText()
                // Auto-reconnexion : si DNS était actif, reconnecter avec le nouveau profil
                if (wasActive) {
                    val adbWasActive = adbManager.getCurrentPrivateDnsMode()?.contains("hostname") == true
                    if (adbWasActive) {
                        // ADB → désactiver d'abord, puis appliquer le nouveau profil
                        Thread {
                            adbManager.disablePrivateDns()
                            runOnUiThread { setInactiveStatus(); applyDns() }
                        }.start()
                    } else {
                        // VPN actif → ACTION_RESTART : stop visible + restart après délai
                        // (pas ACTION_STOP+stopSelf qui détruirait le service)
                        val svcIntent = Intent(this@MainActivity, DnsVpnService::class.java).apply {
                            action = DnsVpnService.ACTION_RESTART
                            putExtra(DnsVpnService.EXTRA_DNS_PRIMARY, newProfile.primary)
                            newProfile.secondary?.let { putExtra(DnsVpnService.EXTRA_DNS_SECONDARY, it) }
                        }
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                            startForegroundService(svcIntent) else startService(svcIntent)
                        val label = "VPN: ${newProfile.providerName}\n${newProfile.primary}"
                        prefs.edit()
                            .putBoolean("vpn_active", true)
                            .putString("vpn_label", label)
                            .putString("last_method", "VPN")
                            .apply()
                        setActiveStatus(true, label)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        adbManager = AdbDnsManager(this)

        // Premier lancement : si pas de langue choisie, définir la langue du système
        if (prefs.getString("language", null) == null) {
            val sysLang = java.util.Locale.getDefault().language
            prefs.edit().putString("language", if (sysLang == "fr") "fr" else "en").apply()
        }

        // Migration de version : rafraîchir les presets DNS si la version a changé
        checkVersionMigration()

        initViews()
        applyGoldenIndicators()
        restoreState()
        setupUI()

        // Vérification auto des mises à jour au lancement
        checkForAppUpdate()

        // Récupérer l'IP WAN
        fetchWanIp()
    }

    private fun checkForAppUpdate() {
        val currentVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (_: Exception) { "1.0" }
        UpdateManager(this).checkOnLaunch(currentVersion)
    }

    override fun onResume() {
        super.onResume()
        if (!isActivating) {
            // Gestion AUTO_RECONNECT (notification boot)
            // Priorité : DNS par défaut > dernier DNS sélectionné
            if (intent?.getBooleanExtra("AUTO_RECONNECT", false) == true) {
                intent?.removeExtra("AUTO_RECONNECT")
                val defaultJson = prefs.getString("default_profile_json", null)
                val selectedJson = prefs.getString("selected_profile_json", null)
                val profileJson = defaultJson ?: selectedJson
                if (profileJson != null && !DnsVpnService.isVpnRunning) {
                    try {
                        val profile = Gson().fromJson(profileJson, DnsProfile::class.java)
                        selectedProfile = profile
                        prefs.edit().putString("selected_profile_json", profileJson).apply()
                        updateSelectButtonText()
                        if (methodForProfile(profile) == "VPN") applyDnsViaVpn(profile)
                    } catch (_: Exception) {}
                }
            }
            checkStatus()
            fetchWanIp()
        }
    }

    private fun applyGoldenIndicators() {
        val gold = Color.parseColor("#FFD700")
        val tvProvider: TextView = findViewById(R.id.tvDnsProviderLabel)
        val tvActivation: TextView = findViewById(R.id.tvActivationLabel)
        for (tv in listOf(tvProvider, tvActivation)) {
            val text = tv.text.toString()
            val span = SpannableString(text)
            span.setSpan(ForegroundColorSpan(gold), 0, 2, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
            tv.text = span
        }
    }

    private fun initViews() {
        btnToggle = findViewById(R.id.btnToggle)
        btnLanguage = findViewById(R.id.btnLanguage)
        layoutSelectDns = findViewById(R.id.layoutSelectDns)
        ivSelectedDnsIcon = findViewById(R.id.ivSelectedDnsIcon)
        tvSelectDns = findViewById(R.id.tvSelectDns)
        btnSettings = findViewById(R.id.btnSettings)
        btnTestDns = findViewById(R.id.btnTestDns)
        tvStatus = findViewById(R.id.tvStatus)
        tvWanIp = findViewById(R.id.tvWanIp)
        tvWanIpv6 = findViewById(R.id.tvWanIpv6)
        findViewById<Button>(R.id.btnShareIp).setOnClickListener { shareIpsViaTmpfiles() }
    }

    private fun fetchWanIp() {
        // IPv4
        Thread {
            val ip = try {
                java.net.URL("https://api4.ipify.org").readText().trim()
            } catch (_: Exception) {
                try {
                    java.net.URL("https://ipv4.icanhazip.com").readText().trim()
                } catch (_: Exception) { null }
            }
            runOnUiThread {
                tvWanIp.text = ip ?: getString(R.string.wan_ip_error)
            }
        }.start()
        // IPv6
        Thread {
            val ipv6 = try {
                java.net.URL("https://api6.ipify.org").readText().trim()
            } catch (_: Exception) {
                try {
                    java.net.URL("https://ipv6.icanhazip.com").readText().trim()
                } catch (_: Exception) { null }
            }
            runOnUiThread {
                if (ipv6 != null && ipv6.contains(":")) {
                    tvWanIpv6.text = ipv6
                    tvWanIpv6.setTextColor(Color.parseColor("#FF5252"))
                } else {
                    tvWanIpv6.text = getString(R.string.wan_ipv6_blocked)
                    tvWanIpv6.setTextColor(Color.parseColor("#4CAF50"))
                }
            }
        }.start()
    }

    private fun shareIpsViaTmpfiles() {
        val ipv4 = tvWanIp.text.toString()
        val ipv6 = tvWanIpv6.text.toString()
        if (ipv4 == getString(R.string.wan_ip_loading)) {
            Toast.makeText(this, getString(R.string.wan_ip_loading), Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, getString(R.string.share_ip_uploading), Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val content = buildString {
                    appendLine("=== Perfect DNS Manager — IP Report ===")
                    appendLine()
                    appendLine("IPv4 : $ipv4")
                    appendLine("IPv6 : $ipv6")
                    appendLine()
                    appendLine("DNS actif : ${tvStatus.text}")
                    appendLine("Profil : ${selectedProfile?.let { "${it.providerName} - ${it.name}" } ?: "aucun"}")
                    appendLine()
                    appendLine("Date : ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
                    appendLine()
                    appendLine("---")
                    appendLine("Consulter ce rapport en ligne : https://appstorefr.github.io/PerfectDNSManager/ip.html")
                }
                val file = java.io.File(cacheDir, "ip-report.txt")
                file.writeText(content)
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val body = okhttp3.MultipartBody.Builder()
                    .setType(okhttp3.MultipartBody.FORM)
                    .addFormDataPart("file", file.name,
                        file.asRequestBody("text/plain".toMediaType()))
                    .build()
                val request = okhttp3.Request.Builder()
                    .url("https://tmpfiles.org/api/v1/upload")
                    .post(body)
                    .build()
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                response.close()
                val jsonObj = org.json.JSONObject(responseBody)
                if (jsonObj.optString("status") == "success") {
                    val pageUrl = jsonObj.getJSONObject("data").getString("url")
                    val directUrl = pageUrl.replace("tmpfiles.org/", "tmpfiles.org/dl/")
                    val numberRegex = Regex("tmpfiles\\.org/dl/(\\d+)/")
                    val number = numberRegex.find(directUrl)?.groupValues?.get(1) ?: ""
                    runOnUiThread {
                        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("IP Number", number))
                        AlertDialog.Builder(this)
                            .setTitle(getString(R.string.share_ip_success_title))
                            .setMessage(getString(R.string.share_ip_success_message, number, directUrl))
                            .setPositiveButton("OK", null)
                            .show()
                    }
                } else {
                    runOnUiThread { Toast.makeText(this, getString(R.string.share_ip_error), Toast.LENGTH_LONG).show() }
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, getString(R.string.share_ip_error) + ": ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun restoreState() {
        val vpnReallyActive = DnsVpnService.isVpnRunning
        val vpnSavedActive = prefs.getBoolean("vpn_active", false)
        val adbIsActive = adbManager.getCurrentPrivateDnsMode()?.contains("hostname") == true

        if ((vpnReallyActive && vpnSavedActive) || adbIsActive) {
            val profileJson = prefs.getString("selected_profile_json", null)
            if (profileJson != null) {
                try { selectedProfile = Gson().fromJson(profileJson, DnsProfile::class.java) }
                catch (_: Exception) {}
            }
        } else if (vpnSavedActive && !vpnReallyActive) {
            // VPN était actif mais le service a été tué (mise à jour, kill…)
            // → auto-reconnexion
            val selectedJson = prefs.getString("selected_profile_json", null)
            if (selectedJson != null) {
                try {
                    val profile = Gson().fromJson(selectedJson, DnsProfile::class.java)
                    selectedProfile = profile
                    updateSelectButtonText()
                    if (methodForProfile(profile) == "VPN") {
                        applyDnsViaVpn(profile)
                    }
                    return
                } catch (_: Exception) {
                    selectedProfile = null
                }
            }
            prefs.edit().putBoolean("vpn_active", false).apply()
        } else {
            // Pré-sélectionner le DNS par défaut, sinon le dernier sélectionné
            val defaultJson = prefs.getString("default_profile_json", null)
            val selectedJson = prefs.getString("selected_profile_json", null)
            val profileJson = defaultJson ?: selectedJson
            if (profileJson != null) {
                try {
                    selectedProfile = Gson().fromJson(profileJson, DnsProfile::class.java)
                    prefs.edit().putString("selected_profile_json", profileJson).apply()
                } catch (_: Exception) {
                    selectedProfile = null
                }
            } else {
                selectedProfile = null
            }
        }
        updateSelectButtonText()
    }

    private fun checkStatus() {
        val adbMode = adbManager.getCurrentPrivateDnsMode()
        if (adbMode?.contains("hostname") == true) {
            val host = adbManager.getCurrentPrivateDnsHost()
            if (host.isNotEmpty()) {
                val savedMethod = prefs.getString("last_method", "ADB") ?: "ADB"
                val displayMethod = if (savedMethod == "Shizuku" || savedMethod == "Settings") savedMethod else "ADB"
                prefs.edit().putString("last_method", displayMethod).apply()
                setActiveStatus(true, "$displayMethod: $host")
                return
            }
        }

        val vpnReallyActive = DnsVpnService.isVpnRunning
        val vpnSavedActive = prefs.getBoolean("vpn_active", false)

        if (vpnReallyActive && vpnSavedActive) {
            val label = prefs.getString("vpn_label", "") ?: ""
            setActiveStatus(true, label)
        } else {
            if (!vpnReallyActive && vpnSavedActive) {
                prefs.edit().putBoolean("vpn_active", false).putString("vpn_label", "").apply()
            }
            setInactiveStatus()
        }
    }

    private fun updateSelectButtonText() {
        if (selectedProfile != null) {
            val typeLabel = when (selectedProfile!!.type) {
                DnsType.DOH -> "DoH"; DnsType.DOT -> "DoT"; DnsType.DEFAULT -> "Standard"
            }
            val methodLabel = if (selectedProfile!!.type == DnsType.DOT) "ADB" else "VPN"
            tvSelectDns.text = "${selectedProfile!!.providerName} - ${selectedProfile!!.name} ($typeLabel / $methodLabel)"
            ivSelectedDnsIcon.setImageResource(DnsProfile.getProviderIcon(selectedProfile!!.providerName))
            ivSelectedDnsIcon.visibility = View.VISIBLE
        } else {
            tvSelectDns.text = getString(R.string.dns_select_button)
            ivSelectedDnsIcon.visibility = View.GONE
        }
    }

    private fun clearSelectedProfile() {
        selectedProfile = null
        prefs.edit().remove("selected_profile_json").apply()
        updateSelectButtonText()
    }

    private fun setupUI() {
        layoutSelectDns.requestFocus()

        btnToggle.setOnClickListener {
            if (isActivating) return@setOnClickListener
            if (isActive) disableDns() else applyDns()
        }

        layoutSelectDns.setOnClickListener {
            dnsSelectionLauncher.launch(Intent(this, DnsSelectionActivity::class.java))
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        tvStatus.setOnClickListener { showDnsReport() }
        btnTestDns.setOnClickListener { runDnsTest() }

        // Bouton langue
        updateLanguageButton()
        btnLanguage.setOnClickListener { showLanguageDialog() }
    }

    private fun updateLanguageButton() {
        val lang = prefs.getString("language", "fr") ?: "fr"
        btnLanguage.text = if (lang == "fr") "\uD83C\uDDEB\uD83C\uDDF7 FR" else "\uD83C\uDDEC\uD83C\uDDE7 EN"
    }

    private fun showLanguageDialog() {
        val languages = arrayOf("\uD83C\uDDEB\uD83C\uDDF7 Fran\u00e7ais", "\uD83C\uDDEC\uD83C\uDDE7 English")
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.choose_language_title))
            .setItems(languages) { _, which ->
                val langCode = if (which == 0) "fr" else "en"
                prefs.edit().putString("language", langCode).apply()
                recreate()
            }
            .show()
    }

    private fun checkVersionMigration() {
        try {
            val info = packageManager.getPackageInfo(packageName, 0)
            val currentVersionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION") info.versionCode
            }
            val savedVersionCode = prefs.getInt("last_version_code", 0)
            if (savedVersionCode != 0 && savedVersionCode != currentVersionCode) {
                // Version changed → refresh presets (keeps custom profiles)
                val profileManager = net.appstorefr.perfectdnsmanager.data.ProfileManager(this)
                profileManager.restoreDefaults()
                Toast.makeText(this, getString(R.string.presets_updated_toast, info.versionName ?: ""), Toast.LENGTH_SHORT).show()
            }
            prefs.edit().putInt("last_version_code", currentVersionCode).apply()
        } catch (_: Exception) {}
    }

    private fun applyDns() {
        val profile = selectedProfile ?: run {
            Toast.makeText(this, getString(R.string.select_a_profile), Toast.LENGTH_SHORT).show()
            return
        }
        if (methodForProfile(profile) == "ADB") applyDnsViaAdb(profile) else applyDnsViaVpn(profile)
    }

    private fun applyDnsViaAdb(profile: DnsProfile) {
        if (profile.type != DnsType.DOT) {
            Toast.makeText(this, getString(R.string.private_dns_supports_only_dot), Toast.LENGTH_LONG).show()
            return
        }
        if (android.os.Build.MODEL.contains("AFT")) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.firestick_warning_title))
                .setMessage(getString(R.string.firestick_warning_message))
                .setPositiveButton(getString(R.string.continue_anyway)) { _, _ -> proceedWithAdb(profile) }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        } else {
            proceedWithAdb(profile)
        }
    }

    private fun proceedWithAdb(profile: DnsProfile) {
        Thread {
            val success = adbManager.enablePrivateDns(profile.primary)
            runOnUiThread {
                if (success) {
                    val method = adbManager.lastMethod.ifEmpty { "ADB" }
                    prefs.edit().putString("last_method", method).apply()
                    setActiveStatus(true, "$method: ${profile.providerName}\n${profile.primary}")
                } else showAdbErrorDialog()
            }
        }.start()
    }

    private fun applyDnsViaVpn(profile: DnsProfile) {
        isActivating = true
        btnToggle.text = "\u23F3"
        btnToggle.isEnabled = false
        pendingVpnProfile = profile
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermissionLauncher.launch(intent) else startVpnService(profile)
    }

    private fun startVpnService(profile: DnsProfile) {
        val intent = Intent(this, DnsVpnService::class.java).apply {
            action = DnsVpnService.ACTION_START
            putExtra(DnsVpnService.EXTRA_DNS_PRIMARY, profile.primary)
            profile.secondary?.let { putExtra(DnsVpnService.EXTRA_DNS_SECONDARY, it) }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        val label = "VPN: ${profile.providerName}\n${profile.primary}"
        prefs.edit()
            .putBoolean("vpn_active", true)
            .putString("vpn_label", label)
            .putString("last_method", "VPN")
            .apply()
        setActiveStatus(true, label)

        btnToggle.postDelayed({
            isActivating = false
            btnToggle.isEnabled = true
            btnToggle.requestFocus()
        }, 500)
    }

    private fun disableDnsQuiet(onDone: () -> Unit) {
        val adbIsActive = adbManager.getCurrentPrivateDnsMode()?.contains("hostname") == true
        if (adbIsActive) {
            Thread {
                adbManager.disablePrivateDns()
                runOnUiThread { setInactiveStatus(); onDone() }
            }.start()
        } else {
            startService(Intent(this, DnsVpnService::class.java).apply { action = DnsVpnService.ACTION_STOP })
            prefs.edit().putBoolean("vpn_active", false).putString("vpn_label", "").apply()
            setInactiveStatus(); onDone()
        }
    }

    private fun disableDns() { disableDnsQuiet {} }

    private fun setActiveStatus(active: Boolean, statusText: String) {
        isActive = active; tvStatus.text = statusText
        btnToggle.text = getString(R.string.deactivate)
        btnToggle.setBackgroundResource(R.drawable.btn_deactivate_background)
    }

    private fun setInactiveStatus() {
        isActive = false; tvStatus.text = getString(R.string.no_active_dns)
        btnToggle.text = getString(R.string.activate)
        btnToggle.setBackgroundResource(R.drawable.btn_activate_background)
    }

    private fun showDnsReport() {
        val vpnActive = prefs.getBoolean("vpn_active", false)
        val vpnLabel = prefs.getString("vpn_label", "") ?: ""
        val adbReport = adbManager.getFullDnsReport()
        val report = StringBuilder()
        report.appendLine(getString(R.string.report_private_dns_header)); report.appendLine(adbReport); report.appendLine()
        report.appendLine(getString(R.string.report_vpn_header))
        if (vpnActive && vpnLabel.isNotEmpty()) { report.appendLine(getString(R.string.report_vpn_active)); report.appendLine(getString(R.string.report_vpn_server, vpnLabel.replace("VPN: ", ""))) }
        else report.appendLine(getString(R.string.report_vpn_inactive))
        selectedProfile?.let {
            report.appendLine(); report.appendLine(getString(R.string.report_selected_header))
            report.appendLine("${it.providerName} - ${it.name}"); report.appendLine("Type: ${it.type}")
            report.appendLine(getString(R.string.report_method, methodForProfile(it)))
            report.appendLine(getString(R.string.report_primary, it.primary)); it.secondary?.let { s -> report.appendLine(getString(R.string.report_secondary, s)) }
        }
        AlertDialog.Builder(this).setTitle(getString(R.string.dns_report_title)).setMessage(report.toString()).setPositiveButton("OK", null).show()
    }

    private fun showAdbErrorDialog() {
        val msg = adbManager.lastError.ifEmpty { getString(R.string.adb_requires_root_or_pc, packageName) }
        val builder = AlertDialog.Builder(this)
            .setTitle(getString(R.string.adb_error_title))
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .setNeutralButton(getString(R.string.reset_adb_keys)) { _, _ ->
                adbManager.resetAdbKeys(); Toast.makeText(this, getString(R.string.adb_keys_reset), Toast.LENGTH_LONG).show()
            }

        // Sur Android 11+, proposer d'installer Shizuku si non disponible
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
            !adbManager.shizuku.isShizukuAvailable()) {
            val shizukuLabel = if (adbManager.shizuku.isShizukuInstalled())
                getString(R.string.shizuku_open)
            else
                getString(R.string.shizuku_install)
            builder.setNegativeButton(shizukuLabel) { _, _ ->
                try {
                    startActivity(android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("market://details?id=moe.shizuku.privileged.api")
                    ))
                } catch (_: Exception) {
                    startActivity(android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api")
                    ))
                }
            }
        }

        builder.show()
    }

    private fun runDnsTest() {
        val customDomain = prefs.getString("test_dns_domain", "ygg.re") ?: "ygg.re"
        val inputLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL; setPadding(60, 30, 60, 0)
        }
        inputLayout.addView(TextView(this).apply { text = getString(R.string.test_domain_label); setTextColor(0xFFCCCCCC.toInt()); textSize = 13f })
        val editDomain = android.widget.EditText(this).apply {
            setText(customDomain); setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFF888888.toInt())
            hint = "ex: ygg.re"; isSingleLine = true; setBackgroundColor(0xFF333333.toInt()); setPadding(20, 15, 20, 15)
        }
        inputLayout.addView(editDomain)
        AlertDialog.Builder(this).setTitle(getString(R.string.test_dns_title)).setView(inputLayout)
            .setPositiveButton(getString(R.string.test_button)) { _, _ ->
                val domain = editDomain.text.toString().trim().ifEmpty { customDomain }
                prefs.edit().putString("test_dns_domain", domain).apply(); executeTest(domain)
            }.setNegativeButton(getString(R.string.cancel), null).show()
    }

    private val BLOCKED_IPS = setOf("127.0.0.1", "0.0.0.0", "::1", "::0", "0:0:0:0:0:0:0:1", "0:0:0:0:0:0:0:0", "90.85.16.52", "194.6.135.126", "54.246.190.12")
    private fun isBlockedIp(ip: String?) = ip != null && (ip in BLOCKED_IPS || ip.startsWith("127.") || ip.startsWith("0.") || ip == "::1" || ip == "::")

    private fun executeTest(testDomain: String) {
        Toast.makeText(this, getString(R.string.test_flushing), Toast.LENGTH_SHORT).show()
        Thread {
            try { val f = InetAddress::class.java.getDeclaredField("addressCache"); f.isAccessible = true; val c = f.get(null); c?.javaClass?.getDeclaredMethod("clear")?.apply { isAccessible = true; invoke(c) } } catch (_: Exception) {}
            try { val f = InetAddress::class.java.getDeclaredField("negativeCache"); f.isAccessible = true; val c = f.get(null); c?.javaClass?.getDeclaredMethod("clear")?.apply { isAccessible = true; invoke(c) } } catch (_: Exception) {}

            var resolvedIp: String? = null; var err: String? = null; var blocked = false
            try { val a = InetAddress.getByName(testDomain); resolvedIp = a.hostAddress; blocked = isBlockedIp(resolvedIp) }
            catch (e: java.net.UnknownHostException) { err = "NXDOMAIN" }
            catch (e: Exception) { err = e.message }
            val internet = try { InetAddress.getByName("www.google.com"); true } catch (_: Exception) { false }

            runOnUiThread {
                val (title, msg) = when {
                    !internet -> getString(R.string.test_no_connection_title) to getString(R.string.test_no_connection_msg)
                    resolvedIp != null && blocked -> {
                        val warningText = if (isActive) getString(R.string.test_blocked_dns_bad) else getString(R.string.test_blocked_activate)
                        getString(R.string.test_blocked_title) to getString(R.string.test_blocked_msg, testDomain, resolvedIp, warningText)
                    }
                    resolvedIp != null -> getString(R.string.test_accessible_title) to if (isActive) getString(R.string.test_accessible_msg_active, testDomain, resolvedIp) else getString(R.string.test_accessible_msg_inactive, testDomain, resolvedIp)
                    else -> getString(R.string.test_unresolved_title) to if (isActive) getString(R.string.test_unresolved_msg_active, testDomain, err ?: "") else getString(R.string.test_unresolved_msg_inactive, testDomain, err ?: "")
                }
                AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()
            }
        }.start()
    }
}
