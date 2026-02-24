package com.citigroup.copilotchat.mcp

import kotlinx.serialization.json.*

/**
 * Fixes JSON Schema constructs that the Copilot language server rejects.
 * Port of Python's _sanitize_schema() from mcp.py.
 *
 * MCP servers return rich JSON Schema (anyOf, oneOf, array-typed "type")
 * that the Copilot server doesn't accept. This sanitizer flattens them
 * into simple single-type schemas recursively.
 */
object SchemaSanitizer {

    /**
     * Recursively sanitize a JSON Schema object:
     * - Converts array-typed "type" (e.g. ["object", "null"]) to a string
     * - Flattens anyOf/oneOf unions into a single type
     * - Ensures every property has a "type" field
     * - Recursively processes properties, items, additionalProperties
     */
    fun sanitize(schema: JsonObject): JsonObject {
        val mutable = schema.toMutableMap()

        // Handle anyOf / oneOf
        for (keyword in listOf("anyOf", "oneOf")) {
            val variants = mutable[keyword]?.jsonArray
            if (variants != null) {
                val types = variants
                    .mapNotNull { it as? JsonObject }
                    .mapNotNull { v ->
                        val t = v["type"]?.jsonPrimitive?.contentOrNull
                        if (t != null && t != "null") t else null
                    }
                // Collect extra fields from the first non-null variant
                val extra = mutableMapOf<String, JsonElement>()
                for (v in variants) {
                    val obj = v as? JsonObject ?: continue
                    val t = obj["type"]?.jsonPrimitive?.contentOrNull
                    if (t != null && t != "null") {
                        for ((k, value) in obj) {
                            if (k != "type") extra[k] = value
                        }
                        break
                    }
                }

                mutable.remove(keyword)
                mutable["type"] = JsonPrimitive(types.firstOrNull() ?: "string")
                mutable.putAll(extra)
            }
        }

        // Handle array "type" (e.g. ["object", "null"])
        val typeEl = mutable["type"]
        if (typeEl is JsonArray) {
            val types = typeEl.mapNotNull {
                val t = it.jsonPrimitive.contentOrNull
                if (t != null && t != "null") t else null
            }
            mutable["type"] = JsonPrimitive(types.firstOrNull() ?: "string")
        }

        // Ensure "type" exists
        if ("type" !in mutable && "properties" !in mutable) {
            mutable["type"] = JsonPrimitive("string")
        }

        // Recursively sanitize properties
        val properties = mutable["properties"]?.jsonObject
        if (properties != null) {
            val sanitizedProps = buildJsonObject {
                for ((propName, propValue) in properties) {
                    if (propValue is JsonObject) {
                        put(propName, sanitize(propValue))
                    } else {
                        put(propName, propValue)
                    }
                }
            }
            mutable["properties"] = sanitizedProps
        }

        // Recursively sanitize items and additionalProperties
        for (kw in listOf("items", "additionalProperties")) {
            val nested = mutable[kw]
            if (nested is JsonObject) {
                mutable[kw] = sanitize(nested)
            }
        }

        return JsonObject(mutable)
    }
}
