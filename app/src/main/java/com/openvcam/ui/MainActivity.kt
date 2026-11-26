package com.openvcam.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.unit.dp
import com.openvcam.core.ConfigStore
import com.openvcam.core.CoreKeepAliveService
import com.openvcam.core.FloatingControlService
import com.openvcam.core.SourceConfig

class MainActivity : ComponentActivity() {
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OpenVCamTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

private enum class Tab(val label: String) {
    HOME("Home"), PRESETS("Presets"), TARGETS("Targets"), CONTROLS("Controls")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val store = ConfigStore.get(context)
    var version by remember { mutableIntStateOf(0) }
    var tab by remember { mutableStateOf(Tab.HOME) }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    @Suppress("UNUSED_VARIABLE")
    val cfg = run {
        version
        store.config
    }

    fun apply(action: String, value: Any? = null) {
        store.applyControl(action, value)
        version++
    }

    fun import(index: Int, uri: Uri?, type: String) {
        if (uri == null) return
        val source = UiUtils.importMedia(context, uri, type) ?: run {
            Toast.makeText(context, "Could not import media", Toast.LENGTH_SHORT).show()
            return
        }
        store.update {
            sources[index] = source
            if (index == 0) activeIndex = 0
        }
        version++
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("OpenVCam", fontWeight = FontWeight.Bold)
                        Text(
                            "Virtual camera · root + LSPosed/Vector",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        label = { Text(item.label) },
                        icon = { Box(Modifier.size(20.dp)) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                Tab.HOME -> HomeScreen(
                    cfg, store, ::apply, { i, u, t -> import(i, u, t) }, version, overlayGranted
                )
                Tab.PRESETS -> PresetsScreen(cfg, store, ::apply, { i, u, t -> import(i, u, t) }, version)
                Tab.TARGETS -> TargetsScreen(cfg, store, version)
                Tab.CONTROLS -> ControlsScreen(cfg, context, ::apply, version, overlayGranted)
            }
        }
    }
}

@Composable
private fun HomeScreen(
    cfg: com.openvcam.core.AppConfig,
    store: ConfigStore,
    apply: (String, Any?) -> Unit,
    import: (Int, Uri?, String) -> Unit,
    version: Int,
    overlayGranted: Boolean
) {
    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        import(0, it, "image")
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        import(0, it, "video")
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { PermissionCard(overlayGranted, context) }

        item { SectionTitle("Main source") }
        item {
            SourceCard(
                title = "Main",
                source = cfg.sources[0],
                imagePicker = imagePicker,
                videoPicker = videoPicker,
                onPick = { index, launcher, type -> },
                onClear = {
                    store.update { sources[0] = SourceConfig() }
                },
                version = version
            )
        }

        item { SectionTitle("Presets") }
        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                cfg.sources.forEachIndexed { index, source ->
                    val selected = cfg.activeIndex == index
                    Card(
                        modifier = Modifier
                            .width(140.dp)
                            .clickable { apply("preset", index) }
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                cfg.presetNames.getOrElse(index) { "Source $index" },
                                style = MaterialTheme.typography.titleSmall,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                if (source.type == "none") "No media"
                                else source.name.ifEmpty { source.type },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2
                            )
                        }
                    }
                }
            }
        }

        item { SectionTitle("Live controls") }
        item { ControlGrid(apply) }

        item { SectionTitle("Floating controller") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Show a draggable control bubble over other apps with play, rotate, mirror, zoom, speed and preset switches."
                    )
                    Button(onClick = {
                        if (!Settings.canDrawOverlays(context)) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                )
                            }
                        } else {
                            context.startForegroundService(Intent(context, FloatingControlService::class.java))
                        }
                    }) {
                        Text("Start floating controls")
                    }
                    OutlinedButton(onClick = {
                        context.stopService(Intent(context, FloatingControlService::class.java))
                    }) {
                        Text("Stop floating controls")
                    }
                }
            }
        }

        item { SectionTitle("Setup") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("1. Root the device and install LSPosed or Vector.")
                    Text("2. Enable OpenVCam as a module.")
                    Text("3. Select the target apps in the module scope (see Targets tab).")
                    Text("4. Choose a source above, then force-stop and reopen the target app.")
                    Text("5. Use the floating bubble or the Controls tab to switch presets live.")
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun PermissionCard(overlayGranted: Boolean, context: Context) {
    val notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    } else true

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Permissions", style = MaterialTheme.typography.titleSmall)
            Text(
                if (overlayGranted) "✓ Overlay access granted" else "⚠ Overlay access needed for floating controls",
                style = MaterialTheme.typography.bodySmall,
                color = if (overlayGranted) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
            Text(
                if (notificationsGranted) "✓ Notifications allowed" else "⚠ Notifications needed for background services",
                style = MaterialTheme.typography.bodySmall,
                color = if (notificationsGranted) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
            if (!overlayGranted) {
                Button(onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                }) { Text("Grant overlay access") }
            }
        }
    }
}

@Composable
private fun SourceCard(
    title: String,
    source: SourceConfig,
    imagePicker: ActivityResultLauncher<String>,
    videoPicker: ActivityResultLauncher<String>,
    onPick: (Int, ActivityResultLauncher<String>, String) -> Unit,
    onClear: () -> Unit,
    version: Int
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                if (source.type == "none") "No source selected"
                else "${source.type.uppercase()} · ${source.name.ifEmpty { source.path }}",
                style = MaterialTheme.typography.bodyMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { imagePicker.launch("image/*") }) { Text("Image") }
                Button(onClick = { videoPicker.launch("video/*") }) { Text("Video") }
                OutlinedButton(onClick = onClear) { Text("Clear") }
            }
        }
    }
}

@Composable
private fun PresetsScreen(
    cfg: com.openvcam.core.AppConfig,
    store: ConfigStore,
    apply: (String, Any?) -> Unit,
    import: (Int, Uri?, String) -> Unit,
    version: Int
) {
    var expanded by remember { mutableIntStateOf(-1) }
    var pendingIndex by remember { mutableIntStateOf(0) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        import(pendingIndex, it, "image")
    }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        import(pendingIndex, it, "video")
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                "Main source and four presets. Each has its own media, rotation, mirror and zoom.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        items(5) { index ->
            val source = cfg.sources[index]
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = if (expanded == index) -1 else index }
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            cfg.presetNames.getOrElse(index) { "Source $index" },
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        if (cfg.activeIndex == index) {
                            FilterChip(selected = true, onClick = {}, label = { Text("Active") })
                        }
                    }
                    Text(
                        if (source.type == "none") "No media"
                        else "${source.type.uppercase()} · ${source.name.ifEmpty { source.path }}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (expanded == index) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                pendingIndex = index
                                pickImage.launch("image/*")
                            }) { Text("Image") }
                            Button(onClick = {
                                pendingIndex = index
                                pickVideo.launch("video/*")
                            }) { Text("Video") }
                            OutlinedButton(onClick = {
                                store.update { sources[index] = SourceConfig() }
                            }) { Text("Clear") }
                            OutlinedButton(onClick = { apply("preset", index) }) { Text("Use") }
                        }
                        Text("Rotation ${source.rotation}°")
                        Slider(
                            value = source.rotation / 90f,
                            onValueChange = {
                                store.update { sources[index].rotation = it.toInt() * 90 }
                            },
                            valueRange = 0f..3f,
                            steps = 3
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Mirror", modifier = Modifier.weight(1f))
                            Switch(
                                checked = source.mirror,
                                onCheckedChange = {
                                    store.update { sources[index].mirror = it }
                                }
                            )
                        }
                        Text("Zoom ${"%.1f".format(source.zoom)}x")
                        Slider(
                            value = source.zoom,
                            onValueChange = { store.update { sources[index].zoom = it } },
                            valueRange = 0.5f..3f
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun TargetsScreen(
    cfg: com.openvcam.core.AppConfig,
    store: ConfigStore,
    version: Int
) {
    val context = LocalContext.current
    val apps = remember { UiUtils.installedApps(context) }
    var query by remember { mutableStateOf("") }
    val filtered = apps.filter {
        it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Target apps", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "These selections are remembered here. LSPosed/Vector scope must be set separately in the framework UI, then force-stop the target app."
                    )
                    Text("Selected: ${cfg.targets.size}", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        item {
            TextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search apps") },
                modifier = Modifier.fillMaxWidth()
            )
        }
        items(filtered) { app ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        store.update {
                            if (targets.contains(app.packageName)) targets.remove(app.packageName)
                            else targets.add(app.packageName)
                        }
                    }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(app.label, style = MaterialTheme.typography.bodyMedium)
                    Text(app.packageName, style = MaterialTheme.typography.labelSmall)
                }
                Checkbox(
                    checked = cfg.targets.contains(app.packageName),
                    onCheckedChange = null
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ControlsScreen(
    cfg: com.openvcam.core.AppConfig,
    context: Context,
    apply: (String, Any?) -> Unit,
    version: Int,
    overlayGranted: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PermissionCard(overlayGranted, context)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Feed controls", style = MaterialTheme.typography.titleMedium)
                Text("Active: ${cfg.presetNames.getOrElse(cfg.activeIndex) { "Source ${cfg.activeIndex}" }}")
                ControlGrid(apply)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Speed", style = MaterialTheme.typography.titleMedium)
                Text("${"%.2f".format(cfg.speed)}x")
                Slider(
                    value = cfg.speed,
                    onValueChange = { apply("setSpeed", it) },
                    valueRange = 0.25f..4f
                )
                Text("Audio mode: ${cfg.audioMode}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { apply("audio", "off") }) { Text("Off") }
                    OutlinedButton(onClick = { apply("audio", "video") }) { Text("Video") }
                    OutlinedButton(onClick = { apply("audio", "mic") }) { Text("Mic") }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Services", style = MaterialTheme.typography.titleMedium)
                Button(onClick = {
                    context.startForegroundService(Intent(context, CoreKeepAliveService::class.java))
                }) { Text("Start core service") }
                OutlinedButton(onClick = {
                    context.stopService(Intent(context, CoreKeepAliveService::class.java))
                }) { Text("Stop core service") }
                Button(onClick = {
                    if (!Settings.canDrawOverlays(context)) {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    } else {
                        context.startForegroundService(Intent(context, FloatingControlService::class.java))
                    }
                }) { Text("Start floating controls") }
                OutlinedButton(onClick = {
                    context.stopService(Intent(context, FloatingControlService::class.java))
                }) { Text("Stop floating controls") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("About", style = MaterialTheme.typography.titleMedium)
                Text(
                    "OpenVCam is an independent open-source virtual camera module. It is not affiliated with or derived from any proprietary camera app. Use it only where you have permission to substitute the camera feed."
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ControlGrid(apply: (String, Any?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { apply("togglePlay", null) }, modifier = Modifier.weight(1f)) { Text("Play/Pause") }
            Button(onClick = { apply("toggleVirtual", null) }, modifier = Modifier.weight(1f)) { Text("Virtual/Real") }
            Button(onClick = { apply("reset", null) }, modifier = Modifier.weight(1f)) { Text("Reset") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { apply("rotate", 90) }, modifier = Modifier.weight(1f)) { Text("Rotate") }
            OutlinedButton(onClick = { apply("mirror", null) }, modifier = Modifier.weight(1f)) { Text("Mirror") }
            OutlinedButton(onClick = { apply("zoomIn", null) }, modifier = Modifier.weight(1f)) { Text("Zoom +") }
            OutlinedButton(onClick = { apply("zoomOut", null) }, modifier = Modifier.weight(1f)) { Text("Zoom -") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { apply("speedUp", null) }, modifier = Modifier.weight(1f)) { Text("Speed +") }
            OutlinedButton(onClick = { apply("speedDown", null) }, modifier = Modifier.weight(1f)) { Text("Speed -") }
            OutlinedButton(onClick = { apply("seek", 0L) }, modifier = Modifier.weight(1f)) { Text("Seek 0") }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}
