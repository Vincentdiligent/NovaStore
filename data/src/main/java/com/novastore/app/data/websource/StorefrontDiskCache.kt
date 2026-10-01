package com.novastore.app.data.websource

import android.content.Context
import com.novastore.app.core.model.RemoteApp
import com.novastore.app.core.model.SOURCE_PLAY_WEB
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parsed Play storefront shelves on disk (a few KB each instead of MBs of
 * HTML). Home renders instantly from here on the next launch; the network
 * refresh only happens once the entry is older than [TTL_MS].
 */
@Singleton
class StorefrontDiskCache @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val dir = File(context.cacheDir, "storefront_v2").apply { mkdirs() }

    private fun file(key: String) = File(dir, key.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".json")

    /** Cached shelf and whether it is still fresh; null when absent/corrupt. */
    fun read(key: String): Pair<List<RemoteApp>, Boolean>? = runCatching {
        val f = file(key)
        if (!f.exists()) return null
        val fresh = System.currentTimeMillis() - f.lastModified() < TTL_MS
        val array = JSONArray(f.readText())
        val apps = (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            RemoteApp(
                packageName = o.getString("p"),
                name = o.getString("n"),
                summary = o.optString("s").takeIf { it.isNotBlank() },
                developer = o.optString("d").takeIf { it.isNotBlank() },
                iconUrl = o.optString("i").takeIf { it.isNotBlank() },
                license = null,
                categories = listOfNotNull(o.optString("c").takeIf { it.isNotBlank() }),
                source = SOURCE_PLAY_WEB,
                rating = o.optDouble("r", -1.0).takeIf { it > 0 }?.toFloat(),
                downloads = o.optLong("dl", -1).takeIf { it > 0 },
                sizeBytes = o.optLong("sz", -1).takeIf { it > 0 },
                isFree = o.optBoolean("f", true),
            )
        }
        apps to fresh
    }.getOrNull()

    fun write(key: String, apps: List<RemoteApp>) {
        if (apps.isEmpty()) return
        runCatching {
            val array = JSONArray()
            apps.forEach { a ->
                array.put(
                    JSONObject()
                        .put("p", a.packageName)
                        .put("n", a.name)
                        .put("s", a.summary ?: "")
                        .put("d", a.developer ?: "")
                        .put("i", a.iconUrl ?: "")
                        .put("c", a.categories.firstOrNull() ?: "")
                        .put("r", (a.rating ?: -1f).toDouble())
                        .put("dl", a.downloads ?: -1)
                        .put("sz", a.sizeBytes ?: -1)
                        .put("f", a.isFree),
                )
            }
            file(key).writeText(array.toString())
        }
    }

    private companion object {
        const val TTL_MS = 6L * 60 * 60 * 1000
    }
}
