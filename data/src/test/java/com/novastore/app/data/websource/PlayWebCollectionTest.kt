package com.novastore.app.data.websource

import com.novastore.app.core.common.DispatcherProvider
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Parses saved public Play storefront pages (path via -Dnova.pages=dir). */
class PlayWebCollectionTest {
    private val client = PlayWebClient(
        OkHttpClient(),
        object : DispatcherProvider {
            override val io: CoroutineDispatcher = Dispatchers.IO
            override val default: CoroutineDispatcher = Dispatchers.Default
            override val main: CoroutineDispatcher = Dispatchers.Unconfined
        },
    )

    @Test
    fun storefrontPagesYieldApps() {
        val dir = System.getProperty("nova.pages")
        assumeTrue(!dir.isNullOrBlank())
        for (name in listOf("apps_home.html", "cat_game.html")) {
            val html = File(dir, name).readText()
            val apps = client.parseApps(html, 150)
            println("$name: ${apps.size} apps; sample=${apps.take(5).map { "${it.name}(${it.packageName}) ★${it.rating} icon=${it.iconUrl != null}" }}")
            check(apps.size >= 20)
        }
    }
}
