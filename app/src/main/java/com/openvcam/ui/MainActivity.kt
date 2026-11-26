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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CameraAlt
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Button
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.RectangleShape
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
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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
    fun edit(block: AppConfig.() -> Unit) {
        store.update(block)
        refresh()
    }

    var activeIndex by remember { mutableIntStateOf(cfg.activeIndex) }
    val rootAvailable = remember { mutableStateOf(hasRoot()) }
    val framework = remember { frameworkName(context) }
    val hookActive = store.anyHookActive()

    LaunchedEffect(version) { activeIndex = store.config.activeIndex }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.CameraAlt, null,
                                tint = Color.White,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text("OpenVCam", fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                HeroBanner(
                    enabled = cfg.enabled,
                    root = rootAvailable.value,
                    framework = framework,
                    hookActive = hookActive,
                    scopeCount = cfg.targets.size,
                    onToggle = { apply("toggleEnabled", null) }
                )
            }

            item {
                SetupBanner(
                    root = rootAvailable.value,
                    framework = framework,
                    hookActive = hookActive,
                    onFix = {
                        grantWithRoot(context)
                        rootAvailable.value = hasRoot()
                        refresh()
                    }
                )
            }

            item {
                SectionHeader("Media source", Icons.Filled.Image)
                Spacer(Modifier.height(8.dp))
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
                SectionHeader("Target apps", Icons.Filled.Apps)
                Spacer(Modifier.height(8.dp))
                TargetCard(cfg, store, ::edit)
            }

            item {
                SectionHeader("Quick controls", Icons.Filled.Tune)
                Spacer(Modifier.height(8.dp))
                ControlCard(context, ::apply)
            }

            item {
                InfoBanner(
                    "Enable OpenVCam in LSPosed/Vector, scope your target apps, then force-stop and reopen them."
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon, null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun HeroBanner(
    enabled: Boolean,
    root: Boolean,
    framework: String,
    hookActive: Boolean,
    scopeCount: Int,
    onToggle: () -> Unit
) {
    val colors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.tertiary
    )
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(colors))
            .padding(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.CameraAlt, null, tint = Color.White, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Virtual Camera", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (enabled) "Feed is being replaced" else "Real camera active",
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { onToggle() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = Color.White,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color.White.copy(alpha = 0.35f)
                    )
                )
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusPill("Root", root, Icons.Filled.Security)
                StatusPill(framework, framework != "Not detected", Icons.Filled.Layers)
                StatusPill("Hook", hookActive, Icons.Filled.Link)
                StatusPill("$scopeCount scopes", scopeCount > 0, Icons.Filled.Apps)
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, ok: Boolean, icon: ImageVector) {
    Surface(
        color = Color.White.copy(alpha = if (ok) 0.24f else 0.10f),
        contentColor = Color.White,
        shape = CircleShape
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(6.dp))
            Text(if (ok) "●" else "○", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SetupBanner(
    root: Boolean,
    framework: String,
    hookActive: Boolean,
    onFix: () -> Unit
) {
    val needsFix = !root || framework == "Not detected"
    val show = needsFix || !hookActive
    AnimatedVisibility(visible = show) {
        Card(
            Modifier.fillMaxWidth().animateContentSize(),
            colors = CardDefaults.cardColors(
                containerColor = if (needsFix) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (needsFix) Icons.Filled.Warning else Icons.Filled.Info,
                    null,
                    tint = if (needsFix) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (!root) "Root access not detected"
                        else if (framework == "Not detected") "Hooking framework not detected"
                        else "Waiting for a hooked app",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        if (needsFix) "Grant su access and required permissions."
                        else "Open a scoped app to activate the hook.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (needsFix) {
                    FilledTonalButton(onClick = onFix) { Text("Fix") }
                }
            }
        }
    }
}

@Composable
private fun InfoBanner(text: String) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun SourceTabs(
    activeIndex: Int,
    cfg: AppConfig,
    onSelect: (Int) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        cfg.presetNames.forEachIndexed { index, _ ->
            FilterChip(
                selected = activeIndex == index,
                onClick = { onSelect(index) },
                leadingIcon = {
                    Icon(
                        if (index == 0) Icons.Filled.CameraAlt else Icons.Filled.Layers,
                        null,
                        modifier = Modifier.size(15.dp)
                    )
                },
                label = { Text(if (index == 0) "Main" else "Preset $index") }
            )
        }
    }
}

@Composable
private fun MediaCard(
    context: Context,
    index: Int,
    source: SourceConfig,
    onEdit: (AppConfig.() -> Unit) -> Unit,
    onApply: (String, Any?) -> Unit,
    version: Int
) {
    val imagePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> import(context, uri, "image")?.let { s -> onEdit { sources[index] = s } } }

    val videoPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> import(context, uri, "video")?.let { s -> onEdit { sources[index] = s } } }

    Card(Modifier.fillMaxWidth().animateContentSize()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (source.type == "video") Icons.Filled.Videocam else Icons.Filled.Image,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (source.type == "none") "No media selected"
                        else source.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1
                    )
                    Text(
                        if (source.type == "none") "Choose an image or video"
                        else "${source.type.uppercase()} · ${CropPresets.label(source.crop)} · ${source.rotation}°",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (source.type != "none") {
                    IconButton(onClick = { onEdit { sources[index] = SourceConfig() } }) {
                        Icon(Icons.Filled.Delete, "Clear media", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }

            MediaPreview(context, source, version)

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    onClick = { imagePicker.launch("image/*") },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Image, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Image")
                }
                FilledTonalButton(
                    onClick = { videoPicker.launch("video/*") },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Videocam, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Video")
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Crop, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Crop", style = MaterialTheme.typography.labelMedium)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CropPresets.keys.forEach { key ->
                    FilterChip(
                        selected = source.crop == key,
                        onClick = { onEdit { sources[index].crop = key } },
                        label = { Text(CropPresets.label(key)) }
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                EditIconButton(Icons.Filled.RotateLeft, "Rotate left") {
                    onEdit { sources[index].rotation = (sources[index].rotation + 270) % 360 }
                }
                EditIconButton(Icons.Filled.RotateRight, "Rotate right") {
                    onEdit { sources[index].rotation = (sources[index].rotation + 90) % 360 }
                }
                EditIconButton(Icons.Filled.Flip, "Mirror") {
                    onEdit { sources[index].mirror = !sources[index].mirror }
                }
                EditIconButton(Icons.Filled.ZoomOut, "Zoom out") {
                    onEdit { sources[index].zoom = (sources[index].zoom - 0.1f).coerceAtLeast(0.5f) }
                }
                EditIconButton(Icons.Filled.ZoomIn, "Zoom in") {
                    onEdit { sources[index].zoom = (sources[index].zoom + 0.1f).coerceAtMost(3f) }
                }
            }

            Column {
                Text(
                    "Zoom  " + "%.1f".format(source.zoom) + "x",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = source.zoom,
                    onValueChange = { onEdit { sources[index].zoom = it } },
                    valueRange = 0.5f..3f
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    onClick = { onApply("play", null) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Play")
                }
                FilledTonalButton(
                    onClick = { onApply("pause", null) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Pause, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Pause")
                }
            }
        }
    }
}

@Composable
private fun EditIconButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.primary
    ) {
        Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
            Icon(icon, label, modifier = Modifier.size(20.dp))
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
            .height(210.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0B1020)),
        contentAlignment = Alignment.Center
    ) {
        if (uri == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Image, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(8.dp))
                Text("Live preview", color = Color.White.copy(alpha = 0.65f), style = MaterialTheme.typography.bodySmall)
            }
        } else {
            Box(cropModifier.then(transform)) {
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
                    imageBitmap?.let { bitmap ->
                        Image(
                            bitmap = bitmap,
                            contentDescription = "Media preview",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }
        }

        Surface(
            Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp),
            color = Color.Black.copy(alpha = 0.55f),
            contentColor = Color.White,
            shape = CircleShape
        ) {
            Text(
                CropPresets.label(source.crop) + "  ·  " + source.rotation + "°  ·  " +
                    "%.1f".format(source.zoom) + "x",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
            )
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
    cfg: AppConfig,
    store: ConfigStore,
    onEdit: (AppConfig.() -> Unit) -> Unit
) {
    val context = LocalContext.current
    val apps = remember { UiUtils.installedApps(context) }
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "expand")
    val filtered = apps.filter {
        it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
    }
    val hooked = cfg.targets.count { store.isHookActive(it) }

    Card(Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Apps, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Selected apps", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${cfg.targets.size} selected  ·  $hooked active",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    null,
                    modifier = Modifier.graphicsLayer(rotationZ = rotation)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search apps") },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
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
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.Android,
                                    null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(app.label, style = MaterialTheme.typography.bodyMedium)
                                    Text(app.packageName, style = MaterialTheme.typography.labelSmall)
                                }
                                if (store.isHookActive(app.packageName)) {
                                    Text("●", color = Color(0xFF16A34A), modifier = Modifier.padding(end = 8.dp))
                                }
                                Checkbox(checked = selected, onCheckedChange = null)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlCard(context: Context, apply: (String, Any?) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ControlButton(Icons.Filled.PlayArrow, "Play") { apply("play", null) }
                ControlButton(Icons.Filled.Pause, "Pause") { apply("pause", null) }
                ControlButton(Icons.Filled.RestartAlt, "Reset") { apply("reset", null) }
                ControlButton(Icons.Filled.Settings, "Virtual") { apply("toggleVirtual", null) }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    onClick = {
                        context.startForegroundService(Intent(context, CoreKeepAliveService::class.java))
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Build, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Core")
                }
                FilledTonalButton(
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
                ) {
                    Icon(Icons.Filled.Layers, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Floating")
                }
            }
        }
    }
}

@Composable
private fun ControlButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick).padding(8.dp)
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, label, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
