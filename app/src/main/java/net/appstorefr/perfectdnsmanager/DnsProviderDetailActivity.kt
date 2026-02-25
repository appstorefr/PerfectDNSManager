package net.appstorefr.perfectdnsmanager

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import net.appstorefr.perfectdnsmanager.data.DnsProfile
import net.appstorefr.perfectdnsmanager.data.DnsType
import net.appstorefr.perfectdnsmanager.util.LocaleHelper

class DnsProviderDetailActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dns_provider_detail)

        val providerName = intent.getStringExtra("PROVIDER_NAME") ?: run { finish(); return }
        val profilesJson = intent.getStringExtra("PROFILES_JSON") ?: run { finish(); return }
        val profiles: List<DnsProfile> = Gson().fromJson(profilesJson, object : TypeToken<List<DnsProfile>>() {}.type)
        val isNextDns = intent.getBooleanExtra("IS_NEXTDNS", false)

        // Header
        val btnBack: Button = findViewById(R.id.btnBack)
        val tvProviderName: TextView = findViewById(R.id.tvProviderName)
        val ivProviderIcon: ImageView = findViewById(R.id.ivProviderIcon)

        btnBack.setOnClickListener { finish() }
        val actualName = providerName.removeSuffix(" ★")
        tvProviderName.text = actualName
        ivProviderIcon.setImageResource(DnsProfile.getProviderIcon(actualName))

        // Ratings
        val rating = DnsProfile.providerRatings[actualName]
        val layoutRatings: LinearLayout = findViewById(R.id.layoutRatings)
        if (rating != null) {
            layoutRatings.visibility = View.VISIBLE
            findViewById<TextView>(R.id.tvSpeedLabel).text = getString(R.string.speed_label) + ": "
            findViewById<TextView>(R.id.tvSpeedStars).text = "★".repeat(rating.speed) + "☆".repeat(5 - rating.speed)
            findViewById<TextView>(R.id.tvPrivacyLabel).text = getString(R.string.privacy_label) + ": "
            findViewById<TextView>(R.id.tvPrivacyStars).text = "★".repeat(rating.privacy) + "☆".repeat(5 - rating.privacy)
        }

        // NextDNS: add profile button
        val btnAddProfile: Button = findViewById(R.id.btnAddProfile)
        if (isNextDns) {
            btnAddProfile.visibility = View.VISIBLE
            btnAddProfile.setOnClickListener { showAddNextDnsProfileDialog() }
        }

        // Group profiles by type, ordered: DoQ > DoH > DoT > Standard
        val typeOrder = listOf(DnsType.DOQ, DnsType.DOH, DnsType.DOT, DnsType.DEFAULT)
        val grouped = typeOrder.mapNotNull { type ->
            val typeProfiles = profiles.filter { it.type == type }
            if (typeProfiles.isNotEmpty()) type to typeProfiles else null
        }

        // Profile list with expandable categories
        val rvProfiles: RecyclerView = findViewById(R.id.rvProfiles)
        rvProfiles.layoutManager = LinearLayoutManager(this)
        rvProfiles.adapter = ExpandableProfileAdapter(rvProfiles, grouped) { profile ->
            setResult(Activity.RESULT_OK, Intent().apply {
                putExtra("SELECTED_PROFILE_JSON", Gson().toJson(profile))
            })
            finish()
        }
    }

    private fun showAddNextDnsProfileDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 20)
        }

        val tvProto = TextView(this).apply {
            text = "Protocole :"
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 13f
            setPadding(0, 0, 0, 8)
        }
        layout.addView(tvProto)

        val protocols = arrayOf("DoQ (recommandé)", "DoH", "DoT")
        val protoCodes = arrayOf(DnsType.DOQ, DnsType.DOH, DnsType.DOT)
        var selectedProto = 0
        val rgProto = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        for (i in protocols.indices) {
            val rb = RadioButton(this).apply {
                text = protocols[i]
                setTextColor(0xFFFFFFFF.toInt())
                buttonTintList = ColorStateList.valueOf(0xFF2196F3.toInt())
                id = View.generateViewId()
                textSize = 14f
                focusable = View.FOCUSABLE
                if (i == 0) isChecked = true
            }
            rgProto.addView(rb)
        }
        rgProto.setOnCheckedChangeListener { group, checkedId ->
            for (i in 0 until group.childCount) {
                if (group.getChildAt(i).id == checkedId) { selectedProto = i; break }
            }
        }
        layout.addView(rgProto)

        val tvId = TextView(this).apply {
            text = "\nProfile ID NextDNS :"
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 13f
        }
        layout.addView(tvId)

        val etProfileId = EditText(this).apply {
            hint = "ex: abc123"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF666666.toInt())
            textSize = 14f
            focusable = View.FOCUSABLE
        }
        layout.addView(etProfileId)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.add_nextdns_profile))
            .setView(layout)
            .setPositiveButton("OK") { _, _ ->
                val profileId = etProfileId.text.toString().trim()
                if (profileId.isEmpty()) {
                    Toast.makeText(this, getString(R.string.nextdns_id_required), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val type = protoCodes[selectedProto]
                val typeLabel = when (type) { DnsType.DOQ -> "DoQ"; DnsType.DOH -> "DoH"; DnsType.DOT -> "DoT"; else -> "" }
                val primary = when (type) {
                    DnsType.DOQ -> "quic://dns.nextdns.io/$profileId"
                    DnsType.DOH -> "https://dns.nextdns.io/$profileId"
                    DnsType.DOT -> "$profileId.dns.nextdns.io"
                    else -> ""
                }
                val customPrefs = getSharedPreferences("nextdns_profiles", MODE_PRIVATE)
                val savedIds = (customPrefs.getStringSet("profile_ids", emptySet()) ?: emptySet()).toMutableSet()
                savedIds.add(profileId)
                customPrefs.edit().putStringSet("profile_ids", savedIds).apply()

                val newProfile = DnsProfile(
                    providerName = "NextDNS", name = "Profil $profileId ($typeLabel)", type = type,
                    primary = primary, description = "Profil personnalisé NextDNS ($typeLabel)",
                    isCustom = true, testUrl = "https://test.nextdns.io/"
                )
                setResult(Activity.RESULT_OK, Intent().apply {
                    putExtra("SELECTED_PROFILE_JSON", Gson().toJson(newProfile))
                })
                finish()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // -- Sealed class for list items --
    private sealed class ListItem {
        data class Header(val type: DnsType, val count: Int, var expanded: Boolean = true) : ListItem()
        data class Profile(val profile: DnsProfile) : ListItem()
    }

    private class ExpandableProfileAdapter(
        private val recyclerView: RecyclerView,
        private val grouped: List<Pair<DnsType, List<DnsProfile>>>,
        private val onProfileClick: (DnsProfile) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            private const val TYPE_HEADER = 0
            private const val TYPE_PROFILE = 1
        }

        private val items = mutableListOf<ListItem>()
        // Track expanded state per DnsType
        private val expandedState = mutableMapOf<DnsType, Boolean>()

        init {
            // Only DoQ expanded by default, others collapsed
            for ((type, _) in grouped) {
                expandedState[type] = (type == DnsType.DOQ)
            }
            rebuildItems()
        }

        private fun rebuildItems() {
            items.clear()
            for ((type, profiles) in grouped) {
                val expanded = expandedState[type] ?: true
                items.add(ListItem.Header(type, profiles.size, expanded))
                if (expanded) {
                    for (p in profiles) {
                        items.add(ListItem.Profile(p))
                    }
                }
            }
        }

        override fun getItemViewType(position: Int): Int {
            return when (items[position]) {
                is ListItem.Header -> TYPE_HEADER
                is ListItem.Profile -> TYPE_PROFILE
            }
        }

        // -- Header ViewHolder (dedicated layout) --
        class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
            val tvArrow: TextView = view.findViewById(R.id.tvArrow)
            val tvCategoryBadge: TextView = view.findViewById(R.id.tvCategoryBadge)
            val tvCategoryMethod: TextView = view.findViewById(R.id.tvCategoryMethod)
            val tvCategoryCount: TextView = view.findViewById(R.id.tvCategoryCount)
        }

        // -- Profile ViewHolder (reuses same layout) --
        class ProfileVH(view: View) : RecyclerView.ViewHolder(view) {
            val tvTypeBadge: TextView = view.findViewById(R.id.tvTypeBadge)
            val tvProfileName: TextView = view.findViewById(R.id.tvProfileName)
            val tvMethod: TextView = view.findViewById(R.id.tvMethod)
            val tvAddress: TextView = view.findViewById(R.id.tvAddress)
            val tvDescription: TextView = view.findViewById(R.id.tvDescription)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == TYPE_HEADER) {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_dns_category_header, parent, false)
                HeaderVH(view)
            } else {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_dns_profile_detail, parent, false)
                ProfileVH(view)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is ListItem.Header -> bindHeader(holder as HeaderVH, item)
                is ListItem.Profile -> bindProfile(holder as ProfileVH, item.profile)
            }
        }

        private fun bindHeader(holder: HeaderVH, header: ListItem.Header) {
            val (typeLabel, badgeColor) = getTypeInfo(header.type)

            holder.tvArrow.text = if (header.expanded) "▼" else "▶"

            holder.tvCategoryBadge.text = typeLabel
            val badgeBg = GradientDrawable().apply {
                setColor(badgeColor)
                cornerRadius = 12f
            }
            holder.tvCategoryBadge.background = badgeBg

            val methodLabel = if (header.type == DnsType.DOT) "ADB" else "VPN"
            holder.tvCategoryMethod.text = methodLabel

            holder.tvCategoryCount.text = "${header.count} profil${if (header.count > 1) "s" else ""}"

            holder.itemView.setOnClickListener {
                expandedState[header.type] = !(expandedState[header.type] ?: true)
                rebuildItems()
                notifyDataSetChanged()
                // Restore focus on this header after rebuild
                val headerPos = items.indexOfFirst { it is ListItem.Header && (it as ListItem.Header).type == header.type }
                if (headerPos >= 0) {
                    recyclerView.post {
                        val vh = recyclerView.findViewHolderForAdapterPosition(headerPos)
                        vh?.itemView?.requestFocus()
                    }
                }
            }
        }

        private fun bindProfile(holder: ProfileVH, profile: DnsProfile) {
            val (typeLabel, badgeColor) = getTypeInfo(profile.type)

            holder.tvTypeBadge.text = typeLabel
            val badgeBg = GradientDrawable().apply {
                setColor(badgeColor)
                cornerRadius = 8f
            }
            holder.tvTypeBadge.background = badgeBg

            holder.tvProfileName.text = profile.name
            holder.tvProfileName.textSize = 15f
            holder.tvProfileName.setTextColor(Color.WHITE)

            holder.tvMethod.text = if (profile.type == DnsType.DOT) "ADB" else "VPN"
            holder.tvMethod.setTextColor(Color.parseColor("#888888"))

            holder.tvAddress.text = profile.primary
            holder.tvAddress.visibility = View.VISIBLE

            holder.tvDescription.text = profile.description
            holder.tvDescription.visibility = if (profile.description.isNullOrBlank()) View.GONE else View.VISIBLE

            // Reset background for profile items
            holder.itemView.setBackgroundResource(R.drawable.focusable_item_background)

            holder.itemView.setOnClickListener {
                onProfileClick(profile)
            }
        }

        private fun getTypeInfo(type: DnsType): Pair<String, Int> {
            return when (type) {
                DnsType.DOQ -> "DoQ" to Color.parseColor("#9C27B0")
                DnsType.DOH -> "DoH" to Color.parseColor("#2196F3")
                DnsType.DOT -> "DoT" to Color.parseColor("#FF9800")
                DnsType.DEFAULT -> "Standard" to Color.parseColor("#607D8B")
            }
        }

        override fun getItemCount() = items.size
    }
}
