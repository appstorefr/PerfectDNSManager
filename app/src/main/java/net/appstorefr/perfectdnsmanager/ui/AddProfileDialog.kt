package net.appstorefr.perfectdnsmanager.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.*
import net.appstorefr.perfectdnsmanager.R
import net.appstorefr.perfectdnsmanager.data.DnsProfile
import net.appstorefr.perfectdnsmanager.data.DnsType

class AddProfileDialog(
    context: Context,
    private val advancedEnabled: Boolean = true,
    private val onProfileCreated: (DnsProfile) -> Unit
) : Dialog(context) {

    private lateinit var etName: EditText
    private lateinit var rgType: RadioGroup
    private lateinit var rbDoh: RadioButton
    private lateinit var rbDot: RadioButton
    private lateinit var rbStandard: RadioButton
    private lateinit var etPrimary: EditText
    private lateinit var etSecondary: EditText
    private lateinit var tvSecondaryLabel: TextView
    private lateinit var etPrimaryV6: EditText
    private lateinit var etSecondaryV6: EditText
    private lateinit var tvPrimaryV6Label: TextView
    private lateinit var btnSave: Button
    private lateinit var btnCancel: Button
    private lateinit var cbNextDns: CheckBox
    private lateinit var etNextDnsId: EditText
    private lateinit var llNextDns: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_add_profile)
        window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)

        initViews()
        setupTypeSelection()
        setupNextDns()
        setupButtons()
    }

    private fun initViews() {
        etName = findViewById(R.id.etName)
        rgType = findViewById(R.id.rgType)
        rbDoh = findViewById(R.id.rbDoh)
        rbDot = findViewById(R.id.rbDot)
        rbStandard = findViewById(R.id.rbStandard)
        etPrimary = findViewById(R.id.etPrimary)
        etSecondary = findViewById(R.id.etSecondary)
        tvSecondaryLabel = findViewById(R.id.tvSecondaryLabel)
        etPrimaryV6 = findViewById(R.id.etPrimaryV6)
        etSecondaryV6 = findViewById(R.id.etSecondaryV6)
        tvPrimaryV6Label = findViewById(R.id.tvPrimaryV6Label)
        btnSave = findViewById(R.id.btnSave)
        btnCancel = findViewById(R.id.btnCancel)
        cbNextDns = findViewById(R.id.cbNextDns)
        etNextDnsId = findViewById(R.id.etNextDnsId)
        llNextDns = findViewById(R.id.llNextDns)

        rbDoh.isChecked = true
        etSecondary.visibility = View.GONE
        tvSecondaryLabel.visibility = View.GONE
        llNextDns.visibility = View.GONE

        // Masquer DoT et Standard si mode avancé désactivé
        if (!advancedEnabled) {
            rbDot.visibility = View.GONE
            rbStandard.visibility = View.GONE
        }
    }

    private fun setupTypeSelection() {
        rgType.setOnCheckedChangeListener { _, checkedId ->
            val showSecondary = checkedId == R.id.rbStandard
            etSecondary.visibility = if (showSecondary) View.VISIBLE else View.GONE
            tvSecondaryLabel.visibility = if (showSecondary) View.VISIBLE else View.GONE
            etPrimaryV6.visibility = if (showSecondary) View.VISIBLE else View.GONE
            etSecondaryV6.visibility = if (showSecondary) View.VISIBLE else View.GONE
            tvPrimaryV6Label.visibility = if (showSecondary) View.VISIBLE else View.GONE
            if (!showSecondary) {
                etSecondary.text.clear()
                etPrimaryV6.text.clear()
                etSecondaryV6.text.clear()
            }

            // NextDNS uniquement pour DoH et DoT
            val canNextDns = checkedId == R.id.rbDoh || checkedId == R.id.rbDot
            cbNextDns.visibility = if (canNextDns) View.VISIBLE else View.GONE
            if (!canNextDns) {
                cbNextDns.isChecked = false
                llNextDns.visibility = View.GONE
            }

            etPrimary.hint = when (checkedId) {
                R.id.rbDoh -> "Ex: https://dns.adguard-dns.com/dns-query"
                R.id.rbDot -> "Ex: dns.adguard-dns.com"
                else -> "Ex: 94.140.14.14"
            }

            // Si NextDNS activé, masquer le champ primaire
            updateNextDnsVisibility()
        }
    }

    private fun setupNextDns() {
        cbNextDns.setOnCheckedChangeListener { _, isChecked ->
            llNextDns.visibility = if (isChecked) View.VISIBLE else View.GONE
            etPrimary.visibility = if (isChecked) View.GONE else View.VISIBLE
            if (isChecked) {
                etName.setText("NextDNS")
            }
            updateNextDnsVisibility()
        }
    }

    private fun updateNextDnsVisibility() {
        val isNextDns = cbNextDns.isChecked
        etPrimary.visibility = if (isNextDns) View.GONE else View.VISIBLE
        llNextDns.visibility = if (isNextDns) View.VISIBLE else View.GONE
    }

    private fun setupButtons() {
        btnSave.setOnClickListener {
            if (validateInput()) {
                val selectedType = when (rgType.checkedRadioButtonId) {
                    R.id.rbDoh -> DnsType.DOH
                    R.id.rbDot -> DnsType.DOT
                    else -> DnsType.DEFAULT
                }

                val primary: String
                val providerName: String
                val testUrl: String?

                if (cbNextDns.isChecked) {
                    val profileId = etNextDnsId.text.toString().trim()
                    providerName = etName.text.toString().trim().ifEmpty { "NextDNS" }
                    testUrl = "https://test.nextdns.io/"
                    primary = if (selectedType == DnsType.DOH) {
                        "https://dns.nextdns.io/$profileId"
                    } else {
                        "$profileId.dns.nextdns.io"
                    }
                } else {
                    primary = etPrimary.text.toString().trim()
                    providerName = etName.text.toString().trim()
                    testUrl = null
                }

                val profile = DnsProfile(
                    providerName = providerName,
                    name = if (cbNextDns.isChecked) "Profil ${etNextDnsId.text.toString().trim()}" else "Custom",
                    type = selectedType,
                    primary = primary,
                    secondary = etSecondary.text.toString().trim().takeIf { it.isNotBlank() },
                    primaryV6 = etPrimaryV6.text.toString().trim().takeIf { it.isNotBlank() },
                    secondaryV6 = etSecondaryV6.text.toString().trim().takeIf { it.isNotBlank() },
                    description = if (cbNextDns.isChecked) "NextDNS personnalisé" else "Profil personnalisé",
                    isCustom = true,
                    testUrl = testUrl
                )
                onProfileCreated(profile)
                dismiss()
            }
        }
        btnCancel.setOnClickListener { dismiss() }
    }

    private fun validateInput(): Boolean {
        if (etName.text.isBlank()) {
            Toast.makeText(context, context.getString(R.string.name_required), Toast.LENGTH_SHORT).show()
            return false
        }
        if (cbNextDns.isChecked) {
            if (etNextDnsId.text.isBlank()) {
                Toast.makeText(context, context.getString(R.string.nextdns_id_required), Toast.LENGTH_SHORT).show()
                return false
            }
        } else {
            if (etPrimary.text.isBlank()) {
                Toast.makeText(context, context.getString(R.string.primary_dns_required), Toast.LENGTH_SHORT).show()
                return false
            }
        }
        return true
    }
}
