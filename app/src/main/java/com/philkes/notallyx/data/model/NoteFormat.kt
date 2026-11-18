package com.philkes.notallyx.data.model

enum class NoteFormat(val value: String) {
    TEXT("text"),
    MARKDOWN("markdown");

    companion object {
        fun fromValue(value: String?): NoteFormat? = entries.find {
            it.value.equals(value, ignoreCase = true)
        }
    }
}
