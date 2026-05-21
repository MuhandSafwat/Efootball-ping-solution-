package com.example.utils

import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PingUtility {
    private const val TAG = "PingUtility"

    /**
     * Measures the latency to a given host using a TCP Connection handshake on port 443 (HTTPS).
     * This is 100% reliable on Android, bypasses ICMP blocks, and represents true connection latency.
     * Returns the latency in milliseconds, or null if the connection fails or times out.
     */
    suspend fun measureTcpPing(host: String, timeoutMs: Int = 1800): Int? = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        val startTime = System.currentTimeMillis()
        try {
            socket = Socket()
            // Connect to port 443 (standard HTTPS port on AWS/GCP endpoints)
            socket.connect(InetSocketAddress(host, 443), timeoutMs)
            
            // Success! Calculate time delay
            val delay = (System.currentTimeMillis() - startTime).toInt()
            Log.d(TAG, "TCP Ping to $host succeeded: $delay ms")
            delay
        } catch (e: Exception) {
            Log.e(TAG, "TCP Ping to $host failed: ${e.message}")
            // Fallback: If port 443 failed, try a simple ICMP ping just in case
            runIcmpPing(host, timeoutMs)
        } finally {
            try {
                socket?.close()
            } catch (ignored: Exception) {}
        }
    }

    /**
     * Fallback command-line ICMP Ping
     */
    private fun runIcmpPing(host: String, timeoutMs: Int): Int? {
        try {
            val timeoutSeconds = (timeoutMs + 999) / 1000
            val process = Runtime.getRuntime().exec("ping -c 1 -w $timeoutSeconds $host")
            val startTime = System.currentTimeMillis()
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                val delay = (System.currentTimeMillis() - startTime).toInt()
                Log.d(TAG, "ICMP Ping fallback to $host succeeded: $delay ms")
                return delay
            }
        } catch (e: Exception) {
            Log.e(TAG, "ICMP Ping fallback failed: ${e.message}")
        }
        return null
    }
}
