package net.appstorefr.perfectdnsmanager

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import net.appstorefr.perfectdnsmanager.data.DnsProfile
import net.appstorefr.perfectdnsmanager.data.DnsType
import net.appstorefr.perfectdnsmanager.data.ProfileManager
import net.appstorefr.perfectdnsmanager.ui.AddProfileDialog
import net.appstorefr.perfectdnsmanager.ui.ProviderAdapter
import net.appstorefr.perfectdnsmanager.util.DnsTester
import net.appstorefr.perfectdnsmanager.util.LocaleHelper
import com.google.gson.Gson

class DnsSelectionActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    private lateinit var rvProviders: RecyclerView
    private lateinit var btnAddProfile: Button
    private lateinit var btnSpeedtest: Button
    private lateinit var btnBack: Button
    private lateinit var tvMethodInfo: TextView
    private lateinit var profileManager: ProfileManager
    private var allProfiles = mutableListOf<DnsProfile>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dns_selection)

        profileManager = ProfileManager(this)

        rvProviders = findViewById(R.id.rvDnsList)
        btnAddProfile = findViewById(R.id.btnAddProfile)
        btnSpeedtest = findViewById(R.id.btnSpeedtest)
        btnBack = findViewById(R.id.btnBack)
        tvMethodInfo = findViewById(R.id.tvMethodInfo)

        val adbDotEnabled = getSharedPreferences("prefs", MODE_PRIVATE)
            .getBoolean("adb_dot_enabled", false)
        tvMethodInfo.text = if (adbDotEnabled)
            getString(R.string.method_doh_vpn_dot_adb)
        else
            getString(R.string.method_vpn_only)

        btnBack.requestFocus()
        btnBack.setOnClickListener { finish() }
        btnAddProfile.setOnClickListener { showAddProfileDialog() }
        btnSpeedtest.setOnClickListener { runDnsSpeedtest() }
    }

    override fun onResume() {
        super.onResume()
        loadProfiles()
    }

    private fun loadProfiles() {
        allProfiles = profileManager.loadProfiles().toMutableList()
        showProviders()
    }

    private val providerOrder = listOf(
        "ControlD", "dns.sb", "Surfshark", "Mullvad", "Quad9",
        "AdGuard", "Cloudflare", "NextDNS", "Yandex"
    )
    private val bottomProviders = setOf("Google")

    private fun showProviders() {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val operatorEnabled = prefs.getBoolean("operator_dns_enabled", false)
        val adbDotEnabled = prefs.getBoolean("adb_dot_enabled", false)
        val showStandardDns = prefs.getBoolean("show_standard_dns", false)
        val showProfileVariants = prefs.getBoolean("show_profile_variants", false)

        // Filtrage des profils selon les toggles
        val baseFiltered = allProfiles.filter { profile ->
            if (profile.isOperatorDns && !operatorEnabled) return@filter false
            if (profile.type == DnsType.DOT && !adbDotEnabled) return@filter false
            // Masquer DNS Standard (non-opérateur) si toggle off
            if (!showStandardDns && profile.type == DnsType.DEFAULT && !profile.isOperatorDns) return@filter false
            true
        }

        // Si show_profile_variants off : ne garder que le premier profil DoH par fournisseur
        val filtered = if (!showProfileVariants) {
            val seen = mutableSetOf<String>()
            baseFiltered.filter { profile ->
                // Toujours garder les custom, opérateurs et favoris
                if (profile.isCustom || profile.isOperatorDns || profile.isFavorite) return@filter true
                // Pour les profils DoT, garder tel quel (déjà filtré si adb off)
                if (profile.type == DnsType.DOT) return@filter true
                // Pour les profils Standard, garder tel quel (déjà filtré si showStandard off)
                if (profile.type == DnsType.DEFAULT) return@filter true
                // Pour DoH : un seul par fournisseur
                val key = "${profile.providerName}:${profile.type}"
                seen.add(key)
            }
        } else {
            baseFiltered
        }

        val favorites = filtered.filter { it.isFavorite }
        val grouped = linkedMapOf<String, List<DnsProfile>>()

        if (favorites.isNotEmpty()) {
            grouped[getString(R.string.favorites_label)] = favorites
        }

        for (providerName in providerOrder) {
            val profiles = filtered.filter { it.providerName == providerName }
            if (profiles.isNotEmpty()) {
                grouped[providerName] = profiles
            }
        }

        // Fournisseurs normaux (hors classement et hors bottomProviders)
        for ((key, value) in filtered.groupBy { it.providerName }.toSortedMap()) {
            if (key !in providerOrder && key !in bottomProviders) {
                grouped[key] = value
            }
        }

        // Fournisseurs en bas de liste (Google etc.)
        for (providerName in bottomProviders) {
            val profiles = filtered.filter { it.providerName == providerName }
            if (profiles.isNotEmpty()) {
                grouped[providerName] = profiles
            }
        }

        val adapter = ProviderAdapter(
            grouped,
            onProviderLongClick = { providerName, profiles ->
                // Long press : afficher les actions du premier profil (ou choisir si plusieurs)
                if (profiles.size == 1) {
                    showProfileActions(profiles.first())
                } else {
                    showProfilesClickToSelect(providerName, profiles)
                }
            }
        ) { providerName, profiles ->
            if (providerName == getString(R.string.favorites_label)) {
                showProfilesClickToSelect(providerName, profiles)
            } else if (providerName == "NextDNS") {
                showNextDnsOptions(profiles)
            } else if (profiles.size == 1) {
                // Mode noob : 1 seul profil → sélection directe
                returnProfileToMain(profiles.first())
            } else {
                showProfilesClickToSelect(providerName, profiles)
            }
        }

        rvProviders.layoutManager = LinearLayoutManager(this)
        rvProviders.adapter = adapter
    }

    private fun showProfilesClickToSelect(providerName: String, profiles: List<DnsProfile>) {
        // Tri: DoH en premier, Standard en second, DoT en troisième
        val sorted = profiles.sortedBy {
            when (it.type) { DnsType.DOH -> 0; DnsType.DEFAULT -> 1; DnsType.DOT -> 2 }
        }

        val isFavGroup = providerName == getString(R.string.favorites_label)
        val items = sorted.map { p ->
            val typeLabel = when (p.type) { DnsType.DOH -> "DoH"; DnsType.DOT -> "DoT"; DnsType.DEFAULT -> "Standard" }
            val methodLabel = if (p.type == DnsType.DOT) "ADB" else "VPN"
            val fav = if (p.isFavorite) "★ " else ""
            val prefix = if (isFavGroup) "${p.providerName} - " else ""
            "$fav$prefix${p.name} ($typeLabel/$methodLabel) — ${p.primary}"
        }.toTypedArray()

        val dialog = AlertDialog.Builder(this)
            .setTitle("$providerName\n${getString(R.string.long_press_edit)}")
            .setItems(items) { _, which ->
                returnProfileToMain(sorted[which])
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        dialog.setOnShowListener {
            val listView = dialog.listView
            listView?.setOnItemLongClickListener { _, _, position, _ ->
                dialog.dismiss()
                showProfileActions(sorted[position])
                true
            }
        }

        dialog.show()
    }

    private fun showProfileActions(profile: DnsProfile) {
        val favLabel = if (profile.isFavorite) getString(R.string.remove_from_favorites) else getString(R.string.add_to_favorites)
        val isDefault = prefs().getString("default_profile_json", null)?.let {
            try { Gson().fromJson(it, DnsProfile::class.java).id == profile.id } catch (_: Exception) { false }
        } ?: false
        val defaultLabel = if (isDefault) getString(R.string.remove_default_dns) else getString(R.string.set_default_dns)
        val actions = arrayOf(favLabel, defaultLabel, getString(R.string.edit_button), getString(R.string.delete_button))

        AlertDialog.Builder(this)
            .setTitle("${profile.providerName} — ${profile.name}")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> {
                        profileManager.toggleFavorite(profile.id)
                        loadProfiles()
                        Toast.makeText(this,
                            if (profile.isFavorite) getString(R.string.removed_from_favorites) else getString(R.string.added_to_favorites),
                            Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        if (isDefault) {
                            prefs().edit().remove("default_profile_json").apply()
                            Toast.makeText(this, getString(R.string.default_dns_removed), Toast.LENGTH_SHORT).show()
                        } else {
                            prefs().edit().putString("default_profile_json", Gson().toJson(profile)).apply()
                            Toast.makeText(this, getString(R.string.default_dns_set, profile.providerName), Toast.LENGTH_SHORT).show()
                        }
                    }
                    2 -> showEditProfileDialog(profile)
                    3 -> confirmDeleteProfile(profile)
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun prefs() = getSharedPreferences("prefs", MODE_PRIVATE)

    private fun confirmDeleteProfile(profile: DnsProfile) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_profile_title))
            .setMessage("${profile.providerName} — ${profile.name}\n${profile.primary}")
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                profileManager.deleteProfile(profile.id)
                loadProfiles()
                Toast.makeText(this, getString(R.string.profile_deleted), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showEditProfileDialog(profile: DnsProfile) {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 40, 60, 20)
        }

        val etName = android.widget.EditText(this).apply {
            setText(profile.name); setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF333333.toInt()); setPadding(20, 15, 20, 15)
            hint = getString(R.string.profile_name); setHintTextColor(0xFF888888.toInt())
        }
        val etPrimary = android.widget.EditText(this).apply {
            setText(profile.primary); setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF333333.toInt()); setPadding(20, 15, 20, 15)
            hint = getString(R.string.primary_dns); setHintTextColor(0xFF888888.toInt())
        }
        val etSecondary = android.widget.EditText(this).apply {
            setText(profile.secondary ?: ""); setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF333333.toInt()); setPadding(20, 15, 20, 15)
            hint = getString(R.string.secondary_dns_optional); setHintTextColor(0xFF888888.toInt())
        }

        val lbl = { text: String -> TextView(this).apply { this.text = text; setTextColor(0xFFCCCCCC.toInt()); setPadding(0,16,0,8) } }

        layout.addView(lbl(getString(R.string.name_label))); layout.addView(etName)
        layout.addView(lbl(getString(R.string.primary_dns_label))); layout.addView(etPrimary)
        if (profile.type == DnsType.DEFAULT) {
            layout.addView(lbl(getString(R.string.secondary_dns_label))); layout.addView(etSecondary)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.edit_profile_title))
            .setView(layout)
            .setPositiveButton(getString(R.string.save_button)) { _, _ ->
                val updated = profile.copy(
                    name = etName.text.toString().trim().ifEmpty { profile.name },
                    primary = etPrimary.text.toString().trim().ifEmpty { profile.primary },
                    secondary = etSecondary.text.toString().trim().takeIf { it.isNotEmpty() }
                )
                profileManager.updateProfile(updated)
                loadProfiles()
                Toast.makeText(this, getString(R.string.profile_updated), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // ── NextDNS ──

    private fun showNextDnsOptions(profiles: List<DnsProfile>) {
        val customPrefs = getSharedPreferences("nextdns_profiles", MODE_PRIVATE)
        val savedIds = customPrefs.getStringSet("profile_ids", emptySet()) ?: emptySet()
        val adbDotEnabled = getSharedPreferences("prefs", MODE_PRIVATE)
            .getBoolean("adb_dot_enabled", false)

        val options = mutableListOf<String>()
        val optionProfiles = mutableListOf<DnsProfile?>()

        val sorted = profiles.sortedBy {
            when (it.type) { DnsType.DOH -> 0; DnsType.DEFAULT -> 1; DnsType.DOT -> 2 }
        }
        for (p in sorted) {
            val typeLabel = when (p.type) { DnsType.DOH -> "DoH"; DnsType.DOT -> "DoT"; else -> "Standard" }
            val methodLabel = if (p.type == DnsType.DOT) "ADB" else "VPN"
            val fav = if (p.isFavorite) "★ " else ""
            options.add("$fav${p.name} ($typeLabel/$methodLabel) — ${p.primary}")
            optionProfiles.add(p)
        }

        for (pid in savedIds.sorted()) {
            val dohAddr = "https://dns.nextdns.io/$pid"
            options.add("📌 Profil $pid (DoH/VPN) — $dohAddr")
            optionProfiles.add(DnsProfile(
                providerName = "NextDNS", name = "Profil $pid", type = DnsType.DOH,
                primary = dohAddr, description = "Profil personnalisé NextDNS",
                isCustom = true, testUrl = "https://test.nextdns.io/"
            ))
            if (adbDotEnabled) {
                val dotAddr = "$pid.dns.nextdns.io"
                options.add("📌 Profil $pid (DoT/ADB) — $dotAddr")
                optionProfiles.add(DnsProfile(
                    providerName = "NextDNS", name = "Profil $pid", type = DnsType.DOT,
                    primary = dotAddr, description = "Profil personnalisé NextDNS",
                    isCustom = true, testUrl = "https://test.nextdns.io/"
                ))
            }
        }

        options.add(getString(R.string.add_nextdns_profile))
        optionProfiles.add(null)

        val dialog = AlertDialog.Builder(this)
            .setTitle("NextDNS\n${getString(R.string.long_press_edit)}")
            .setItems(options.toTypedArray()) { _, which ->
                val profile = optionProfiles[which]
                if (profile != null) {
                    returnProfileToMain(profile)
                } else {
                    showAddNextDnsProfileDialog()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        dialog.setOnShowListener {
            dialog.listView?.setOnItemLongClickListener { _, _, position, _ ->
                val profile = optionProfiles.getOrNull(position)
                if (profile != null) {
                    dialog.dismiss()
                    showProfileActions(profile)
                }
                true
            }
        }

        dialog.show()
    }

    private fun showAddNextDnsProfileDialog() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 40, 60, 20)
        }
        val tvExplain = TextView(this).apply {
            text = getString(R.string.nextdns_explain)
            setTextColor(0xFFCCCCCC.toInt()); textSize = 13f
        }
        layout.addView(tvExplain)

        val editId = android.widget.EditText(this).apply {
            hint = "Ex: a1b2c3"; setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF888888.toInt()); setBackgroundColor(0xFF333333.toInt())
            setPadding(20, 15, 20, 15); isSingleLine = true; textSize = 18f
        }
        layout.addView(editId)

        val tvPreview = TextView(this).apply {
            setTextColor(0xFF00AAFF.toInt()); textSize = 12f; setPadding(0, 12, 0, 0)
        }
        layout.addView(tvPreview)

        editId.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val id = s.toString().trim()
                tvPreview.text = if (id.isNotEmpty()) {
                    "DoH: https://dns.nextdns.io/$id\nDoT: $id.dns.nextdns.io"
                } else ""
            }
        })

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.nextdns_add_title))
            .setView(layout)
            .setPositiveButton(getString(R.string.nextdns_add_select)) { _, _ ->
                val profileId = editId.text.toString().trim()
                if (profileId.isEmpty()) {
                    Toast.makeText(this, getString(R.string.nextdns_id_required), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val customPrefs = getSharedPreferences("nextdns_profiles", MODE_PRIVATE)
                val savedIds = (customPrefs.getStringSet("profile_ids", emptySet()) ?: emptySet()).toMutableSet()
                savedIds.add(profileId)
                customPrefs.edit().putStringSet("profile_ids", savedIds).apply()

                val addr = "https://dns.nextdns.io/$profileId"
                returnProfileToMain(DnsProfile(
                    providerName = "NextDNS", name = "Profil $profileId", type = DnsType.DOH,
                    primary = addr, description = "Profil personnalisé NextDNS",
                    isCustom = true, testUrl = "https://test.nextdns.io/"
                ))
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun returnProfileToMain(profile: DnsProfile) {
        setResult(Activity.RESULT_OK, Intent().apply {
            putExtra("SELECTED_PROFILE_JSON", Gson().toJson(profile))
        })
        finish()
    }

    private fun runDnsSpeedtest() {
        Toast.makeText(this, getString(R.string.dns_speedtest_running), Toast.LENGTH_SHORT).show()
        btnSpeedtest.isEnabled = false

        Thread {
            val presets = DnsProfile.getDefaultPresets()
            // Prendre le premier profil DoH de chaque fournisseur
            val seenProviders = mutableSetOf<String>()
            val testProfiles = presets.filter { p ->
                if (p.isOperatorDns) return@filter false
                if (p.providerName in seenProviders) return@filter false
                if (p.type == DnsType.DOH || p.type == DnsType.DEFAULT) {
                    seenProviders.add(p.providerName)
                    true
                } else false
            }

            // Client HTTP partagé pour tous les tests DoH (réutilise le pool de connexions)
            val sharedClient = okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            data class SpeedResult(val provider: String, val address: String, val latency: Long?, val type: String)
            val results = mutableListOf<SpeedResult>()

            for (profile in testProfiles) {
                val latency = if (profile.type == DnsType.DOH) {
                    // Warmup (TCP+TLS handshake) puis mesure réelle
                    DnsTester.measureDohLatency(profile.primary, client = sharedClient)
                    DnsTester.measureDohLatency(profile.primary, client = sharedClient)
                } else {
                    DnsTester.measureLatency(profile.primary)
                }
                val typeLabel = if (profile.type == DnsType.DOH) "DoH" else "Standard"
                results.add(SpeedResult(profile.providerName, profile.primary, latency, typeLabel))
            }

            // Fermer le pool de connexions
            sharedClient.dispatcher.executorService.shutdown()
            sharedClient.connectionPool.evictAll()

            val sorted = results.sortedWith(compareBy(nullsLast()) { it.latency })
            val sb = StringBuilder()
            for (r in sorted) {
                val latencyStr = if (r.latency != null)
                    getString(R.string.dns_speedtest_ms, r.latency)
                else
                    getString(R.string.dns_speedtest_error)
                sb.appendLine(getString(R.string.dns_speedtest_result, r.provider, r.type, latencyStr))
            }

            runOnUiThread {
                btnSpeedtest.isEnabled = true
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.dns_speedtest_title))
                    .setMessage(sb.toString().trimEnd())
                    .setPositiveButton("OK", null)
                    .show()
            }
        }.start()
    }

    private fun showAddProfileDialog() {
        val advancedEnabled = getSharedPreferences("prefs", MODE_PRIVATE)
            .getBoolean("advanced_features_enabled", false)
        AddProfileDialog(this, advancedEnabled) { newProfile ->
            profileManager.addProfile(newProfile)
            loadProfiles()
        }.show()
    }
}
