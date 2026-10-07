package com.lifedashboard

import android.app.Notification
import android.os.Bundle
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

/** Best-effort capture: a broken optional field must not discard the remaining raw payload. */
internal object NotificationPayload {
    fun create(sbn: StatusBarNotification): JSONObject {
        val n = sbn.notification
        return JSONObject().put("schemaVersion", 1).put("package", sbn.packageName).put("key", sbn.key)
            .put("postTime", sbn.postTime).put("notificationWhen", n.`when`).put("id", sbn.id).put("tag", sbn.tag)
            .put("groupSummary", n.flags and Notification.FLAG_GROUP_SUMMARY != 0)
            .put("ongoing", sbn.isOngoing).put("category", n.category)
            .also { captureExtras(n.extras ?: Bundle.EMPTY, it) }
    }

    internal fun captureExtras(extras: Bundle, result: JSONObject = JSONObject()): JSONObject {
        val errors = JSONArray()
        fun failure(path: String, e: Exception): JSONObject {
            errors.put(JSONObject().put("field", path).put("error", e.javaClass.simpleName))
            return JSONObject().put("unavailable", true).put("error", e.javaClass.simpleName)
        }
        fun safe(path: String, block: () -> Any): Any = try { block() } catch (e: Exception) { failure(path, e) }
        fun value(input: Any?, path: String, depth: Int): Any {
            require(depth <= 32) { "Nested extras limit" }
            return when (input) {
                null -> JSONObject.NULL
                is Bundle -> JSONObject().also { json ->
                    input.keySet().sorted().forEach { key ->
                        json.put(key, safe("$path.$key") {
                            @Suppress("DEPRECATION")
                            value(input.get(key), "$path.$key", depth + 1)
                        })
                    }
                }
                is CharSequence -> input.toString()
                is Number -> input.also { require(it.toDouble().isFinite()) }
                is Boolean -> input
                is Array<*> -> JSONArray(input.mapIndexed { i, item -> safe("$path[$i]") { value(item, "$path[$i]", depth + 1) } })
                is Iterable<*> -> JSONArray(input.mapIndexed { i, item -> safe("$path[$i]") { value(item, "$path[$i]", depth + 1) } })
                else -> if (input.javaClass.isArray) JSONArray((0 until java.lang.reflect.Array.getLength(input)).map { i ->
                    safe("$path[$i]") { value(java.lang.reflect.Array.get(input, i), "$path[$i]", depth + 1) }
                }) else input.toString()
            }
        }
        fun text(key: String): String = try {
            extras.getCharSequence(key)?.toString().orEmpty()
        } catch (e: Exception) { failure(key, e); "" }
        result.put("title", text(Notification.EXTRA_TITLE))
            .put("text", text(Notification.EXTRA_BIG_TEXT).ifEmpty { text(Notification.EXTRA_TEXT) })
            .put("extras", safe("extras") { value(extras, "extras", 0) })
        if (errors.length() > 0) result.put("captureErrors", errors)
        return result
    }
}
