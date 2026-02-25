package net.appstorefr.perfectdnsmanager.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import net.appstorefr.perfectdnsmanager.R
import net.appstorefr.perfectdnsmanager.data.DnsProfile
import net.appstorefr.perfectdnsmanager.data.DnsType

class ProviderAdapter(
    private val grouped: Map<String, List<DnsProfile>>,
    private val onProviderLongClick: ((String, List<DnsProfile>) -> Unit)? = null,
    private val onProviderClick: (String, List<DnsProfile>) -> Unit
) : RecyclerView.Adapter<ProviderAdapter.ViewHolder>() {

    private val providers = grouped.keys.toList()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivProviderIcon: ImageView = view.findViewById(R.id.ivProviderIcon)
        val tvProviderName: TextView = view.findViewById(R.id.tvName)
        val tvProfileCount: TextView = view.findViewById(R.id.tvType)
        val tvBestProfile: TextView = view.findViewById(R.id.tvDescription)
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
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val providerName = providers[position]
        val profiles = grouped[providerName] ?: return

        holder.tvProviderName.text = providerName

        // Icône du fournisseur (basée sur le premier profil du groupe)
        val firstProfile = profiles.firstOrNull()
        val iconRes = if (firstProfile != null) {
            DnsProfile.getProviderIcon(firstProfile.providerName)
        } else {
            DnsProfile.getProviderIcon(providerName)
        }
        holder.ivProviderIcon.setImageResource(iconRes)

        // Compter les types disponibles + nombre de profils
        val types = profiles.map { it.type }.distinct()
        val typeStr = types.joinToString(" · ") {
            when (it) {
                DnsType.DOH -> "DoH"
                DnsType.DOT -> "DoT"
                DnsType.DOQ -> "DoQ"
                DnsType.DEFAULT -> "Standard"
            }
        }
        val count = profiles.size
        holder.tvProfileCount.text = if (count > 1) "$count profils · $typeStr" else typeStr

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

        holder.itemView.setOnClickListener {
            onProviderClick(providerName, profiles)
        }

        holder.itemView.setOnLongClickListener {
            onProviderLongClick?.invoke(providerName, profiles)
            onProviderLongClick != null
        }
    }

    private fun starsText(count: Int): String {
        return "★".repeat(count) + "☆".repeat(5 - count)
    }

    override fun getItemCount() = providers.size
}
