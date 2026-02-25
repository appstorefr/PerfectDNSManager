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

    private val providerDetailLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val json = result.data?.getStringExtra("SELECTED_PROFILE_JSON")
            if (json != null) {
                setResult(Activity.RESULT_OK, Intent().apply {
                    putExtra("SELECTED_PROFILE_JSON", json)
                })
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dns_selection)

        profileManager = ProfileManager(this)

        rvProviders = findViewById(R.id.rvDnsList)
        btnAddProfile = findViewById(R.id.btnAddProfile)
        btnSpeedtest = findViewById(R.id.btnSpeedtest)
        btnBack = findViewById(R.id.btnBack)
        tvMethodInfo = findViewById(R.id.tvMethodInfo)

        tvMethodInfo.visibility = android.view.View.GONE

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
        "ControlD", "NextDNS", "AdGuard", "Surfshark",
        "Mullvad", "Cloudflare", "Quad9", "FDN", "dns.sb", "Yandex", "Google"
    )

    private fun showProviders() {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val operatorEnabled = prefs.getBoolean("operator_dns_enabled", false)
        val adbDotEnabled = prefs.getBoolean("adb_dot_enabled", false)
        val showStandardDns = prefs.getBoolean("show_standard_dns", false)
        val showProfileVariants = prefs.getBoolean("show_profile_variants", false)

        // Déterminer le fournisseur par défaut
        val defaultProviderName = try {
            val defJson = prefs.getString("default_profile_json", null)
            if (defJson != null) Gson().fromJson(defJson, DnsProfile::class.java).providerName else null
        } catch (_: Exception) { null }
        fun displayKey(name: String) = if (name == defaultProviderName) "$name ★" else name

        // Filtrage des profils selon les toggles
        val baseFiltered = allProfiles.filter { profile ->
            if (profile.isOperatorDns && !operatorEnabled) return@filter false
            if (profile.type == DnsType.DOT && !adbDotEnabled) return@filter false
            // Masquer DNS Standard (non-opérateur) si toggle off
            if (!showStandardDns && profile.type == DnsType.DEFAULT && !profile.isOperatorDns) return@filter false
            true
        }

        // Si show_profile_variants off : ne garder qu'un seul profil par type par fournisseur
        // Trier pour toujours garder le profil "sans filtre" (Unfiltered/Standard/Basic/Unsecured) en priorité
        val filtered = if (!showProfileVariants) {
            val seen = mutableSetOf<String>()
            val sortedForDedup = baseFiltered.sortedBy { profile ->
                val n = profile.name.lowercase()
                when {
                    n == "unfiltered" || n == "unsecured" || n == "standard" || n == "basic" || n.startsWith("ns") -> 0
                    else -> 1
                }
            }
            sortedForDedup.filter { profile ->
                // Toujours garder les custom et opérateurs
                if (profile.isCustom || profile.isOperatorDns) return@filter true
                // Un seul profil par fournisseur par type
                val key = "${profile.providerName}:${profile.type}"
                if (key in seen) return@filter false
                seen.add(key)
                true
            }
        } else {
            baseFiltered
        }

        val grouped = linkedMapOf<String, List<DnsProfile>>()

        for (providerName in providerOrder) {
            val profiles = filtered.filter { it.providerName == providerName }
            if (profiles.isNotEmpty()) {
                grouped[displayKey(providerName)] = profiles
            }
        }

        // Fournisseurs normaux (hors classement, hors opérateurs)
        for ((pName, value) in filtered.groupBy { it.providerName }.toSortedMap()) {
            if (pName !in providerOrder && !value.any { it.isOperatorDns }) {
                grouped[displayKey(pName)] = value
            }
        }

        // DNS Opérateurs en dernier
        for ((pName, value) in filtered.groupBy { it.providerName }.toSortedMap()) {
            if (value.any { it.isOperatorDns }) {
                grouped[displayKey(pName)] = value
            }
        }

        val adapter = ProviderAdapter(
            grouped,
            onProviderLongClick = { providerName, profiles ->
                val allProfiles = if (providerName == "NextDNS" || providerName == "NextDNS ★") {
                    // Include custom NextDNS profiles from SharedPreferences
                    val customPrefs = getSharedPreferences("nextdns_profiles", MODE_PRIVATE)
                    val savedIds = customPrefs.getStringSet("profile_ids", emptySet()) ?: emptySet()
                    val prefsMain = getSharedPreferences("prefs", MODE_PRIVATE)
                    val adbDotEnabled = prefsMain.getBoolean("adb_dot_enabled", false)
                    val customProfiles = mutableListOf<DnsProfile>()
                    for (pid in savedIds.sorted()) {
                        customProfiles.add(DnsProfile(
                            providerName = "NextDNS", name = "Profil $pid", type = DnsType.DOH,
                            primary = "https://dns.nextdns.io/$pid", description = "Profil personnalisé NextDNS",
                            isCustom = true, testUrl = "https://test.nextdns.io/"
                        ))
                        customProfiles.add(DnsProfile(
                            providerName = "NextDNS", name = "Profil $pid", type = DnsType.DOQ,
                            primary = "quic://dns.nextdns.io/$pid", description = "Profil personnalisé NextDNS",
                            isCustom = true, testUrl = "https://test.nextdns.io/"
                        ))
                        if (adbDotEnabled) {
                            customProfiles.add(DnsProfile(
                                providerName = "NextDNS", name = "Profil $pid", type = DnsType.DOT,
                                primary = "$pid.dns.nextdns.io", description = "Profil personnalisé NextDNS",
                                isCustom = true, testUrl = "https://test.nextdns.io/"
                            ))
                        }
                    }
                    profiles + customProfiles
                } else {
                    profiles
                }
                val isNextDns = providerName == "NextDNS" || providerName == "NextDNS ★"
                val intent = Intent(this, DnsProviderDetailActivity::class.java).apply {
                    putExtra("PROVIDER_NAME", providerName)
                    putExtra("PROFILES_JSON", Gson().toJson(allProfiles))
                    putExtra("IS_NEXTDNS", isNextDns)
                }
                providerDetailLauncher.launch(intent)
            }
        ) { _, profiles ->
            // Simple clic : sélection directe du meilleur profil (DoQ > DoH > DoT > Standard)
            val best = profiles.minByOrNull {
                when (it.type) {
                    DnsType.DOQ -> 0; DnsType.DOH -> 1; DnsType.DOT -> 2; DnsType.DEFAULT -> 3
                }
            }
            if (best != null) returnProfileToMain(best)
        }

        rvProviders.layoutManager = LinearLayoutManager(this)
        rvProviders.adapter = adapter
    }

    private fun showProfileActions(profile: DnsProfile) {
        val isDefault = prefs().getString("default_profile_json", null)?.let {
            try { Gson().fromJson(it, DnsProfile::class.java).id == profile.id } catch (_: Exception) { false }
        } ?: false
        val defaultLabel = if (isDefault) getString(R.string.remove_default_dns) else getString(R.string.set_default_dns)
        val actions = arrayOf(defaultLabel, getString(R.string.edit_button), getString(R.string.delete_button))

        AlertDialog.Builder(this)
            .setTitle("${profile.providerName} — ${profile.name}")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> {
                        if (isDefault) {
                            prefs().edit().remove("default_profile_json").apply()
                            Toast.makeText(this, getString(R.string.default_dns_removed), Toast.LENGTH_SHORT).show()
                        } else {
                            prefs().edit().putString("default_profile_json", Gson().toJson(profile)).apply()
                            Toast.makeText(this, getString(R.string.default_dns_set, profile.providerName), Toast.LENGTH_SHORT).show()
                        }
                    }
                    1 -> showEditProfileDialog(profile)
                    2 -> confirmDeleteProfile(profile)
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

    private fun returnProfileToMain(profile: DnsProfile) {
        setResult(Activity.RESULT_OK, Intent().apply {
            putExtra("SELECTED_PROFILE_JSON", Gson().toJson(profile))
        })
        finish()
    }

    private fun runDnsSpeedtest() {
        startActivity(Intent(this, DnsSpeedtestActivity::class.java))
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
