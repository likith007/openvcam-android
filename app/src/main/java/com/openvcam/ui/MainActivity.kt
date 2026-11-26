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
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.openvcam.core.AppConfig
import com.openvcam.core.ConfigStore
import com.openvcam.core.CoreKeepAliveService
import com.openvcam.core.CropPresets
import com.openvcam.core.SourceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MinimalScreen() }
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

private data class FrameworkEntry(val packages: List<String>, val label: String)

private val frameworks = listOf(
    FrameworkEntry(listOf("org.lsposed.manager", "com.lsposed.manager"), "LSPosed"),
    FrameworkEntry(listOf("org.lsposed.lspatch", "com.lsposed.lspatch"), "LSPatch"),
    FrameworkEntry(listOf("com.vector.manager", "org.vector.manager"), "Vector"),
    FrameworkEntry(listOf("de.robv.android.xposed.installer"), "Xposed"),
    FrameworkEntry(listOf("org.meowcat.edxposed.manager"), "EdXposed"),
    FrameworkEntry(listOf("com.topjohnwu.magisk"), "Magisk"),
    FrameworkEntry(listOf("me.weishu.kernelsu", "com.kernelsu.manager"), "KernelSU"),
    FrameworkEntry(listOf("com.apatch.app", "com.apatch.manager"), "APatch")
)

private fun uriFor(path: String): Uri? =
    path.takeIf { it.isNotEmpty() }?.let { Uri.parse("content://com.openvcam.provider/media/$it") }

private fun shell(command: String, seconds: Long = 3L): String = try {
    val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
    val output = process.inputStream.bufferedReader().readText()
    process.waitFor(seconds, TimeUnit.SECONDS)
    output
} catch (_: Throwable) {
    ""
}

private fun hasRoot(): Boolean = shell("id", 2L).contains("uid=0")

private fun packageInstalled(context: Context, name: String): Boolean = try {
    context.packageManager.getPackageInfo(name, 0)
    true
} catch (_: Throwable) {
    false
}

private fun detectFramework(context: Context): String {
    frameworks.forEach { entry ->
        if (entry.packages.any { packageInstalled(context, it) }) return entry.label
    }

    val output = shell(
        "pm list packages | grep -Ei 'lsposed|lspatch|xposed|vector'; " +
            "test -d /data/adb/lspd && echo lsposed; " +
            "test -d /data/adb/apatch && echo apatch; " +
            "test -d /data/adb/ksu && echo kernelsu; " +
            "ls /data/adb/modules 2>/dev/null | grep -Ei 'lsposed|xposed|vector'",
        2L
    ).lowercase()

    return when {
        output.contains("lsposed") -> "LSPosed"
        output.contains("vector") -> "Vector"
        output.contains("lspatch") -> "LSPatch"
        output.contains("xposed") -> "Xposed"
        output.contains("apatch") -> "APatch"
        output.contains("kernelsu") -> "KernelSU"
        output.contains("magisk") -> "Magisk"
        else -> "Not detected"
    }
}

private fun frameworkIntent(context: Context): Intent? {
    frameworks.forEach { entry ->
        entry.packages.firstOrNull { packageInstalled(context, it) }?.let { pkg ->
            context.packageManager.getLaunchIntentForPackage(pkg)?.let { return it }
        }
    }
    return null
}

private fun fixWithRoot(context: Context, onDone: (Boolean, String) -> Unit) {
    Thread {
        val pkg = context.packageName
        shell(
            "pm grant $pkg android.permission.POST_NOTIFICATIONS; " +
                "pm grant $pkg android.permission.FOREGROUND_SERVICE_SPECIAL_USE; " +
                "am start-foreground-service -n $pkg/$pkg.core.CoreKeepAliveService",
            5L
        )
        val ok = hasRoot()
        val detected = if (ok) detectFramework(context) else "Not detected"
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                context,
                if (ok) "Root access active" else "Root access failed — allow su for OpenVCam",
                Toast.LENGTH_SHORT
            ).show()
            onDone(ok, detected)
        }
    }.start()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MinimalScreen() {
    val context = LocalContext.current
    val store = ConfigStore.get(context)
    var version by remember { mutableIntStateOf(0) }
    val cfg = remember(version) { store.config }

    var root by remember { mutableStateOf(false) }
    var framework by remember { mutableStateOf("Checking…") }
    var hookActive by remember { mutableStateOf(false) }
    val apps = remember { mutableStateOf(emptyList<AppEntry>()) }
    var query by remember { mutableStateOf("") }
    var expandedEdit by remember { mutableStateOf(false) }
    var expandedTargets by remember { mutableStateOf(false) }

    fun refresh() { version++ }
    fun apply(action: String, value: Any? = null) {
        store.applyControl(action, value)
        refresh()
    }
    fun edit(block: AppConfig.() -> Unit) {
        store.update {
            block()
            // The hook is only meaningful with both a feed and at least one app.
            if (activeSource.type == "none" || targets.isEmpty()) enabled = false
        }
        refresh()
    }

    LaunchedEffect(Unit) {
        // A hook enabled before media/targets were chosen would blank the camera.
        val initial = store.config
        if (initial.enabled && (initial.activeSource.type == "none" || initial.targets.isEmpty())) {
            store.update { enabled = false }
            refresh()
        }
        root = withContext(Dispatchers.IO) { hasRoot() }
        framework = withContext(Dispatchers.IO) { detectFramework(context) }
    }
    LaunchedEffect(expandedTargets) {
        if (expandedTargets && apps.value.isEmpty()) {
            apps.value = withContext(Dispatchers.IO) { UiUtils.installedApps(context) }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(6_000L)
            hookActive = store.anyHookActive()
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        UiUtils.importMedia(context, uri ?: return@rememberLauncherForActivityResult, "image")
            ?.let { source -> edit { sources[cfg.activeIndex] = source } }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        UiUtils.importMedia(context, uri ?: return@rememberLauncherForActivityResult, "video")
            ?.let { source -> edit { sources[cfg.activeIndex] = source } }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.CameraAlt, null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("OpenVCam", fontWeight = FontWeight.Bold)
                }
            })
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                HeroCard(
                    enabled = cfg.enabled,
                    root = root,
                    framework = framework,
                    hookActive = hookActive,
                    scopeCount = cfg.targets.size,
                    canEnable = cfg.activeSource.type != "none" && cfg.targets.isNotEmpty(),
                    onToggle = { apply("toggleEnabled") },
                    onFixRoot = {
                        fixWithRoot(context) { ok, detected ->
                            root = ok
                            framework = detected
                        }
                    },
                    onOpenFramework = {
                        val intent = frameworkIntent(context)
                        if (intent != null) context.startActivity(intent)
                        else Toast.makeText(context, "Install LSPosed or Vector first", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            item {
                MediaCard(
                    context = context,
                    cfg = cfg,
                    index = cfg.activeIndex,
                    onEdit = { block -> edit(block) },
                    onApply = { action, value -> apply(action, value) },
                    expanded = expandedEdit,
                    onExpand = { expandedEdit = !expandedEdit },
                    onPickImage = { imagePicker.launch("image/*") },
                    onPickVideo = { videoPicker.launch("video/*") }
                )
            }

            item {
                TargetCard(
                    cfg = cfg,
                    apps = apps.value,
                    query = query,
                    onQuery = { query = it },
                    expanded = expandedTargets,
                    onExpand = { expandedTargets = !expandedTargets },
                    onEdit = { block -> edit(block) }
                )
            }

            item {
                Text(
                    "Changes apply instantly. Keep OpenVCam and your target apps enabled in LSPosed/Vector.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun HeroCard(
    enabled: Boolean,
    root: Boolean,
    framework: String,
    hookActive: Boolean,
    scopeCount: Int,
    canEnable: Boolean,
    onToggle: () -> Unit,
    onFixRoot: () -> Unit,
    onOpenFramework: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    Card(
        Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.secondary,
                            MaterialTheme.colorScheme.tertiary
                        )
                    )
                )
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (enabled) Icons.Filled.Check else Icons.Filled.Close,
                        null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (enabled) "Hook enabled" else "Hook disabled",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (enabled) "Virtual feed is active" else "Real camera is active",
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Button(
                    onClick = onToggle,
                    enabled = enabled || canEnable,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = primary,
                        disabledContainerColor = Color.White.copy(alpha = 0.35f),
                        disabledContentColor = Color.White
                    )
                ) {
                    Text(if (enabled) "Disable hook" else "Enable hook", fontWeight = FontWeight.Bold)
                }
                if (!enabled && !canEnable) {
                    Text(
                        "Select media and at least one target app first",
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StatusChip("Root", root, Icons.Filled.Security)
                    StatusChip(framework, framework != "Not detected" && framework != "Checking…", Icons.Filled.Layers)
                    StatusChip(if (hookActive) "Hook live" else "Waiting", hookActive, Icons.Filled.Link)
                    StatusChip("$scopeCount app${if (scopeCount == 1) "" else "s"}", scopeCount > 0, Icons.Filled.Apps)
                }

                if (!root || framework == "Not detected") {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Filled.Warning,
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            if (!root) "Root access not granted" else "Hooking framework not detected",
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        FilledTonalButton(
                            onClick = if (!root) onFixRoot else onOpenFramework,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = Color.White,
                                contentColor = primary
                            )
                        ) {
                            Text(if (!root) "Fix root" else if (framework == "Checking…") "Check" else "Open")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(text: String, ok: Boolean, icon: ImageVector) {
    Surface(
        color = Color.White.copy(alpha = if (ok) 0.25f else 0.12f),
        contentColor = Color.White,
        shape = CircleShape
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(5.dp))
            Text(text, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.width(5.dp))
            Text(if (ok) "●" else "○", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaCard(
    context: Context,
    cfg: AppConfig,
    index: Int,
    onEdit: (AppConfig.() -> Unit) -> Unit,
    onApply: (String, Any?) -> Unit,
    expanded: Boolean,
    onExpand: () -> Unit,
    onPickImage: () -> Unit,
    onPickVideo: () -> Unit
) {
    val source = cfg.sources.getOrElse(index) { SourceConfig() }
    Card(Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (source.type == "video") Icons.Filled.Videocam else Icons.Filled.Image,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (source.type == "none") "No media selected" else source.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1
                    )
                    Text(
                        if (source.type == "none") "Choose an image or video"
                        else source.type.uppercase() + " · " + CropPresets.label(source.crop),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (source.type != "none") {
                    IconButton(onClick = { onEdit { sources[index] = SourceConfig() } }) {
                        Icon(Icons.Filled.Delete, "Clear", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }

            MediaPreview(context, source)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onPickImage, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Image, null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Image")
                }
                FilledTonalButton(onClick = onPickVideo, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Videocam, null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Video")
                }
            }

            OutlinedButton(onClick = onExpand, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    null,
                    modifier = Modifier.rotate(if (expanded) 180f else 0f)
                )
                Spacer(Modifier.width(6.dp))
                Text(if (expanded) "Hide editing" else "Edit crop, rotation & zoom")
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CropPresets.keys.forEach { key ->
                            FilterChip(
                                selected = source.crop == key,
                                onClick = { onEdit { sources[index].crop = key } },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.Crop, null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                },
                                label = { Text(CropPresets.label(key)) }
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MiniIcon(Icons.Filled.RotateLeft, "Rotate left") {
                            onEdit { sources[index].rotation = (sources[index].rotation + 270) % 360 }
                        }
                        MiniIcon(Icons.Filled.RotateRight, "Rotate right") {
                            onEdit { sources[index].rotation = (sources[index].rotation + 90) % 360 }
                        }
                        MiniIcon(Icons.Filled.Flip, "Mirror") {
                            onEdit { sources[index].mirror = !sources[index].mirror }
                        }
                        MiniIcon(Icons.Filled.ZoomOut, "Zoom out") {
                            onEdit { sources[index].zoom = (sources[index].zoom - 0.1f).coerceAtLeast(0.5f) }
                        }
                        MiniIcon(Icons.Filled.ZoomIn, "Zoom in") {
                            onEdit { sources[index].zoom = (sources[index].zoom + 0.1f).coerceAtMost(3f) }
                        }
                    }
                    Text(
                        "Zoom  " + "%.1f".format(source.zoom) + "x",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = source.zoom,
                        onValueChange = { onEdit { sources[index].zoom = it } },
                        valueRange = 0.5f..3f
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = { onApply("play", null) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Play")
                        }
                        FilledTonalButton(
                            onClick = { onApply("pause", null) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Pause, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Pause")
                        }
                        FilledTonalButton(
                            onClick = { onApply("reset", null) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.RestartAlt, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Reset")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.primary
    ) {
        Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
            Icon(icon, label, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun TargetCard(
    cfg: AppConfig,
    apps: List<AppEntry>,
    query: String,
    onQuery: (String) -> Unit,
    expanded: Boolean,
    onExpand: () -> Unit,
    onEdit: (AppConfig.() -> Unit) -> Unit
) {
    val filtered = apps.filter {
        it.label.contains(query, ignoreCase = true) ||
            it.packageName.contains(query, ignoreCase = true)
    }.take(80)
    Card(Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.clickable(onClick = onExpand),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Apps, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Target apps", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (cfg.targets.isEmpty()) "None selected" else "${cfg.targets.size} selected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    null,
                    modifier = Modifier.rotate(if (expanded) 180f else 0f)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQuery,
                        label = { Text("Search apps") },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onEdit { targets.addAll(apps.map { it.packageName }) } },
                            modifier = Modifier.weight(1f)
                        ) { Text("Select all") }
                        OutlinedButton(
                            onClick = { onEdit { targets.clear() } },
                            modifier = Modifier.weight(1f)
                        ) { Text("Clear") }
                    }
                    if (apps.isEmpty()) {
                        Text(
                            "Loading apps…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp)
                        )
                    } else {
                        LazyColumn(Modifier.height(260.dp).fillMaxWidth()) {
                            items(filtered, key = { it.packageName }) { app ->
                                val selected = cfg.targets.contains(app.packageName)
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onEdit {
                                                if (targets.contains(app.packageName)) {
                                                    targets.remove(app.packageName)
                                                } else {
                                                    targets.add(app.packageName)
                                                }
                                            }
                                        }
                                        .padding(vertical = 6.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Filled.Android, null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(app.label, style = MaterialTheme.typography.bodyMedium)
                                        Text(app.packageName, style = MaterialTheme.typography.labelSmall)
                                    }
                                    Checkbox(checked = selected, onCheckedChange = null)
                                }
                            }
                        }
                    }
                    Text(
                        "Also enable OpenVCam and these apps in LSPosed/Vector scope.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaPreview(context: Context, source: SourceConfig) {
    val uri = uriFor(source.path)
    val ratio = CropPresets.ratio(source.crop)
    val bitmap by produceImageBitmap(context, uri)
    val transform = Modifier
        .graphicsLayer(
            scaleX = source.zoom * if (source.mirror) -1f else 1f,
            scaleY = source.zoom
        )
        .rotate(source.rotation.toFloat())
    val crop = when {
        ratio == null -> Modifier.fillMaxSize()
        ratio >= 1f -> Modifier.fillMaxWidth().aspectRatio(ratio)
        else -> Modifier.fillMaxHeight().aspectRatio(ratio)
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(132.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF0B1020)),
        contentAlignment = Alignment.Center
    ) {
        if (uri == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.Info, null,
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(26.dp)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Live preview",
                    color = Color.White.copy(alpha = 0.65f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else {
            Box(crop.then(transform)) {
                if (source.type == "video") {
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                setOnPreparedListener { mp -> mp.isLooping = true; start() }
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
                    bitmap?.let { image ->
                        Image(
                            bitmap = image,
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
    uri: Uri?
): androidx.compose.runtime.State<androidx.compose.ui.graphics.ImageBitmap?> {
    val state = remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(uri) {
        val next = if (uri == null) null else withContext(Dispatchers.IO) {
            try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri))
                    .asImageBitmap()
            } catch (_: Throwable) {
                null
            }
        }
        val previous = state.value
        state.value = next
        if (previous !== next) runCatching { previous?.asAndroidBitmap()?.recycle() }
    }
    return state
}
