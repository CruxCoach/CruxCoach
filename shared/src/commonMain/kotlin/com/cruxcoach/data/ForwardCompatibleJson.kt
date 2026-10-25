package com.cruxcoach.data

import com.cruxcoach.athlete.data.AthleteSnapshot
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reading data a newer app version wrote. `ignoreUnknownKeys` already skips new
 * fields; a new *value* of an enum (a new kind of climbing day, a new piece of
 * equipment) would still fail the whole decode. [knownValuesOnly] removes what
 * this version cannot read, guided by the serializer's descriptor:
 *
 * - an unknown entry of a list or set is left out;
 * - an unknown value of a field with a default falls back to the default
 *   (a nullable field becomes null);
 * - an unknown value of a required field leaves out the whole object it is in,
 *   e.g. that one climbing day – the rest of the list stays.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object ForwardCompatibleJson {

    /** [element] with only the values [descriptor] knows; null when the element itself cannot be read. */
    fun knownValuesOnly(element: JsonElement, descriptor: SerialDescriptor): JsonElement? {
        if (element is JsonNull) return element
        if (descriptor.isInline) return knownValuesOnly(element, descriptor.getElementDescriptor(0))
        return when (descriptor.kind) {
            SerialKind.ENUM -> element.takeIf {
                it is JsonPrimitive && it.isString && descriptor.getElementIndex(it.content) != CompositeDecoder.UNKNOWN_NAME
            }
            StructureKind.LIST -> (element as? JsonArray)?.let { array ->
                val item = descriptor.getElementDescriptor(0)
                JsonArray(array.mapNotNull { knownValuesOnly(it, item) })
            } ?: element
            StructureKind.MAP -> (element as? JsonObject)?.let { map ->
                val key = descriptor.getElementDescriptor(0)
                val value = descriptor.getElementDescriptor(1)
                JsonObject(map.entries.mapNotNull { (k, v) ->
                    if (key.kind == SerialKind.ENUM && key.getElementIndex(k) == CompositeDecoder.UNKNOWN_NAME) null
                    else knownValuesOnly(v, value)?.let { k to it }
                }.toMap())
            } ?: element
            StructureKind.CLASS, StructureKind.OBJECT -> (element as? JsonObject)?.let { obj ->
                val out = LinkedHashMap<String, JsonElement>()
                for ((name, value) in obj) {
                    val index = descriptor.getElementIndex(name)
                    if (index == CompositeDecoder.UNKNOWN_NAME) {
                        out[name] = value // ignoreUnknownKeys skips it
                        continue
                    }
                    val child = knownValuesOnly(value, descriptor.getElementDescriptor(index))
                    when {
                        child != null -> out[name] = child
                        descriptor.isElementOptional(index) -> Unit // its default
                        descriptor.getElementDescriptor(index).isNullable -> out[name] = JsonNull
                        else -> return null
                    }
                }
                JsonObject(out)
            } ?: element
            else -> element
        }
    }
}

/**
 * The athlete part of a backup (training, body, nutrition), read so that a
 * backup from a newer version still restores: values this version does not
 * know are left out ([ForwardCompatibleJson]); if the part still cannot be
 * read, it is skipped and everything else in the backup – logbook, lists, own
 * climbs – restores anyway. Writing is unchanged.
 */
internal object ForwardCompatibleAthleteSnapshotSerializer : KSerializer<AthleteSnapshot> {
    private val strict = AthleteSnapshot.serializer()
    override val descriptor: SerialDescriptor = strict.descriptor

    override fun serialize(encoder: Encoder, value: AthleteSnapshot) = strict.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): AthleteSnapshot {
        val json = decoder as? JsonDecoder ?: return strict.deserialize(decoder)
        val element = json.decodeJsonElement()
        return try {
            json.json.decodeFromJsonElement(strict, element)
        } catch (e: IllegalArgumentException) { // SerializationException is one
            val known = ForwardCompatibleJson.knownValuesOnly(element, strict.descriptor) ?: return AthleteSnapshot()
            runCatching { json.json.decodeFromJsonElement(strict, known) }.getOrDefault(AthleteSnapshot())
        }
    }
}
