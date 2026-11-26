package com.openvcam.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SourceConfig(
    var type: String = "none",          // none | image | video
    var name: String = "",
    var path: String = "",              // filename inside filesDir/media
    var rotation: Int = 0,              // 0 | 90 | 180 | 270
    var mirror: Boolean = false,
    var zoom: Float = 1f,
    var crop: String = "full"           // full | 1:1 | 4:3 | 16:9 | 9:16
)

data class AppConfig(
    var enabled: Boolean = true,
    var virtualMode: Boolean = true,
    var activeIndex: Int = 0,           // 0 = main source, 1..4 = presets
    var playing: Boolean = true,
    var speed: Float = 1f,
    var positionMs: Long = 0L,
    var audioMode: String = "off",      // off | video | mic
    var theme: String = "system",       // system | light | dark
    var targets: MutableSet<String> = mutableSetOf(),
    var presetNames: MutableList<String> =
        mutableListOf("Main", "Preset 1", "Preset 2", "Preset 3", "Preset 4"),
    var sources: MutableList<SourceConfig> =
        MutableList(5) { SourceConfig() }
) {
    val activeSource: SourceConfig
        get() = sources.getOrElse(activeIndex) { sources[0] }
}

class ConfigStore private constructor(private val context: Context) {

    private val file = File(context.filesDir, "config.json")
    private val lock = Any()
    @Volatile
    private var current: AppConfig = AppConfig()

    companion object {
        @Volatile
        private var instance: ConfigStore? = null
        fun get(context: Context): ConfigStore {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val created = ConfigStore(context.applicationContext)
                created.load()
                instance = created
                return created
            }
        }
    }

    private val lastHeartbeat = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun recordHeartbeat(packageName: String) {
        if (packageName.isNotBlank()) lastHeartbeat[packageName] = System.currentTimeMillis()
    }

    fun heartbeats(): Map<String, Long> = HashMap(lastHeartbeat)

    fun isHookActive(packageName: String): Boolean {
        val time = lastHeartbeat[packageName] ?: return false
        return System.currentTimeMillis() - time < 120_000L
    }

    fun anyHookActive(): Boolean = lastHeartbeat.keys.any { isHookActive(it) }

    val config: AppConfig
        get() = synchronized(lock) { current }

    fun snapshotJson(): String = synchronized(lock) { toJson(current).toString() }

    fun load() {
        synchronized(lock) {
            current = try {
                if (file.exists()) fromJson(JSONObject(file.readText())) else AppConfig()
            } catch (t: Throwable) {
                AppConfig()
            }
        }
    }

    private fun persistLocked() {
        val temp = File(file.parentFile, "config.json.tmp")
        try {
            temp.writeText(toJson(current).toString())
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText())
                temp.delete()
            }
        } catch (_: Throwable) {
            runCatching { temp.delete() }
        }
    }

    fun save() {
        synchronized(lock) { persistLocked() }
    }

    fun update(block: AppConfig.() -> Unit) {
        synchronized(lock) {
            current.block()
            persistLocked()
        }
    }

    fun applyControl(action: String, value: Any?): String {
        update {
            when (action) {
                "togglePlay" -> playing = !playing
                "play" -> playing = true
                "pause" -> playing = false
                "virtual" -> virtualMode = true
                "real" -> virtualMode = false
                "toggleVirtual" -> virtualMode = !virtualMode
                "rotate" -> {
                    val step = (value as? Number)?.toInt() ?: 90
                    activeSource.rotation = (activeSource.rotation + step) % 360
                }
                "setRotation" -> activeSource.rotation = ((value as? Number)?.toInt() ?: 0) % 360
                "mirror" -> activeSource.mirror = !activeSource.mirror
                "zoomIn" -> activeSource.zoom = (activeSource.zoom + 0.1f).coerceAtMost(3f)
                "zoomOut" -> activeSource.zoom = (activeSource.zoom - 0.1f).coerceAtLeast(0.5f)
                "setZoom" -> activeSource.zoom = (value as? Number)?.toFloat()?.coerceIn(0.5f, 3f) ?: 1f
                "speedUp" -> speed = (speed + 0.25f).coerceAtMost(4f)
                "speedDown" -> speed = (speed - 0.25f).coerceAtLeast(0.25f)
                "setSpeed" -> speed = (value as? Number)?.toFloat()?.coerceIn(0.25f, 4f) ?: 1f
                "seek" -> positionMs = (value as? Number)?.toLong() ?: 0L
                "preset" -> activeIndex = (value as? Number)?.toInt()?.coerceIn(0, 4) ?: 0
                "audio" -> {
                    audioMode = when (value as? String) {
                        "video" -> "video"
                        "mic" -> "mic"
                        else -> "off"
                    }
                }
                "setTheme" -> theme = when (value as? String) {
                    "light", "dark" -> value as String
                    else -> "system"
                }
                "toggleEnabled" -> enabled = !enabled
                "reset" -> {
                    activeSource.rotation = 0
                    activeSource.mirror = false
                    activeSource.zoom = 1f
                    speed = 1f
                    positionMs = 0L
                }
            }
        }
        return snapshotJson()
    }

    fun mediaDir(): File = File(context.filesDir, "media").apply { mkdirs() }

    fun mediaFile(name: String): File = File(mediaDir(), name)

    private fun toJson(c: AppConfig): JSONObject = JSONObject().apply {
        put("enabled", c.enabled)
        put("virtualMode", c.virtualMode)
        put("activeIndex", c.activeIndex)
        put("playing", c.playing)
        put("speed", c.speed.toDouble())
        put("positionMs", c.positionMs)
        put("audioMode", c.audioMode)
        put("theme", c.theme)
        put("targets", JSONArray(c.targets))
        put("presetNames", JSONArray(c.presetNames))
        put("sources", JSONArray().apply {
            c.sources.forEach { s ->
                put(JSONObject().apply {
                    put("type", s.type)
                    put("name", s.name)
                    put("path", s.path)
                    put("rotation", s.rotation)
                    put("mirror", s.mirror)
                    put("zoom", s.zoom.toDouble())
                    put("crop", s.crop)
                })
            }
        })
    }

    private fun fromJson(json: JSONObject): AppConfig {
        val names = mutableListOf<String>()
        json.optJSONArray("presetNames")?.let { arr ->
            for (i in 0 until arr.length()) names.add(arr.optString(i, "Source ${i + 1}"))
        }
        while (names.size < 5) names.add("Source ${names.size + 1}")

        val targets = mutableSetOf<String>()
        json.optJSONArray("targets")?.let { arr ->
            for (i in 0 until arr.length()) targets.add(arr.optString(i))
        }

        val sources = mutableListOf<SourceConfig>()
        json.optJSONArray("sources")?.let { arr ->
            for (i in 0 until 5) {
                val o = if (i < arr.length()) arr.optJSONObject(i) ?: JSONObject() else JSONObject()
                sources.add(
                    SourceConfig(
                        type = o.optString("type", "none"),
                        name = o.optString("name", ""),
                        path = o.optString("path", ""),
                        rotation = o.optInt("rotation", 0),
                        mirror = o.optBoolean("mirror", false),
                        zoom = o.optDouble("zoom", 1.0).toFloat(),
                        crop = o.optString("crop", "full")
                    )
                )
            }
        }
        if (sources.isEmpty()) sources.addAll(MutableList(5) { SourceConfig() })

        return AppConfig(
            enabled = json.optBoolean("enabled", true),
            virtualMode = json.optBoolean("virtualMode", true),
            activeIndex = json.optInt("activeIndex", 0).coerceIn(0, 4),
            playing = json.optBoolean("playing", true),
            speed = json.optDouble("speed", 1.0).toFloat(),
            positionMs = json.optLong("positionMs", 0L),
            audioMode = json.optString("audioMode", "off"),
            theme = json.optString("theme", "system"),
            targets = targets,
            presetNames = names,
            sources = sources
        )
    }
}
