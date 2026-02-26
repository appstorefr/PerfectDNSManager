package net.appstorefr.perfectdnsmanager.ui

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import net.appstorefr.perfectdnsmanager.R
import net.appstorefr.perfectdnsmanager.data.DnsProfile
import net.appstorefr.perfectdnsmanager.data.DnsType
import net.appstorefr.perfectdnsmanager.util.DnsColors

class ProviderAdapter(
    private val grouped: Map<String, List<DnsProfile>>,
    private val onProviderLongClick: ((String, List<DnsProfile>) -> Unit)? = null,
    private val onProviderEditClick: ((String, List<DnsProfile>) -> Unit)? = null,
    private val onProviderClick: (String, List<DnsProfile>) -> Unit
) : RecyclerView.Adapter<ProviderAdapter.ViewHolder>() {

    private val groupedMutable = LinkedHashMap(grouped)
    private val providers = grouped.keys.toMutableList()

    fun moveItem(from: Int, to: Int) {
        val item = providers.removeAt(from)
        providers.add(to, item)
        notifyItemMoved(from, to)
    }

    fun getProviderNames(): List<String> = providers.toList()

    fun getProfilesAt(position: Int): List<DnsProfile>? {
        if (position < 0 || position >= providers.size) return null
        return groupedMutable[providers[position]]
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivProviderIcon: ImageView = view.findViewById(R.id.ivProviderIcon)
        val tvProviderName: TextView = view.findViewById(R.id.tvName)
        val tvProfileCount: TextView = view.findViewById(R.id.tvType)
        val tvBestProfile: TextView = view.findViewById(R.id.tvDescription)
        val tvEditButton: TextView = view.findViewById(R.id.tvEditButton)
        val layoutRatings: View = view.findViewById(R.id.layoutRatings)
        val tvSpeedLabel: TextView = view.findViewById(R.id.tvSpeedLabel)
        val tvSpeedStars: TextView = view.findViewById(R.id.tvSpeedStars)
        val tvPrivacyLabel: TextView = view.findViewById(R.id.tvPrivacyLabel)
        val tvPrivacyStars: TextView = view.findViewById(R.id.tvPrivacyStars)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_profile, parent, false)
        view.isFocusable = true
        view.isLongClickable = true
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val providerName = providers[position]
        val profiles = groupedMutable[providerName] ?: return

        holder.tvProviderName.text = providerName

        // Icône du fournisseur (basée sur le premier profil du groupe)
        val firstProfile = profiles.firstOrNull()
        val iconRes = if (firstProfile != null) {
            DnsProfile.getProviderIcon(firstProfile.providerName)
        } else {
            DnsProfile.getProviderIcon(providerName)
        }
        holder.ivProviderIcon.setImageResource(iconRes)

        // Compter les types disponibles + nombre de profils (colored)
        val types = profiles.map { it.type }.distinct()
        val count = profiles.size
        val ssb = SpannableStringBuilder()
        if (count > 1) {
            ssb.append("$count profils \u00b7 ")
        }
        for ((idx, type) in types.withIndex()) {
            val label = DnsColors.labelForType(type)
            val color = DnsColors.colorForType(type)
            val start = ssb.length
            ssb.append(label)
            ssb.setSpan(ForegroundColorSpan(color), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (idx < types.size - 1) {
                ssb.append(" \u00b7 ")
            }
        }
        holder.tvProfileCount.text = ssb

        // Afficher le meilleur profil (DoQ > DoH > DoT > Standard)
        val best = profiles.minByOrNull {
            when (it.type) {
                DnsType.DOQ -> 0
                DnsType.DOH -> 1
                DnsType.DOT -> 2
                DnsType.DEFAULT -> 3
            }
        }
        holder.tvBestProfile.text = best?.let { "${it.primary}" } ?: ""
        holder.tvBestProfile.visibility = View.GONE

        // Étoiles vitesse/vie privée
        val actualProvider = firstProfile?.providerName ?: providerName
        val rating = DnsProfile.providerRatings[actualProvider]
        if (rating != null && firstProfile?.isOperatorDns != true && firstProfile?.isCustom != true) {
            holder.layoutRatings.visibility = View.VISIBLE
            val ctx = holder.itemView.context
            holder.tvSpeedLabel.text = ctx.getString(R.string.speed_label) + ": "
            holder.tvSpeedStars.text = starsText(rating.speed)
            holder.tvPrivacyLabel.text = ctx.getString(R.string.privacy_label) + ": "
            holder.tvPrivacyStars.text = starsText(rating.privacy)
        } else {
            holder.layoutRatings.visibility = View.GONE
        }

        // Green badge click -> open provider detail page
        holder.tvProfileCount.setOnClickListener {
            onProviderLongClick?.invoke(providerName, profiles)
        }
        holder.tvProfileCount.isFocusable = true
        holder.tvProfileCount.setBackgroundResource(R.drawable.focusable_item_background)

        holder.itemView.setOnClickListener {
            onProviderClick(providerName, profiles)
        }
        // Allow long-press to propagate to ItemTouchHelper for drag
        holder.itemView.setOnLongClickListener { false }
        holder.itemView.isLongClickable = true

        // Edit/settings button click -> show profile actions
        holder.tvEditButton.setOnClickListener {
            onProviderEditClick?.invoke(providerName, profiles)
        }
        // Long-press on edit button should not consume event (allow drag)
        holder.tvEditButton.setOnLongClickListener { false }
        holder.tvEditButton.isFocusable = true
        holder.tvEditButton.setBackgroundResource(R.drawable.focusable_item_background)

        // Long-press on type badge should not consume event (allow drag)
        holder.tvProfileCount.setOnLongClickListener { false }
    }

    private fun starsText(count: Int): String {
        return "★".repeat(count) + "☆".repeat(5 - count)
    }

    override fun getItemCount() = providers.size
}
