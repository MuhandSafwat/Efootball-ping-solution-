package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.PingServer
import com.example.ui.theme.BorderSlate
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.GameBlue
import com.example.ui.theme.GamePrimaryYellow
import com.example.ui.theme.GameSecondaryGreen
import com.example.ui.theme.MyApplicationTheme
import com.example.utils.PingUtility
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val OVERLAY_PERMISSION_REQ_CODE = 4132

    // List of standard Konami/eFootball game server pinging hosts around AWS data centers
    private val initialServers = listOf(
        PingServer("me_bahrain", R.string.server_middle_east, "me-south-1", "dynamodb.me-south-1.amazonaws.com"),
        PingServer("eu_frankfurt", R.string.server_europe_central, "eu-central-1", "dynamodb.eu-central-1.amazonaws.com"),
        PingServer("eu_london", R.string.server_europe_west, "eu-west-2", "dynamodb.eu-west-2.amazonaws.com"),
        PingServer("asia_tokyo", R.string.server_asia_east, "ap-northeast-1", "dynamodb.ap-northeast-1.amazonaws.com"),
        PingServer("asia_singapore", R.string.server_southeast_asia, "ap-southeast-1", "dynamodb.ap-southeast-1.amazonaws.com"),
        PingServer("sa_saopaulo", R.string.server_south_america, "sa-east-1", "dynamodb.sa-east-1.amazonaws.com"),
        PingServer("us_virginia", R.string.server_north_america, "us-east-1", "dynamodb.us-east-1.amazonaws.com"),
        PingServer("ap_sydney", R.string.server_oceania, "ap-southeast-2", "dynamodb.ap-southeast-2.amazonaws.com")
    )

    private fun getBlockedServers(): Set<String> {
        val prefs = getSharedPreferences("efootball_ping_prefs", Context.MODE_PRIVATE)
        return prefs.getStringSet("blocked_servers", emptySet()) ?: emptySet()
    }

    private fun saveBlockedServers(blockedIds: Set<String>) {
        val prefs = getSharedPreferences("efootball_ping_prefs", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("blocked_servers", blockedIds).apply()
    }

    // Reactive states
    private var isOverlayAllowedState = mutableStateOf(false)
    private var isServiceRunningState = mutableStateOf(false)
    private var serverListState = mutableStateOf<List<PingServer>>(initialServers)
    private var selectedServerState = mutableStateOf<PingServer>(initialServers[0])
    private var isTestingInProgressState = mutableStateOf(false)
    private var autoDetectedServerState = mutableStateOf<PingServer?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Initial loader of blocked lists
        val blockedIds = getBlockedServers()
        val mappedServers = initialServers.map {
            it.copy(isBlocked = blockedIds.contains(it.id))
        }
        serverListState.value = mappedServers
        val unblocked = mappedServers.filter { !it.isBlocked }
        selectedServerState.value = unblocked.firstOrNull() ?: mappedServers.first()
        
        checkOverlayPermission()
        isServiceRunningState.value = PingOverlayService.isRunning

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = DarkBackground
                ) { innerPadding ->
                    DashboardScreen(
                        modifier = Modifier.padding(innerPadding),
                        isOverlayAllowed = isOverlayAllowedState.value,
                        isServiceRunning = isServiceRunningState.value,
                        servers = serverListState.value,
                        selectedServer = selectedServerState.value,
                        isTesting = isTestingInProgressState.value,
                        autoDetectedServer = autoDetectedServerState.value,
                        onGrantPermission = { requestOverlayPermission() },
                        onSelectServer = { selectedServerState.value = it },
                        onRunSpeedtest = { runGlobalSpeedtest() },
                        onStartOverlay = { toggleOverlay(true) },
                        onStopOverlay = { toggleOverlay(false) },
                        onToggleBlock = { toggledServer ->
                            val currentBlocked = getBlockedServers().toMutableSet()
                            if (currentBlocked.contains(toggledServer.id)) {
                                currentBlocked.remove(toggledServer.id)
                            } else {
                                currentBlocked.add(toggledServer.id)
                            }
                            saveBlockedServers(currentBlocked)
                            
                            val updatedList = serverListState.value.map {
                                if (it.id == toggledServer.id) {
                                    it.copy(isBlocked = currentBlocked.contains(it.id))
                                } else {
                                    it
                                }
                            }
                            serverListState.value = updatedList
                            
                            // Highlight update
                            val currentSelected = selectedServerState.value
                            if (currentSelected.id == toggledServer.id) {
                                selectedServerState.value = currentSelected.copy(isBlocked = currentBlocked.contains(toggledServer.id))
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkOverlayPermission()
        isServiceRunningState.value = PingOverlayService.isRunning
    }

    private fun checkOverlayPermission() {
        isOverlayAllowedState.value = Settings.canDrawOverlays(this)
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, OVERLAY_PERMISSION_REQ_CODE)
            } catch (e: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    startActivityForResult(intent, OVERLAY_PERMISSION_REQ_CODE)
                } catch (ex: Exception) {
                    Toast.makeText(
                        this,
                        "Device does not support overlay settings / لا يدعم هذا الهاتف إعدادات الظهور فوق التطبيقات",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQ_CODE) {
            checkOverlayPermission()
            if (isOverlayAllowedState.value) {
                Toast.makeText(this, "Overlay permission granted! / تم تفعيل الإذن", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleOverlay(start: Boolean) {
        if (start) {
            if (!Settings.canDrawOverlays(this)) {
                requestOverlayPermission()
                return
            }
            try {
                // Start overlay foreground service safely
                val server = selectedServerState.value
                PingOverlayService.startService(
                    context = this,
                    serverId = server.id,
                    serverName = getString(server.nameResId),
                    serverHost = server.pingTargetHost
                )
                isServiceRunningState.value = true
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to start overlay: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            try {
                // Stop overlay foreground service safely
                PingOverlayService.stopService(this)
                isServiceRunningState.value = false
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to stop service: ${e.message}")
            }
        }
    }

    private fun runGlobalSpeedtest() {
        if (isTestingInProgressState.value) return
        
        isTestingInProgressState.value = true
        lifecycleScope.launch {
            // Create a copy of current list as testing
            val updatedList = serverListState.value.map { it.copy(isTesting = true, currentPing = null) }
            serverListState.value = updatedList
            
            // Execute all socket connection test-rounds concurrently
            val pingDeferreds = updatedList.map { server ->
                async {
                    val pingValue = PingUtility.measureTcpPing(server.pingTargetHost)
                    server.id to pingValue
                }
            }
            
            val results = pingDeferreds.awaitAll().toMap()
            
            // Update results back in our main state list
            val finalizedList = serverListState.value.map { server ->
                server.copy(
                    isTesting = false,
                    currentPing = results[server.id]
                )
            }
            
            serverListState.value = finalizedList
            
            // Auto-detect server with lowest latency (excluding timeouts and BLOCKED servers)
            val healthyServers = finalizedList.filter { it.currentPing != null && !it.isBlocked }
            if (healthyServers.isNotEmpty()) {
                val lowestPingServer = healthyServers.minByOrNull { it.currentPing!! }
                lowestPingServer?.let {
                    autoDetectedServerState.value = it
                    selectedServerState.value = it // auto-select matching target
                    
                    // If service is running, restart it with updated detected server details
                    if (PingOverlayService.isRunning) {
                        toggleOverlay(true)
                    }
                }
            }
            
            isTestingInProgressState.value = false
        }
    }
}

/**
 * Main dashboard view for interactive eFootball connection testing.
 */
@Composable
fun DashboardScreen(
    modifier: Modifier = Modifier,
    isOverlayAllowed: Boolean,
    isServiceRunning: Boolean,
    servers: List<PingServer>,
    selectedServer: PingServer,
    isTesting: Boolean,
    autoDetectedServer: PingServer?,
    onGrantPermission: () -> Unit,
    onSelectServer: (PingServer) -> Unit,
    onRunSpeedtest: () -> Unit,
    onStartOverlay: () -> Unit,
    onStopOverlay: () -> Unit,
    onToggleBlock: (PingServer) -> Unit
) {
    val context = LocalContext.current
    val liveServicePing by PingOverlayService.currentPingFlow.collectAsState()
    
    var currentTab by remember { mutableIntStateOf(0) }
    var isLowRamEnabled by remember { mutableStateOf(true) }
    
    LaunchedEffect(Unit) {
        isLowRamEnabled = context.getSharedPreferences("efootball_ping_prefs", Context.MODE_PRIVATE)
            .getBoolean("low_ram_mode", true)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // Aesthetic Top Gaming Header
        GamingHeaderBlock()

        // Tab Row switcher (Monitor vs. Diagnostics)
        TabRow(
            selectedTabIndex = currentTab,
            containerColor = DarkSurface,
            contentColor = GamePrimaryYellow,
            divider = { HorizontalDivider(color = BorderSlate) }
        ) {
            Tab(
                selected = currentTab == 0,
                onClick = { currentTab = 0 },
                text = { Text(context.getString(R.string.tab_monitor), fontWeight = FontWeight.Bold) },
                icon = { Icon(Icons.Default.Search, contentDescription = null) }
            )
            Tab(
                selected = currentTab == 1,
                onClick = { currentTab = 1 },
                text = { Text(context.getString(R.string.tab_diagnostics), fontWeight = FontWeight.Bold) },
                icon = { Icon(Icons.Default.Info, contentDescription = null) }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (currentTab == 0) {
            // MAIN MONITOR TAB
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Overlay Service Status card
                item {
                    StatusControlCard(
                        isServiceRunning = isServiceRunning,
                        selectedServer = selectedServer,
                        livePing = liveServicePing,
                        isOverlayAllowed = isOverlayAllowed,
                        onStartOverlay = onStartOverlay,
                        onStopOverlay = onStopOverlay,
                        onGrantPermission = onGrantPermission
                    )
                }

                // If selected server is blocked, warn the user
                if (selectedServer.isBlocked) {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f)), RoundedCornerShape(12.dp)),
                            colors = CardDefaults.cardColors(containerColor = Color(0x1AEF4444)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFEF4444))
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = context.getString(R.string.warning_blocked_server),
                                    color = Color(0xFFEF4444),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // Permission Warning Card if not allowed
                if (!isOverlayAllowed) {
                    item {
                        PermissionWarningCard(onGrantPermission)
                    }
                }

                // Performance mode card
                item {
                    LowRamPerformanceCard(
                        isLowRam = isLowRamEnabled,
                        onToggle = { newValue ->
                            isLowRamEnabled = newValue
                            context.getSharedPreferences("efootball_ping_prefs", Context.MODE_PRIVATE)
                                .edit()
                                .putBoolean("low_ram_mode", newValue)
                                .apply()
                            
                            // If overlay is running, toggle it to reapply the custom interval rate instantly
                            if (isServiceRunning) {
                                onStartOverlay()
                            }
                        }
                    )
                }

                // Global ping test controller
                item {
                    SpeedtestActionBlock(
                        isTesting = isTesting,
                        onRunSpeedtest = onRunSpeedtest,
                        autoDetectedServer = autoDetectedServer
                    )
                }

                item {
                    Text(
                        text = context.getString(R.string.status_header) + " / Select Server",
                        color = Color.LightGray,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }

                // Server block instruction card
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(BorderStroke(1.dp, BorderSlate), RoundedCornerShape(12.dp)),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = GamePrimaryYellow, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = context.getString(R.string.block_instruction),
                                color = Color.LightGray,
                                fontSize = 10.sp,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }

                // Interactive Servers list
                items(servers) { server ->
                    ServerRowItem(
                        server = server,
                        isSelected = server.id == selectedServer.id,
                        onSelect = { onSelectServer(server) },
                        onToggleBlock = { onToggleBlock(server) }
                    )
                }
            }
        } else {
            // COHESIVE SYSTEM GUIDES / DIAGNOSTICS TAB
            DiagnosticsPanel(context)
        }
    }
}

@Composable
fun LowRamPerformanceCard(
    isLowRam: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, BorderSlate), RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Default.Build,
                        contentDescription = null,
                        tint = if (isLowRam) GameSecondaryGreen else Color.LightGray,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = context.getString(R.string.perf_mode_title),
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = context.getString(R.string.pref_low_ram),
                            color = if (isLowRam) GameSecondaryGreen else Color.Gray,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                Switch(
                    checked = isLowRam,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = GameSecondaryGreen,
                        checkedTrackColor = GameSecondaryGreen.copy(alpha = 0.4f),
                        uncheckedThumbColor = Color.Gray,
                        uncheckedTrackColor = Color.DarkGray
                    )
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (isLowRam) context.getString(R.string.pref_low_ram_active) else context.getString(R.string.perf_mode_desc),
                color = Color.LightGray,
                fontSize = 10.5.sp,
                lineHeight = 15.sp
            )
        }
    }
}

/**
 * Top brand header
 */
@Composable
fun GamingHeaderBlock() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF1E283F), Color(0xFF0F1524))
                )
            )
            .padding(top = 20.dp, bottom = 18.dp, start = 20.dp, end = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = GamePrimaryYellow,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "eFootball Ping Tool",
                    color = Color.White,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "مقياس البينج الذكي وسيرفرات كونامي",
                color = GamePrimaryYellow.copy(alpha = 0.85f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * System Service active controllers
 */
@Composable
fun StatusControlCard(
    isServiceRunning: Boolean,
    selectedServer: PingServer,
    livePing: Int?,
    isOverlayAllowed: Boolean,
    onStartOverlay: () -> Unit,
    onStopOverlay: () -> Unit,
    onGrantPermission: () -> Unit
) {
    val context = LocalContext.current
    val glowingAccent = if (isServiceRunning) GameSecondaryGreen else Color.Gray

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, BorderSlate), RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Status marker
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(glowingAccent, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isServiceRunning) "OVERLAY ACTIVE / مؤشر البينج مفعل" else "OVERLAY OFF / المؤشر متوقف",
                        color = if (isServiceRunning) GameSecondaryGreen else Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (isServiceRunning && livePing != null) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0x3310B981))
                    ) {
                        Text(
                            text = "$livePing ms",
                            color = GameSecondaryGreen,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Info details
            Text(
                text = "Target Server: ${selectedServer.getDisplayName(context)} (${selectedServer.awsRegion.uppercase()})",
                color = Color.LightGray,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Action Buttons
            if (!isOverlayAllowed) {
                Button(
                    onClick = onGrantPermission,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF97316)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(context.getString(R.string.btn_grant_perm), color = Color.White, fontWeight = FontWeight.Bold)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!isServiceRunning) {
                        Button(
                            onClick = onStartOverlay,
                            colors = ButtonDefaults.buttonColors(containerColor = GameSecondaryGreen),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("تشغيل المقياس", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    } else {
                        Button(
                            onClick = onStopOverlay,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("إيقاف المقياس", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Permission Required Warning Card
 */
@Composable
fun PermissionWarningCard(onGrantPermission: () -> Unit) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, Color(0xFFF97316).copy(alpha = 0.5f)), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = Color(0x1AF97316)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFF97316))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = context.getString(R.string.perm_req_title),
                    color = Color(0xFFF97316),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = context.getString(R.string.perm_req_desc),
                color = Color.LightGray,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }
    }
}

/**
 * Active automatic routing diagnostics card
 */
@Composable
fun SpeedtestActionBlock(
    isTesting: Boolean,
    onRunSpeedtest: () -> Unit,
    autoDetectedServer: PingServer?
) {
    val context = LocalContext.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, BorderSlate), RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Server Speedtest",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )

                Button(
                    onClick = onRunSpeedtest,
                    enabled = !isTesting,
                    colors = ButtonDefaults.buttonColors(containerColor = GamePrimaryYellow),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp)
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(
                            color = Color.DarkGray,
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(context.getString(R.string.btn_testing), color = Color.DarkGray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.DarkGray, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(context.getString(R.string.btn_test_servers), color = Color.DarkGray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Lowest ping/likely server display code
            AnimatedVisibility(
                visible = autoDetectedServer != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                autoDetectedServer?.let { server ->
                    Column {
                        HorizontalDivider(color = BorderSlate, modifier = Modifier.padding(vertical = 10.dp))
                        Text(
                            text = context.getString(R.string.desc_detected_server),
                            color = Color.Gray,
                            fontSize = 10.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF10B981).copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = GameSecondaryGreen, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = server.getDisplayName(context),
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Text(
                                    text = "Aws Region: " + server.awsRegion.uppercase(),
                                    color = Color.LightGray,
                                    fontSize = 10.sp
                                )
                            }
                            Text(
                                text = "${server.currentPing} ms",
                                color = GameSecondaryGreen,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }
            }
        }
    }
}



@Composable
fun ServerRowItem(
    server: PingServer,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onToggleBlock: () -> Unit
) {
    val context = LocalContext.current
    val pingValue = server.currentPing
    
    val pingColor = remember(pingValue) {
        when {
            pingValue == null -> Color.Gray
            pingValue < 40 -> GameSecondaryGreen
            pingValue < 81 -> GamePrimaryYellow
            pingValue < 121 -> Color(0xFFFF9800)
            else -> Color(0xFFEF4444)
        }
    }

    val containerAlpha = if (server.isBlocked) 0.65f else 1.0f

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                BorderStroke(
                    1.dp,
                    if (isSelected) GamePrimaryYellow else if (server.isBlocked) Color(0xFFEF4444).copy(alpha = 0.4f) else BorderSlate
                ),
                RoundedCornerShape(12.dp)
            )
            .clickable { onSelect() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) Color(0xFF1E263D) else if (server.isBlocked) Color(0x22EF4444) else DarkSurface
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            // Radio outline button
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .border(
                        BorderStroke(1.5.dp, if (isSelected) GamePrimaryYellow else Color.Gray),
                        CircleShape
                    )
                    .padding(3.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(GamePrimaryYellow, CircleShape)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Name
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = server.getDisplayName(context),
                        color = if (server.isBlocked) Color(0xFFEF4444) else Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (server.isBlocked) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .background(Color(0xFFEF4444).copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = context.getString(R.string.status_blocked),
                                color = Color(0xFFEF4444),
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .background(GameSecondaryGreen.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = context.getString(R.string.status_allowed),
                                color = GameSecondaryGreen,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Text(
                    text = server.awsRegion.uppercase(),
                    color = Color.LightGray.copy(alpha = containerAlpha),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // Real-time server ping indicator
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (server.isTesting) {
                    CircularProgressIndicator(
                        color = GamePrimaryYellow,
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(pingColor, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (pingValue != null) "$pingValue ms" else "---",
                        color = pingColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.End
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Interactive Block / Allow badge icon button
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(
                            if (server.isBlocked) Color(0xFFEF4444).copy(alpha = 0.15f)
                            else Color.Gray.copy(alpha = 0.15f),
                            CircleShape
                        )
                        .clickable { onToggleBlock() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (server.isBlocked) Icons.Default.Close else Icons.Default.CheckCircle,
                        contentDescription = "Toggle Block Status",
                        tint = if (server.isBlocked) Color(0xFFEF4444) else GameSecondaryGreen,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

/**
 * Diagnostics & Connection Tips view in English and Arabic
 */
@Composable
fun DiagnosticsPanel(context: Context) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(1.dp, BorderSlate), RoundedCornerShape(14.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = context.getString(R.string.diagnostics_title),
                        color = GamePrimaryYellow,
                        fontWeight = FontWeight.Black,
                        fontSize = 16.sp
                    )
                    
                    HorizontalDivider(color = BorderSlate, modifier = Modifier.padding(vertical = 10.dp))

                    DiagnosticTipItem(
                        icon = Icons.Default.Info,
                        title = "WiFi 5GHz vs 2.4GHz",
                        desc = context.getString(R.string.tip_wifi_5ghz)
                    )

                    DiagnosticTipItem(
                        icon = Icons.Default.Settings,
                        title = "eFootball Matchmaking",
                        desc = context.getString(R.string.tip_matchmaking)
                    )

                    DiagnosticTipItem(
                        icon = Icons.Default.Refresh,
                        title = "Background Sync Data",
                        desc = context.getString(R.string.tip_background_apps)
                    )
                }
            }
        }
        
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(1.dp, BorderSlate), RoundedCornerShape(14.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Why Ping is Crucial in eFootball",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "In online eFootball matches, Konami uses localized P2P and dedicated cloud servers to resolve match inputs. Having a latency higher than 80ms can result in delayed player movements, lag in shot execution, and matching with distant regions. For competitive play (Division climbing), we highly recommend playing on servers showing < 40ms.",
                        color = Color.LightGray,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        textAlign = TextAlign.Justify
                    )
                }
            }
        }
    }
}

@Composable
fun DiagnosticTipItem(
    icon: ImageVector,
    title: String,
    desc: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .background(Color(0xFF3B82F6).copy(alpha = 0.15f), CircleShape)
                .padding(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = GameBlue,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = desc,
                color = Color.LightGray,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }
    }
}
