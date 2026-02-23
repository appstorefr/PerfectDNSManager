package net.appstorefr.perfectdnsmanager.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.github.kittinunf.fuel.Fuel
import com.github.kittinunf.result.Result
import net.appstorefr.perfectdnsmanager.R
import org.json.JSONObject
import java.io.File

class UpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "UpdateManager"
    }

    fun checkForUpdateGitHub(githubRepo: String, currentVersion: String) {
        val apiUrl = "https://api.github.com/repos/$githubRepo/releases/latest"
        Log.i(TAG, "Vérification mise à jour GitHub: $apiUrl")

        Fuel.get(apiUrl)
            .header("Accept", "application/vnd.github.v3+json")
            .responseString { _, _, result ->
                when (result) {
                    is Result.Success -> {
                        try {
                            val json = JSONObject(result.get())
                            val tagName = json.getString("tag_name").removePrefix("v")
                            Log.i(TAG, "Version actuelle: $currentVersion, GitHub: $tagName")

                            if (tagName != currentVersion) {
                                showToastOnMainThread(context.getString(R.string.update_available, tagName))
                                val assets = json.getJSONArray("assets")
                                // Chercher latest.apk en priorité, sinon premier .apk trouvé
                                var apkUrl: String? = null
                                for (i in 0 until assets.length()) {
                                    val asset = assets.getJSONObject(i)
                                    val name = asset.getString("name")
                                    if (name == "latest.apk") {
                                        apkUrl = asset.getString("browser_download_url")
                                        break
                                    }
                                    if (apkUrl == null && name.endsWith(".apk")) {
                                        apkUrl = asset.getString("browser_download_url")
                                    }
                                }
                                if (apkUrl != null) {
                                    downloadAndInstallUpdate(apkUrl)
                                }
                            } else {
                                showToastOnMainThread(context.getString(R.string.app_up_to_date))
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Erreur parsing GitHub API", e)
                            showToastOnMainThread(context.getString(R.string.update_check_error))
                        }
                    }
                    is Result.Failure -> {
                        Log.e(TAG, "Erreur vérification MAJ GitHub", result.getException())
                        showToastOnMainThread(context.getString(R.string.update_check_error))
                    }
                }
            }
    }

    private fun downloadAndInstallUpdate(apkUrl: String) {
        showToastOnMainThread(context.getString(R.string.update_downloading))
        val destination = File(context.cacheDir, "update.apk")

        Fuel.download(apkUrl).fileDestination { _, _ -> destination }.response { _, _, result ->
            when (result) {
                is Result.Success -> {
                    Log.i(TAG, "Téléchargement terminé: ${destination.absolutePath}")
                    installApk(destination)
                }
                is Result.Failure -> {
                    Log.e(TAG, "Erreur téléchargement", result.getException())
                    showToastOnMainThread(context.getString(R.string.update_download_error))
                }
            }
        }
    }

    private fun installApk(apkFile: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Erreur installation APK", e)
            showToastOnMainThread(context.getString(R.string.update_install_error))
        }
    }

    private fun showToastOnMainThread(message: String) {
        if (context is Activity) {
            context.runOnUiThread {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
