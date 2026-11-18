package com.philkes.notallyx.data.adb

import android.content.ContentValues
import android.util.Base64
import com.philkes.notallyx.data.dao.BaseNoteDao
import com.philkes.notallyx.data.model.BaseNote
import com.philkes.notallyx.data.model.Converters
import com.philkes.notallyx.data.model.Type
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal class AdbNoteChanges(input: ContentValues) {

    private val values = if (input.containsKey("patch")) decodePatch(input) else input

    init {
        require(values.size() > 0 && values.keySet().all { it in EDITABLE_FIELDS }) {
            "Provide one or more of title, body, spans, items"
        }
        require(values.keySet().all { values[it] is String }) { "Use string values" }
    }

    private val title = values.getAsString("title")
    private val body = values.getAsString("body")
    private val spans = values.getAsString("spans")
    private val items = values.getAsString("items")

    fun applyTo(note: BaseNote): BaseNote {
        require(note.type == Type.NOTE || (body == null && spans == null)) {
            "Only text notes support body and spans"
        }
        require(note.type == Type.LIST || items == null) { "Only checklists support items" }
        val updatedBody = body ?: note.body
        require(body == null || body.length <= BaseNoteDao.MAX_BODY_CHAR_LENGTH) { "Body too long" }
        val updatedSpans =
            when {
                spans != null ->
                    parseArray(spans) { array ->
                        Converters.jsonToSpans(array).also { parsed ->
                            require(
                                parsed.size == array.length() &&
                                    parsed.all {
                                        it.start >= 0 &&
                                            it.start < it.end &&
                                            it.end <= updatedBody.length
                                    }
                            ) {
                                "Invalid formatting range"
                            }
                        }
                    }
                updatedBody != note.body -> emptyList()
                else -> note.spans
            }
        val updatedItems =
            items?.let {
                parseArray(it) { array ->
                    repeat(array.length()) { index ->
                        val item = array.getJSONObject(index)
                        require(item.get("body") is String) { "Each checklist item needs a body" }
                        if (item.has("checked")) item.getBoolean("checked")
                        if (item.has("isChild")) item.getBoolean("isChild")
                    }
                    Converters.jsonToItems(array).also { parsed ->
                        require(parsed.firstOrNull()?.isChild != true) {
                            "Checklist starts with a child item"
                        }
                    }
                }
            } ?: note.items
        return note.copy(
            title = title ?: note.title,
            body = updatedBody,
            spans = updatedSpans,
            items = updatedItems,
            modifiedTimestamp = maxOf(System.currentTimeMillis(), note.modifiedTimestamp + 1),
        )
    }

    private fun <T> parseArray(json: String, parse: (JSONArray) -> T): T =
        try {
            parse(JSONArray(json))
        } catch (e: JSONException) {
            throw IllegalArgumentException("Invalid JSON array", e)
        }

    companion object {
        private val TEXT_FIELDS = setOf("title", "body")
        private val ARRAY_FIELDS = setOf("spans", "items")
        private val EDITABLE_FIELDS = TEXT_FIELDS + ARRAY_FIELDS

        private fun decodePatch(input: ContentValues): ContentValues {
            require(input.size() == 1) { "Bind patch on its own" }
            val encoded =
                input["patch"] as? String ?: throw IllegalArgumentException("Use a string patch")
            val json =
                try {
                    JSONObject(Base64.decode(encoded, Base64.DEFAULT).toString(Charsets.UTF_8))
                } catch (e: JSONException) {
                    throw IllegalArgumentException("Patch must contain a JSON object", e)
                }
            return ContentValues().apply {
                json.keys().forEach { key ->
                    val value = json.get(key)
                    when {
                        key in TEXT_FIELDS && value is String -> put(key, value)
                        key in ARRAY_FIELDS && value is JSONArray -> put(key, value.toString())
                        else -> throw IllegalArgumentException("Invalid patch field: $key")
                    }
                }
            }
        }
    }
}
