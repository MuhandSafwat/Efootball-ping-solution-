package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.model.PingServer
import com.example.utils.PingUtility
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

class PingOverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "efootball_ping_service_channel"
        const val NOTIFICATION_ID = 4132
        
        // Static state to allow Compose UI to check service status
        var isRunning = false
            private set
            
        // Current measured ping & connected server info
        val currentPingFlow = MutableStateFlow<Int?>(null)
        val activeServerNameFlow = MutableStateFlow("")
        val activeServerIdFlow = MutableStateFlow("")
        val activeServerHostFlow = MutableStateFlow("")
        val lowRamModeFlow = MutableStateFlow(true)

        fun startService(context: Context, serverId: String, serverName: String, serverHost: String) {
            val intent = Intent(context, PingOverlayService::class.java).apply {
                putExtra("SERVER_ID", serverId)
                putExtra("SERVER_NAME", serverName)
                putExtra("SERVER_HOST", serverHost)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e("PingOverlayService", "Failed to start foreground service: ${e.message}")
            }
        }

        fun stopService(context: Context) {
            try {
                val intent = Intent(context, PingOverlayService::class.java)
                context.stopService(intent)
            } catch (e: Exception) {
                Log.e("PingOverlayService", "Failed to stop service: ${e.message}")
            }
        }
    }

    private lateinit var windowManager: WindowManager
    private var composeView: ComposeView? = null
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var lifecycleOwner: ServiceLifecycleOwner
    
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pingJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        // Initialize reactive pref value
        val prefs = getSharedPreferences("efootball_ping_prefs", Context.MODE_PRIVATE)
        lowRamModeFlow.value = prefs.getBoolean("low_ram_mode", true)
        
        // Setup Service custom lifecycle so Jetpack Compose runs flawlessly
        lifecycleOwner = ServiceLifecycleOwner()
        lifecycleOwner.onCreate()
        
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        setupOverlayView()
        lifecycleOwner.onStart()
        lifecycleOwner.onResume()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.overlay_service_title),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.overlay_service_desc)
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.overlay_service_title))
            .setContentText(getString(R.string.overlay_service_desc))
            .setSmallIcon(android.R.drawable.stat_sys_phone_call) // Configured standard small icon
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("efootball_ping_prefs", Context.MODE_PRIVATE)
        lowRamModeFlow.value = prefs.getBoolean("low_ram_mode", true)

        intent?.let {
            val serverId = it.getStringExtra("SERVER_ID") ?: ""
            val serverName = it.getStringExtra("SERVER_NAME") ?: "Auto-Detect"
            val serverHost = it.getStringExtra("SERVER_HOST") ?: "dynamodb.me-south-1.amazonaws.com"

            activeServerIdFlow.value = serverId
            activeServerNameFlow.value = serverName
            activeServerHostFlow.value = serverHost

            startPingTracking(serverHost)
        }
        return START_NOT_STICKY
    }

    private fun startPingTracking(host: String) {
        pingJob?.cancel()
        val prefs = getSharedPreferences("efootball_ping_prefs", Context.MODE_PRIVATE)
        val isLowRam = prefs.getBoolean("low_ram_mode", true)
        val delayMillis = if (isLowRam) 4000L else 2000L

        pingJob = serviceScope.launch {
            while (isActive) {
                val measuredPing = PingUtility.measureTcpPing(host)
                currentPingFlow.value = measuredPing
                delay(delayMillis) // Throttled to 4 seconds in Low RAM mode to conserve CPU/RAM
            }
        }
    }

    private fun setupOverlayView() {
        // Layout parameters optimized for drag capability + translucent overlays
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100 // initial position
            y = 200
        }

        val viewModelStoreOwner = object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = ViewModelStore()
        }
        
        composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(viewModelStoreOwner)

            setContent {
                MaterialTheme {
                    FloatingOverlayLayout(
                        onDrag = { dx, dy ->
                            params.x = (params.x + dx).coerceAtLeast(0)
                            params.y = (params.y + dy).coerceAtLeast(0)
                            try {
                                windowManager.updateViewLayout(this@apply, params)
                            } catch (ignored: Exception) {}
                        },
                        onClose = {
                            stopSelf()
                        }
                    )
                }
            }
        }

        try {
            windowManager.addView(composeView, params)
        } catch (e: Exception) {
            Log.e("PingOverlayService", "Failed to add window overlay view: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        pingJob?.cancel()
        serviceScope.cancel()
        
        composeView?.let {
            try {
                windowManager.removeView(it)
            } catch (ignored: Exception) {}
            try {
                it.disposeComposition()
            } catch (ignored: Exception) {}
        }

        lifecycleOwner.onPause()
        lifecycleOwner.onStop()
        lifecycleOwner.onDestroy()
    }
}

/**
 * Compose Floating UI Layout with drag gestures.
 */
@Composable
fun FloatingOverlayLayout(
    onDrag: (Int, Int) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val ping by PingOverlayService.currentPingFlow.collectAsState()
    val serverName by PingOverlayService.activeServerNameFlow.collectAsState()
    
    val isLowRam by PingOverlayService.lowRamModeFlow.collectAsState()

    var isExpanded by remember { mutableStateOf(false) }

    // Color-coding latency thresholds
    val activeColor = remember(ping) {
        val current = ping
        when {
            current == null -> Color.Gray
            current < 40 -> Color(0xFF4CAF50) // Bright Green
            current < 81 -> Color(0xFFFFEB3B)  // Vivid Yellow
            current < 121 -> Color(0xFFFF9800) // Soft Orange
            else -> Color(0xFFF44336)          // Bright Red
        }
    }

    if (isLowRam) {
        // ULTRA LIGHT LOW RAM MINI BADGE with custom golden Messi logo and status glow dot
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x.toInt(), dragAmount.y.toInt())
                    }
                }
                .background(Color(0xE60F1524), RoundedCornerShape(20.dp))
                .border(1.dp, Color(0xFFFACC15).copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                .padding(horizontal = 8.dp, vertical = 5.dp)
                .wrapContentSize()
        ) {
            Image(
                painter = painterResource(id = R.drawable.app_ping_icon_1779359445265),
                contentDescription = "Logo",
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(activeColor, CircleShape)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (ping != null) "${ping}ms" else "...",
                color = Color.White,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                style = LocalTextStyle.current.copy(
                    shadow = Shadow(
                        color = Color.Black,
                        offset = Offset(1f, 1f),
                        blurRadius = 3f
                    )
                )
            )
            Spacer(modifier = Modifier.width(6.dp))
            HorizontalDivider(
                color = Color.Gray.copy(alpha = 0.3f),
                modifier = Modifier
                    .height(11.dp)
                    .width(1.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clickable { onClose() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color.LightGray,
                    modifier = Modifier.size(10.dp)
                )
            }
        }
    } else {
        // NORMAL EXPANDABLE GRAPHIC OVERLAY
        Box(
            modifier = Modifier
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x.toInt(), dragAmount.y.toInt())
                    }
                }
                .background(Color.Transparent, RoundedCornerShape(16.dp))
                .padding(8.dp)
                .wrapContentSize()
        ) {
            if (!isExpanded) {
                // COMPACT/MINIMIZED MODE with circular logo + status glow
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xE60F1524))
                        .border(1.dp, Color(0xFFFACC15).copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                        .clickable { isExpanded = true }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.app_ping_icon_1779359445265),
                        contentDescription = "Logo",
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    // Glow indicator dot
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(activeColor, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (ping != null) "${ping}ms" else "...",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        style = LocalTextStyle.current.copy(
                            shadow = Shadow(
                                color = Color.Black,
                                offset = Offset(1f, 1f),
                                blurRadius = 3f
                            )
                        )
                    )
                }
            } else {
                // DETAILED EXPANDED MODE with luxury headers and centered logo branding
                Column(
                    modifier = Modifier
                        .width(180.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xF20F1524))
                        .border(1.5.dp, Color(0xFFFACC15), RoundedCornerShape(16.dp))
                        .padding(10.dp)
                ) {
                    // Header with actions & Logo!
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                painter = painterResource(id = R.drawable.app_ping_icon_1779359445265),
                                contentDescription = "Logo",
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .border(1.dp, Color(0xFFFACC15), CircleShape)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "eFootball Ping",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                style = LocalTextStyle.current.copy(
                                    shadow = Shadow(
                                        color = Color.Black,
                                        offset = Offset(1f, 1f),
                                        blurRadius = 3f
                                    )
                                )
                            )
                        }
                        Row {
                            IconButton(
                                onClick = { isExpanded = false },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowUp,
                                    contentDescription = "Collapse",
                                    tint = Color.LightGray,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            IconButton(
                                onClick = onClose,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = Color(0xFFF44336),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                    
                    HorizontalDivider(color = Color.Gray.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 4.dp))
                    
                    // Huge dynamic Ping Text
                    Text(
                        text = if (ping != null) "${ping} ms" else "Ping...",
                        color = activeColor,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        style = LocalTextStyle.current.copy(
                            shadow = Shadow(
                                color = Color.Black,
                                offset = Offset(1.5f, 1.5f),
                                blurRadius = 5f
                            )
                        )
                    )

                    // Server region label
                    Text(
                        text = serverName,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth(),
                        style = LocalTextStyle.current.copy(
                            shadow = Shadow(
                                color = Color.Black,
                                offset = Offset(1f, 1f),
                                blurRadius = 3f
                            )
                        )
                    )
                    
                    Spacer(modifier = Modifier.height(2.dp))
                    
                    Text(
                        text = when {
                            ping == null -> "Connecting..."
                            ping!! < 40 -> "Excellent Connection"
                            ping!! < 81 -> "Good Connection"
                            ping!! < 121 -> "Fair Latency"
                            else -> "Poor Connection"
                        },
                        color = Color.Gray,
                        fontSize = 9.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                        style = LocalTextStyle.current.copy(
                            shadow = Shadow(
                                color = Color.Black,
                                offset = Offset(1f, 1f),
                                blurRadius = 3f
                            )
                        )
                    )
                }
            }
        }
    }
}

/**
 * Pristine, fully-formed implementation of an Android LifecycleOwner and SavedStateRegistryOwner.
 * This completely satisfies Android lifecycle architectures so Compose views can exist inside background Services.
 */
class ServiceLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    init {
        lifecycleRegistry.currentState = Lifecycle.State.INITIALIZED
    }

    fun onCreate() {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun onStart() {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    fun onResume() {
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun onPause() {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    fun onStop() {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
}
