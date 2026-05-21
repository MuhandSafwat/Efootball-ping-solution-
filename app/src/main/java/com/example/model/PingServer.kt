package com.example.model

import android.content.Context
import com.example.R

data class PingServer(
    val id: String,
    val nameResId: Int,
    val awsRegion: String,
    val pingTargetHost: String,
    var currentPing: Int? = null,
    var isTesting: Boolean = false,
    var isBlocked: Boolean = false
) {
    fun getDisplayName(context: Context): String {
        return context.getString(nameResId)
    }

    fun getStatusLabel(context: Context): String {
        val ping = currentPing ?: return "..."
        return when {
            ping < 40 -> context.getString(R.string.ping_excellent)
            ping < 81 -> context.getString(R.string.ping_good)
            ping < 121 -> context.getString(R.string.ping_fair)
            else -> context.getString(R.string.ping_poor)
        }
    }
}
