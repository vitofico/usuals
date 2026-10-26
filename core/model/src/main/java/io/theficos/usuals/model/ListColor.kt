package io.theficos.usuals.model

/** Stable list identity choices. Null means the current theme's automatic accent. */
enum class ListColor(val id: String) {
    PLUM("plum"), SAGE("sage"), TEAL("teal"), SLATE("slate"), OCHRE("ochre"), ROSE("rose");
    companion object {
        fun fromId(id: String?): ListColor? = entries.firstOrNull { it.id == id }
    }
}
