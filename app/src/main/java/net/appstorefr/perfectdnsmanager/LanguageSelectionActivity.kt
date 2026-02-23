package net.appstorefr.perfectdnsmanager

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import net.appstorefr.perfectdnsmanager.util.LocaleHelper

class LanguageSelectionActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val language = prefs.getString("language", null)
        val forceShow = intent.getBooleanExtra("FORCE_SHOW", false)

        // Si langue déjà choisie et pas forcé (ex: depuis Settings), aller directement au MainActivity
        if (language != null && !forceShow) {
            startMainActivity()
            return
        }

        setContentView(R.layout.activity_language_selection)

        val btnFrancais: Button = findViewById(R.id.btnFrancais)
        val btnEnglish: Button = findViewById(R.id.btnEnglish)

        btnFrancais.requestFocus()
        btnFrancais.setOnClickListener { saveLanguageAndStart("fr") }
        btnEnglish.setOnClickListener { saveLanguageAndStart("en") }
    }

    private fun saveLanguageAndStart(langCode: String) {
        prefs.edit().putString("language", langCode).apply()
        startMainActivity()
    }

    private fun startMainActivity() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }
}
