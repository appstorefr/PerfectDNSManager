package net.appstorefr.perfectdnsmanager.util

import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class EncryptedSharer {

    companion object {
        private const val TAG = "EncryptedSharer"
        private const val AES_KEY_SIZE = 256
        private const val GCM_IV_SIZE = 12
        private const val GCM_TAG_SIZE = 128

        data class UploadResult(
            val shortCode: String,
            val decryptionKey: String,
            val fullUrl: String,
            val fileUrl: String
        )

        /**
         * Encrypt content, upload to tmpfiles.org, shorten via is.gd.
         * Content is AES-256-GCM encrypted before upload.
         * @param content The text content to share
         * @param fileName The filename for the upload
         * @param expiresIn Ignored (tmpfiles.org = 60 min auto), kept for API compat
         * @return UploadResult with short code and decryption key
         */
        fun encryptAndUpload(content: String, fileName: String = "data.enc", expiresIn: String = "1h"): UploadResult {
            // 1. Generate AES-256 key
            val keyGen = KeyGenerator.getInstance("AES")
            keyGen.init(AES_KEY_SIZE)
            val secretKey = keyGen.generateKey()
            val keyBase64 = Base64.encodeToString(secretKey.encoded, Base64.URL_SAFE or Base64.NO_WRAP)

            // 2. Encrypt with AES-256-GCM
            val iv = ByteArray(GCM_IV_SIZE)
            SecureRandom().nextBytes(iv)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_SIZE, iv))
            val encrypted = cipher.doFinal(content.toByteArray(Charsets.UTF_8))

            // 3. Combine IV + encrypted data
            val combined = iv + encrypted
            val encryptedBase64 = Base64.encodeToString(combined, Base64.NO_WRAP)

            // 4. Upload to tmpfiles.org (encrypted content)
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName,
                    encryptedBase64.toByteArray().toRequestBody("application/octet-stream".toMediaType()))
                .build()

            val uploadRequest = Request.Builder()
                .url("https://tmpfiles.org/api/v1/upload")
                .post(body)
                .build()

            Log.d(TAG, "Uploading ${encryptedBase64.length} chars to tmpfiles.org")
            val uploadResponse = client.newCall(uploadRequest).execute()
            val responseBody = uploadResponse.body?.string()?.trim() ?: ""
            uploadResponse.close()
            Log.d(TAG, "tmpfiles response: $responseBody")

            val json = JSONObject(responseBody)
            if (json.optString("status") != "success") {
                throw Exception("Upload failed: $responseBody")
            }
            // tmpfiles.org returns page URL, need /dl/ for direct download
            val pageUrl = json.getJSONObject("data").getString("url")
            val fileUrl = pageUrl.replace("tmpfiles.org/", "tmpfiles.org/dl/")
                .replace("http://", "https://")
            Log.d(TAG, "tmpfiles link: $fileUrl")

            // 5. Build decrypt page URL with file URL + key in fragment
            val decryptPageUrl = "https://pdm.appstorefr.net/decrypt.html#${java.net.URLEncoder.encode(fileUrl, "UTF-8")}|$keyBase64"
            Log.d(TAG, "Decrypt page URL: $decryptPageUrl")

            // 6. Shorten via is.gd with numeric code
            Log.d(TAG, "Shortening URL via is.gd...")
            val shortCode = shortenUrl(decryptPageUrl, client)
            Log.d(TAG, "Short code: $shortCode")

            return UploadResult(
                shortCode = shortCode,
                decryptionKey = keyBase64,
                fullUrl = decryptPageUrl,
                fileUrl = fileUrl
            )
        }

        /**
         * Download from short URL, decrypt and return content.
         * @param shortCode The is.gd short code (e.g. "abc123" or full URL)
         * @return Decrypted content string
         */
        fun downloadAndDecrypt(shortCode: String): String {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(false)
                .build()

            // 1. Resolve is.gd short URL to get download URL + key
            val isgdUrl = if (shortCode.startsWith("http")) shortCode
                else "https://is.gd/$shortCode"

            val expandRequest = Request.Builder()
                .url("https://is.gd/forward.php?format=simple&shorturl=$isgdUrl")
                .build()
            val expandResponse = client.newCall(expandRequest).execute()
            val expandedUrl = expandResponse.body?.string()?.trim() ?: ""
            expandResponse.close()

            if (expandedUrl.isBlank() || !expandedUrl.startsWith("http")) {
                throw Exception("Cannot resolve short URL: $shortCode")
            }

            // 2. Extract key from fragment (#)
            val parts = expandedUrl.split("#", limit = 2)
            if (parts.size < 2) throw Exception("No decryption key in URL")
            val fileUrl = parts[0]
            val keyBase64 = parts[1]

            // 3. Download encrypted data
            val dlClient = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()

            val downloadRequest = Request.Builder()
                .url(fileUrl)
                .build()
            val downloadResponse = dlClient.newCall(downloadRequest).execute()
            val encryptedBase64 = downloadResponse.body?.string() ?: ""
            downloadResponse.close()

            if (encryptedBase64.isBlank()) throw Exception("Empty response from file server")

            // 4. Decrypt
            return decrypt(encryptedBase64.trim(), keyBase64)
        }

        /**
         * Decrypt content given the encrypted base64 data and the key.
         */
        fun decrypt(encryptedBase64: String, keyBase64: String): String {
            val keyBytes = Base64.decode(keyBase64, Base64.URL_SAFE or Base64.NO_WRAP)
            val secretKey: SecretKey = SecretKeySpec(keyBytes, "AES")

            val combined = Base64.decode(encryptedBase64, Base64.NO_WRAP)
            val iv = combined.copyOfRange(0, GCM_IV_SIZE)
            val encrypted = combined.copyOfRange(GCM_IV_SIZE, combined.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_SIZE, iv))
            val decrypted = cipher.doFinal(encrypted)

            return String(decrypted, Charsets.UTF_8)
        }

        /**
         * Shorten a URL via is.gd with a numeric code (easy to type on TV remote).
         * Tries up to 5 times with different random numeric codes.
         * Falls back to auto-generated code if all attempts fail.
         */
        private fun shortenUrl(url: String, client: OkHttpClient): String {
            val encoded = java.net.URLEncoder.encode(url, "UTF-8")
            val random = java.util.Random()

            // Try numeric custom codes (6 digits)
            for (attempt in 1..5) {
                val numericCode = (100000 + random.nextInt(900000)).toString()
                val request = Request.Builder()
                    .url("https://is.gd/create.php?format=simple&url=$encoded&shorturl=$numericCode")
                    .build()
                try {
                    val response = client.newCall(request).execute()
                    val shortUrl = response.body?.string()?.trim() ?: ""
                    response.close()

                    if (shortUrl.isNotBlank() && !shortUrl.startsWith("Error")) {
                        return shortUrl.substringAfterLast("/")
                    }
                    Log.d(TAG, "is.gd attempt $attempt ($numericCode) failed: $shortUrl")
                } catch (e: Exception) {
                    Log.d(TAG, "is.gd attempt $attempt ($numericCode) error: ${e.message}")
                }
            }

            // Fallback: let is.gd generate its own code
            Log.d(TAG, "Falling back to auto-generated is.gd code")
            val request = Request.Builder()
                .url("https://is.gd/create.php?format=simple&url=$encoded")
                .build()
            val response = client.newCall(request).execute()
            val shortUrl = response.body?.string()?.trim() ?: ""
            response.close()

            if (shortUrl.isBlank() || shortUrl.startsWith("Error")) {
                throw Exception("is.gd shortening failed: $shortUrl")
            }

            return shortUrl.substringAfterLast("/")
        }
    }
}
