package com.disunjun.komunikasigroup.communication

import org.json.JSONArray
import org.json.JSONObject

/**
 * Converts org.json trees into plain Map/List structures so the pure domain
 * parsers can operate without Android types. Not for credential logging — the
 * resulting maps hold (short-lived) TURN credentials and must stay in memory.
 */
internal fun JSONObject.toFlatMap(): Map<String, Any?> {
    val keys = keys()
    return buildMap {
        while (keys.hasNext()) {
            val key = keys.next()
            val value = opt(key)
            when (value) {
                is JSONObject -> put(key, value.toFlatMap())
                is JSONArray -> put(key, value.toFlatList())
                JSONObject.NULL -> put(key, null)
                else -> put(key, value)
            }
        }
    }
}

private fun JSONArray.toFlatList(): List<Any?> = buildList {
    for (i in 0 until length()) {
        when (val item = opt(i)) {
            is JSONObject -> add(item.toFlatMap())
            is JSONArray -> add(item.toFlatList())
            JSONObject.NULL -> add(null)
            else -> add(item)
        }
    }
}