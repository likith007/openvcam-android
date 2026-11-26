package com.openvcam.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.openvcam.core.ConfigStore
import com.openvcam.core.CoreKeepAliveService
import com.openvcam.core.CropPresets
import com.openvcam.core.FloatingControlService
import com.openvcam.core.SourceConfig
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OpenVCamTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SimpleScreen()
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

private fun uriFor(path: String): Uri? =
    path.takeIf { it.isNotEmpty() }?.let { Uri.parse("content://com.openvcam.provider/media/$it") }

private fun hasRoot(): Boolean = try {
    val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
    val output = process.inputStream.bufferedReader().readText()
    process.waitFor(3, TimeUnit.SECONDS)
    output.contains("uid=0")
} catch (_: Throwable) {
    false
}

private fun frameworkName(context: Context): String {
    val candidates = listOf(
        "org.lsposed.manager" to "LSPosed",
        "com.lsposed.manager" to "LSPosed",
        "org.lsposed.lspatch" to "LSPatch",
        "de.robv.android.xposed.installer" to "Xposed",
        "org.meowcat.edxposed.manager" to "EdXposed"
    )
    candidates.forEach { (pkg, label) ->
        if (runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)) {
            return label
        }
    }
    return "Not detected"
}

private fun grantWithRoot(context: Context) {
    Thread {
        var granted = 0
        val pkg = context.packageName
        arrayOf(
            "pm grant $pkg android.permission.SYSTEM_ALERT_WINDOW",
            "pm grant $pkg android.permission.POST_NOTIFICATIONS",
            "pm grant $pkg android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
            "am start-foreground-service -n $pkg/$pkg.core.CoreKeepAliveService",
            "am start-foreground-service -n $pkg/$pkg.core.FloatingControlService"
        ).forEach { command ->
            try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                process.waitFor(5, TimeUnit.SECONDS)
                if (command.startsWith("pm grant")) granted++
            } catch (_: Throwable) {
            }
        }
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                context,
                if (granted > 0) "Root access active ($granted permissions granted)"
                else "Root access failed — grant su to OpenVCam",
                Toast.LENGTH_SHORT
            ).show()
        }
    }.start()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleScreen() {
    val context = LocalContext.current
    val store = ConfigStore.get(context)
    var version by remember { mutableIntStateOf(0) }
    val cfg = store.config

    fun refresh() { version++ }
    fun apply(action: String, value: Any? = null) {
        store.applyControl(action, value)
        refresh()
    }
    fun edit(block: com.openvcam.core.AppConfig.() -> Unit) {
        store.update(block)
        refresh()
    }

    var activeIndex by remember { mutableIntStateOf(cfg.activeIndex) }
    val rootAvailable = remember { mutableStateOf(hasRoot()) }
    val framework = remember { frameworkName(context) }

    LaunchedEffect(version) {
        activeIndex = store.config.activeIndex
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("OpenVCam", fontWeight = FontWeight.Bold) })
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                StatusCard(
                    root = rootAvailable.value,
                    framework = framework,
                    hookActive = store.anyHookActive(),
                    scopeCount = cfg.targets.size,
                    onRootGrant = {
                        grantWithRoot(context)
                        rootAvailable.value = hasRoot()
                    }
                )
            }

            item { EnableCard(cfg.enabled, ::apply) }

            item {
                SectionTitle("Media source")
                SourceTabs(activeIndex, cfg) { index ->
                    activeIndex = index
                    apply("preset", index)
                }
            }

            item {
                val source = cfg.sources.getOrElse(activeIndex) { SourceConfig() }
                MediaCard(
                    context = context,
                    index = activeIndex,
                    source = source,
                    onEdit = ::edit,
                    onApply = ::apply,
                    version = version
                )
            }

            item {
                SectionTitle("Target apps")
                TargetCard(cfg, store, ::edit, version)
            }

            item {
                SectionTitle("Quick controls")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { apply("togglePlay", null) }, modifier = Modifier.weight(1f)) {
                        Text("Play/Pause")
                    }
                    OutlinedButton(onClick = { apply("reset", null) }, modifier = Modifier.weight(1f)) {
                        Text("Reset")
                    }
                    OutlinedButton(onClick = { apply("toggleVirtual", null) }, modifier = Modifier.weight(1f)) {
                        Text("Virtual/Real")
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            context.startForegroundService(Intent(context, CoreKeepAliveService::class.java))
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Start core") }
                    Button(
                        onClick = {
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
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Floating controls") }
                }
            }

            item {
                Text(
                    "Setup: enable OpenVCam in LSPosed/Vector, scope the target apps above, force-stop and reopen them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun StatusCard(
    root: Boolean,
    framework: String,
    hookActive: Boolean,
    scopeCount: Int,
    onRootGrant: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Environment", style = MaterialTheme.typography.titleMedium)
            StatusRow("Root access", if (root) "Granted" else "Not available", root)
            StatusRow("Framework", framework, framework != "Not detected")
            StatusRow("Hook integration", if (hookActive) "Active in scoped app" else "Waiting for scoped app", hookActive)
            StatusRow("Selected scopes", "$scopeCount app(s)", scopeCount > 0)
            if (!root || framework == "Not detected") {
                Spacer(Modifier.height(4.dp))
                Button(onClick = onRootGrant) { Text("Fix with root") }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (ok) "●" else "○",
            color = if (ok) Color(0xFF2E7D32) else Color(0xFFB71C1C),
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun EnableCard(enabled: Boolean, apply: (String, Any?) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { apply("toggleEnabled", null) }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Virtual camera", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (enabled) "Enabled — feed will be replaced" else "Disabled — real camera",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(checked = enabled, onCheckedChange = { apply("toggleEnabled", null) })
        }
    }
}

@Composable
private fun SourceTabs(
    activeIndex: Int,
    cfg: com.openvcam.core.AppConfig,
    onSelect: (Int) -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        cfg.presetNames.forEachIndexed { index, name ->
            FilterChip(
                selected = activeIndex == index,
                onClick = { onSelect(index) },
                label = { Text(if (index == 0) "Main" else "P$index") }
            )
        }
    }
}

@Composable
private fun MediaCard(
    context: Context,
    index: Int,
    source: SourceConfig,
    onEdit: (com.openvcam.core.AppConfig.() -> Unit) -> Unit,
    onApply: (String, Any?) -> Unit,
    version: Int
) {
    val imagePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> import(context, uri, "image")?.let { s -> onEdit { sources[index] = s } } }

    val videoPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> import(context, uri, "video")?.let { s -> onEdit { sources[index] = s } } }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (source.type == "none") "No media selected"
                else "${source.type.uppercase()} · ${source.name}",
                style = MaterialTheme.typography.titleSmall
            )

            MediaPreview(context, source, version)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { imagePicker.launch("image/*") }) { Text("Image") }
                Button(onClick = { videoPicker.launch("video/*") }) { Text("Video") }
                if (source.type != "none") {
                    OutlinedButton(onClick = { onEdit { sources[index] = SourceConfig() } }) {
                        Text("Clear")
                    }
                }
            }

            Text("Crop", style = MaterialTheme.typography.labelMedium)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                CropPresets.keys.forEach { key ->
                    FilterChip(
                        selected = source.crop == key,
                        onClick = { onEdit { sources[index].crop = key } },
                        label = { Text(CropPresets.label(key)) }
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onEdit { sources[index].rotation = (sources[index].rotation + 270) % 360 } }) {
                    Text("Rotate -90°")
                }
                OutlinedButton(onClick = { onEdit { sources[index].rotation = (sources[index].rotation + 90) % 360 } }) {
                    Text("Rotate +90°")
                }
                OutlinedButton(onClick = { onEdit { sources[index].mirror = !sources[index].mirror } }) {
                    Text("Mirror")
                }
            }

            Text("Zoom ${"%.1f".format(source.zoom)}x", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = source.zoom,
                onValueChange = { onEdit { sources[index].zoom = it } },
                valueRange = 0.5f..3f
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onApply("play", null) }, modifier = Modifier.weight(1f)) {
                    Text("Play")
                }
                OutlinedButton(onClick = { onApply("pause", null) }, modifier = Modifier.weight(1f)) {
                    Text("Pause")
                }
            }
        }
    }
}

private fun import(context: Context, uri: Uri?, type: String): SourceConfig? {
    if (uri == null) return null
    return try {
        val resolver = context.contentResolver
        val name = resolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        } ?: "media"
        val ext = if (name.contains('.')) name.substringAfterLast('.') else if (type == "video") "mp4" else "jpg"
        val filename = "src_${System.currentTimeMillis()}.$ext"
        resolver.openInputStream(uri)?.use { input ->
            ConfigStore.get(context).mediaFile(filename).outputStream().use { out -> input.copyTo(out) }
        } ?: return null
        SourceConfig(type = type, name = name, path = filename)
    } catch (t: Throwable) {
        Toast.makeText(context, "Could not import media", Toast.LENGTH_SHORT).show()
        null
    }
}

@Composable
private fun MediaPreview(context: Context, source: SourceConfig, version: Int) {
    val uri = uriFor(source.path)
    val ratio = CropPresets.ratio(source.crop)
    val imageBitmap by produceImageBitmap(context, uri, version)

    val transform = Modifier
        .graphicsLayer(
            scaleX = source.zoom * if (source.mirror) -1f else 1f,
            scaleY = source.zoom
        )
        .rotate(source.rotation.toFloat())

    val cropModifier = when {
        ratio == null -> Modifier.fillMaxSize()
        ratio >= 1f -> Modifier.fillMaxWidth().aspectRatio(ratio)
        else -> Modifier.fillMaxHeight().aspectRatio(ratio)
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RectangleShape)
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        if (uri == null) {
            Text("Preview will appear here", color = Color.White)
        } else {
            Box(cropModifier.then(transform)) {
                if (source.type == "video") {
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                setOnPreparedListener { mp ->
                                    mp.isLooping = true
                                    start()
                                }
                            }
                        },
                        update = { view ->
                            if (view.tag != uri.toString()) {
                                view.tag = uri.toString()
                                view.setVideoURI(uri)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    imageBitmap?.let { bitmap ->
                        androidx.compose.foundation.Image(
                            bitmap = bitmap,
                            contentDescription = "Media preview",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun produceImageBitmap(
    context: Context,
    uri: Uri?,
    version: Int
): androidx.compose.runtime.State<androidx.compose.ui.graphics.ImageBitmap?> {
    val state = remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(uri, version) {
        state.value = if (uri == null) null else try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source).asImageBitmap()
        } catch (_: Throwable) {
            null
        }
    }
    return state
}

@Composable
private fun TargetCard(
    cfg: com.openvcam.core.AppConfig,
    store: ConfigStore,
    onEdit: (com.openvcam.core.AppConfig.() -> Unit) -> Unit,
    version: Int
) {
    val context = LocalContext.current
    val apps = remember { UiUtils.installedApps(context) }
    var query by remember { mutableStateOf("") }
    val filtered = apps.filter {
        it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search apps") },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Selected: ${cfg.targets.size} · hooked: ${cfg.targets.count { store.isHookActive(it) }}",
                style = MaterialTheme.typography.bodySmall
            )
            Column(Modifier.height(260.dp).verticalScroll(rememberScrollState())) {
                filtered.forEach { app ->
                    val selected = cfg.targets.contains(app.packageName)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                onEdit {
                                    if (targets.contains(app.packageName)) targets.remove(app.packageName)
                                    else targets.add(app.packageName)
                                }
                            }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.bodyMedium)
                            Text(app.packageName, style = MaterialTheme.typography.labelSmall)
                        }
                        if (store.isHookActive(app.packageName)) {
                            Text("●", color = Color(0xFF2E7D32), modifier = Modifier.padding(end = 8.dp))
                        }
                        Checkbox(checked = selected, onCheckedChange = null)
                    }
                }
            }
        }
    }
}
