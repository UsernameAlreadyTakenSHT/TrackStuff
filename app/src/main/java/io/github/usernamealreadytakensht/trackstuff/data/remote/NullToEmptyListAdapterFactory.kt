package io.github.usernamealreadytakensht.trackstuff.data.remote

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.lang.reflect.Type

/**
 * Reads a JSON `null` as an empty list. TVDB returns `"characters": null`, `"releases": null`…
 * for sparse records, which made the whole record fail to parse
 * ("Non-null value 'characters' was null") and lost the TVDB poster and synopsis.
 */
class NullToEmptyListAdapterFactory : JsonAdapter.Factory {
    override fun create(type: Type, annotations: Set<Annotation>, moshi: Moshi): JsonAdapter<*>? {
        if (annotations.isNotEmpty() || Types.getRawType(type) != List::class.java) return null
        val delegate = moshi.nextAdapter<List<Any?>>(this, type, annotations)
        return object : JsonAdapter<List<Any?>>() {
            override fun fromJson(reader: JsonReader): List<Any?> =
                if (reader.peek() == JsonReader.Token.NULL) { reader.nextNull<Unit>(); emptyList() } else delegate.fromJson(reader) ?: emptyList()

            override fun toJson(writer: JsonWriter, value: List<Any?>?) = delegate.toJson(writer, value)
        }
    }
}
