package com.openvcam.core

object CropPresets {
    val keys = listOf("full", "1:1", "4:3", "16:9", "9:16")

    fun label(key: String): String = when (key) {
        "1:1" -> "Square"
        "4:3" -> "4:3"
        "16:9" -> "16:9"
        "9:16" -> "9:16"
        else -> "Full"
    }

    fun ratio(key: String): Float? = when (key) {
        "1:1" -> 1f
        "4:3" -> 4f / 3f
        "16:9" -> 16f / 9f
        "9:16" -> 9f / 16f
        else -> null
    }

    /** Horizontal / vertical zoom factors needed to crop the source to the preset aspect. */
    fun factors(sourceWidth: Int, sourceHeight: Int, crop: String, rotation: Int): Pair<Float, Float> {
        val target = ratio(crop) ?: return 1f to 1f
        if (sourceWidth <= 0 || sourceHeight <= 0) return 1f to 1f
        val effective = if (rotation % 180 == 0) {
            sourceWidth.toFloat() / sourceHeight
        } else {
            sourceHeight.toFloat() / sourceWidth
        }
        return if (effective > target) effective / target to 1f
        else 1f to target / effective
    }
}
