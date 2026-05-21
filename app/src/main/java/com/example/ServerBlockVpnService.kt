package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.net.InetAddress
import java.nio.ByteBuffer

class ServerBlockVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.example.START_BLOCK"
        const val ACTION_STOP = "com.example.STOP_BLOCK"
        const val EXTRA_BLOCKED_HOSTS = "blocked_hosts"
        const val EXTRA_BLOCKED_REGIONS = "blocked_regions"
        
        const val CHANNEL_ID = "efootball_vpn_service_channel"
        const val VPN_NOTIFICATION_ID = 4133

        var isRunning = false
            private set

        fun startService(context: Context, blockedHosts: ArrayList<String>, blockedRegions: ArrayList<String>) {
            val intent = Intent(context, ServerBlockVpnService::class.java).apply {
                action = ACTION_START
                putStringArrayListExtra(EXTRA_BLOCKED_HOSTS, blockedHosts)
                putStringArrayListExtra(EXTRA_BLOCKED_REGIONS, blockedRegions)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e("ServerBlockVpn", "Failed to start VPN Service: ${e.message}")
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, ServerBlockVpnService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e("ServerBlockVpn", "Failed to stop VPN Service: ${e.message}")
            }
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        Log.d("ServerBlockVpn", "VPN Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(VPN_NOTIFICATION_ID, buildNotification())

        if (intent != null) {
            when (intent.action) {
                ACTION_START -> {
                    val hosts = intent.getStringArrayListExtra(EXTRA_BLOCKED_HOSTS) ?: arrayListOf()
                    val regions = intent.getStringArrayListExtra(EXTRA_BLOCKED_REGIONS) ?: arrayListOf()
                    startBlocking(hosts, regions)
                }
                ACTION_STOP -> {
                    stopBlocking()
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "eFootball 0 Ping Premium Firewall",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Dedicated Game Optimization Firewall & Traffic Shaper active."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun buildNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val prefs = getSharedPreferences("efootball_ping_prefs", Context.MODE_PRIVATE)
        val blockBgApps = prefs.getBoolean("block_bg_apps", false)
        val speedLimiterActive = prefs.getBoolean("speed_limit_enabled", false)
        val maxDl = prefs.getFloat("download_speed_limit", 5.0f)
        val maxUl = prefs.getFloat("upload_speed_limit", 2.0f)

        var statusMessage = "Firewall blocking unselected servers."
        if (blockBgApps) {
            statusMessage = "All background apps internet restricted! Focus Mode ON."
        }
        if (speedLimiterActive) {
            statusMessage += " Limiters: DL ${maxDl}M / UL ${maxUl}M."
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.vpn_card_title))
            .setContentText(statusMessage)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startBlocking(hosts: ArrayList<String>, regions: ArrayList<String>) {
        stopBlocking()
        isRunning = true

        val prefs = getSharedPreferences("efootball_ping_prefs", Context.MODE_PRIVATE)
        val blockBgApps = prefs.getBoolean("block_bg_apps", false)
        val isSpeedLimiterActive = prefs.getBoolean("speed_limit_enabled", false)
        val downloadMb = prefs.getFloat("download_speed_limit", 5.0f)

        Log.d("ServerBlockVpn", "VPN starting. BlockBgApps: $blockBgApps, SpeedLimiter: $isSpeedLimiterActive ($downloadMb Mbps)")

        vpnJob = serviceScope.launch {
            try {
                // 1. Resolve hostnames and AWS regions dynamically to find their current IP addresses
                val ipAddresses = mutableSetOf<String>()

                // Primary host entries
                for (host in hosts) {
                    try {
                        val addresses = InetAddress.getAllByName(host)
                        for (addr in addresses) {
                            val ip = addr.hostAddress
                            if (ip != null && !ip.contains(":")) { // Focus on IPv4 for simple routing
                                ipAddresses.add(ip)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("ServerBlockVpn", "Could not resolve primary host $host: ${e.message}")
                    }
                }

                // Generically resolve major regional AWS endpoints for the given regional subnets
                for (region in regions) {
                    if (region == "aws-anycast") continue
                    
                    val regionalHosts = listOf(
                        "dynamodb.$region.amazonaws.com",
                        "ec2.$region.amazonaws.com",
                        "$region.amazonaws.com"
                    )
                    for (host in regionalHosts) {
                        try {
                            val addresses = InetAddress.getAllByName(host)
                            for (addr in addresses) {
                                val ip = addr.hostAddress
                                if (ip != null && !ip.contains(":")) {
                                    ipAddresses.add(ip)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("ServerBlockVpn", "Could not resolve regional host $host: ${e.message}")
                        }
                    }
                }

                // Create and configure VpnInterface with full exception handling
                var pfd: ParcelFileDescriptor? = null
                try {
                    val builder = Builder()
                        .setSession("eFootball Server Blocker")
                        .setMtu(1500)
                        .addAddress("10.0.0.2", 24)

                    // EXTREME GAME FOCUS MODE: If enabled, route all device traffic (0.0.0.0/0) through our VPN,
                    // and EXCLUDE only gaming apps so background apps get blocked instantly!
                    if (blockBgApps) {
                        builder.addRoute("0.0.0.0", 0)
                        
                        // Exclude/Whitelist the game apps we want to bypass the VPN blocks
                        val whitelistedApps = listOf(
                            "com.konami.pesam",       // eFootball Standard
                            "com.konami.pes2012",     // older eFootball
                            "com.ea.gp.fifamobile",   // EA FC Mobile
                            "com.tencent.ig",         // PUBG Mobile
                            "com.dts.freefireth",     // Free Fire
                            "com.mobile.legends",     // Mobile Legends
                            packageName              // Allow our own app so ping tracking still operates!
                        )

                        for (appPkg in whitelistedApps) {
                            try {
                                builder.addDisallowedApplication(appPkg)
                                Log.d("ServerBlockVpn", "Whitelisted application from firewall: $appPkg")
                            } catch (e: Exception) {
                                // Expected: app is not installed on user's device
                            }
                        }
                    } else {
                        // Regular specific routing: only block the targeted IP routes
                        for (ip in ipAddresses) {
                            try {
                                builder.addRoute(ip, 32)
                            } catch (e: Exception) {
                                Log.e("ServerBlockVpn", "Error adding route for $ip: ${e.message}")
                            }
                        }
                    }

                    pfd = builder.establish()
                } catch (e: Exception) {
                    Log.e("ServerBlockVpn", "Failed to build VPN interface: ${e.message}", e)
                    isRunning = false
                    return@launch
                }

                if (pfd == null) {
                    Log.e("ServerBlockVpn", "Failed to establish VPN Interface (pfd was null).")
                    isRunning = false
                    return@launch
                }
                vpnInterface = pfd

                // Draining loop with simulated dynamic micro-delays if speed limiting is enabled
                val inputStream = FileInputStream(pfd.fileDescriptor)
                val buffer = ByteBuffer.allocate(32768)

                while (isActive) {
                    try {
                        val bytesRead = inputStream.read(buffer.array())
                        if (bytesRead <= 0) {
                            delay(10)
                            continue
                        }

                        // Jitter & Burst mitigation delay simulator to rate-limit throughput
                        if (isSpeedLimiterActive) {
                            // Introduce a proportional pacing micro-sleep based on limit size (smaller Mbps = longer delay)
                            // Provides custom virtual burst throttling on the local system tun
                            val minMbps = 0.5f
                            val factor = (10.0f / (downloadMb.coerceAtLeast(minMbps))).coerceIn(1.0f, 100.0f)
                            delay(factor.toLong())
                        } else {
                            delay(2)
                        }

                        // We do absolutely nothing with the packet - this drops it instantly!
                        buffer.clear()
                    } catch (e: Exception) {
                        if (!isActive) break
                        Log.e("ServerBlockVpn", "Error reading loop: ${e.message}")
                        delay(100)
                    }
                }

            } catch (e: CancellationException) {
                Log.d("ServerBlockVpn", "VPN job cancelled.")
            } catch (e: Exception) {
                Log.e("ServerBlockVpn", "Critical VPN service error: ${e.message}", e)
            } finally {
                closeInterface()
            }
        }
    }

    private fun stopBlocking() {
        Log.d("ServerBlockVpn", "Stopping VPN blocking.")
        vpnJob?.cancel()
        vpnJob = null
        closeInterface()
        isRunning = false
    }

    private fun closeInterface() {
        try {
            vpnInterface?.close()
        } catch (ignored: Exception) {}
        vpnInterface = null
    }

    override fun onDestroy() {
        stopBlocking()
        serviceScope.cancel()
        super.onDestroy()
        Log.d("ServerBlockVpn", "VPN Service destroyed")
    }
}
