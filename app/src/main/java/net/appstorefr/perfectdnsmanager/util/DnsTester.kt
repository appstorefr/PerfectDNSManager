package net.appstorefr.perfectdnsmanager.util

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

object DnsTester {

    private const val TAG = "DnsTester"

    data class DnsResult(val ip: String, val isBlocked: Boolean)

    fun execute(server: String, domain: String): DnsResult? {
        return try {
            val socket = DatagramSocket()
            socket.soTimeout = 5000

            // 1. Build Query
            val queryBuffer = buildQuery(domain)

            // 2. Send Query
            val serverAddress = InetAddress.getByName(server)
            val requestPacket = DatagramPacket(queryBuffer.array(), queryBuffer.limit(), serverAddress, 53)
            socket.send(requestPacket)

            // 3. Receive Response
            val responseBytes = ByteArray(1024)
            val responsePacket = DatagramPacket(responseBytes, responseBytes.size)
            socket.receive(responsePacket)

            // 4. Parse Response
            val resultIp = parseResponse(responsePacket.data, responsePacket.length)

            socket.close()

            if (resultIp != null) {
                val isBlocked = resultIp == "127.0.0.1" || resultIp == "54.246.190.12"
                DnsResult(resultIp, isBlocked)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "DNS test failed for server $server, domain $domain", e)
            null
        }
    }

    private fun buildQuery(domain: String): ByteBuffer {
        val buffer = ByteBuffer.allocate(512)
        // --- Header ---
        buffer.putShort(0x1234) // Transaction ID
        buffer.putShort(0x0100) // Flags (Standard Query)
        buffer.putShort(1)      // Questions
        buffer.putShort(0)      // Answer RRs
        buffer.putShort(0)      // Authority RRs
        buffer.putShort(0)      // Additional RRs

        // --- Question ---
        domain.split(".").forEach {
            buffer.put(it.length.toByte())
            buffer.put(it.toByteArray())
        }
        buffer.put(0.toByte()) // End of domain name
        buffer.putShort(1)     // Type: A (Host Address)
        buffer.putShort(1)     // Class: IN (Internet)

        buffer.flip()
        return buffer
    }

    /**
     * Mesure la latence d'un serveur DNS UDP standard (port 53).
     * @return latence en millisecondes, ou null si erreur
     */
    fun measureLatency(server: String, domain: String = "google.com"): Long? {
        return try {
            val socket = DatagramSocket()
            socket.soTimeout = 5000
            val queryBuffer = buildQuery(domain)
            val serverAddress = InetAddress.getByName(server)
            val requestPacket = DatagramPacket(queryBuffer.array(), queryBuffer.limit(), serverAddress, 53)

            val start = System.currentTimeMillis()
            socket.send(requestPacket)
            val responseBytes = ByteArray(1024)
            val responsePacket = DatagramPacket(responseBytes, responseBytes.size)
            socket.receive(responsePacket)
            val elapsed = System.currentTimeMillis() - start

            socket.close()
            elapsed
        } catch (e: Exception) {
            Log.e(TAG, "Latency test failed for $server", e)
            null
        }
    }

    /** Client HTTP réutilisable pour les tests DoH (évite le coût TCP+TLS à chaque test) */
    private val dohClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Mesure la latence d'un serveur DNS over HTTPS (DoH).
     * @param client client HTTP à utiliser (réutiliser pour bénéficier du pool de connexions)
     * @return latence en millisecondes, ou null si erreur
     */
    fun measureDohLatency(url: String, domain: String = "google.com", client: OkHttpClient = dohClient): Long? {
        return try {
            val queryBuffer = buildQuery(domain)
            val queryBytes = queryBuffer.array().copyOf(queryBuffer.limit())
            val body = queryBytes.toRequestBody("application/dns-message".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(body)
                .header("Accept", "application/dns-message")
                .build()

            val start = System.currentTimeMillis()
            val response = client.newCall(request).execute()
            val elapsed = System.currentTimeMillis() - start
            response.close()

            if (response.isSuccessful) elapsed else null
        } catch (e: Exception) {
            Log.e(TAG, "DoH latency test failed for $url", e)
            null
        }
    }

    private fun parseResponse(data: ByteArray, length: Int): String? {
        try {
            val buffer = ByteBuffer.wrap(data, 0, length)
            // Skip header (12 bytes)
            buffer.position(12)

            // Skip question section
            var labels = 0
            do {
                labels = buffer.get().toInt()
                if (labels > 0) {
                    buffer.position(buffer.position() + labels)
                }
            } while (labels != 0)
            buffer.position(buffer.position() + 4) // Skip QTYPE and QCLASS

            // --- Answer Section ---
            while (buffer.hasRemaining()) {
                // Check for pointer (C0)
                val name = buffer.getShort()
                if ((name.toInt() and 0xC000) == 0xC000) {
                    val type = buffer.getShort()
                    buffer.getShort() // Class
                    buffer.getInt()   // TTL
                    val rdLength = buffer.getShort()

                    if (type.toInt() == 1 && rdLength.toInt() == 4) { // A record
                        val ipBytes = ByteArray(4)
                        buffer.get(ipBytes)
                        return ipBytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
                    }
                }
                // In a simple parser, we stop at the first A record.
                break
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse DNS response", e)
        }
        return null
    }
}
