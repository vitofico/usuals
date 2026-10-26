package io.theficos.usuals.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf

enum class BackgroundStyle(val id: String) {
    PLAIN("plain"),
    ARTWORK("artwork");

    companion object {
        fun fromId(id: String?): BackgroundStyle = entries.firstOrNull { it.id == id } ?: ARTWORK
    }
}

val LocalBackgroundStyle = staticCompositionLocalOf { BackgroundStyle.ARTWORK }
