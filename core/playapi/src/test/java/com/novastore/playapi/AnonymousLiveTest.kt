package com.novastore.playapi

import com.novastore.playapi.helpers.AppDetailsHelper
import com.novastore.playapi.helpers.PurchaseHelper
import com.novastore.playapi.helpers.SearchHelper
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Live end-to-end check of the anonymous Play tier (dispenser → details →
 * bulk details → search → delivery URL). Hits the network, so it only runs
 * with -Dnova.live=true.
 */
class AnonymousLiveTest {

    @Test
    fun anonymousSessionDeliversPlayFiles() {
        assumeTrue(System.getProperty("nova.live") == "true")

        val auth = AnonymousAuth.login(AnonymousAuth.DEFAULT_DISPENSERS, System.getProperty("nova.profile") ?: "px_3a.properties")
        println("anonymous session: ${auth.email} gsf=${auth.gsfId}")

        val details = AppDetailsHelper.with(auth).getAppByPackageName("org.telegram.messenger")!!
        println("details: ${details.displayName} ${details.versionName} (${details.versionCode})")
        check(details.versionCode > 0)

        val bulk = AppDetailsHelper.with(auth).getAppByPackageName(listOf("com.whatsapp", "com.spotify.music"))
        bulk.forEach { println("bulk: ${it.packageName} ${it.versionName} (${it.versionCode})") }
        check(bulk.any { it.versionCode > 0 })

        val search = runCatching { SearchHelper.with(auth).searchResults("vpn").appList }.getOrDefault(mutableListOf())
        println("search: ${search.size} apps, first=${search.firstOrNull()?.packageName}")
        val helper = SearchHelper.with(auth)
        var bundle = runCatching { helper.searchResults("vpn") }.onFailure { println("search paged: $it") }.getOrNull() ?: com.novastore.playapi.data.models.SearchBundle()
        val all = LinkedHashSet<String>()
        bundle.appList.forEach { all += it.packageName }
        repeat(2) {
            val pages = bundle.subBundles.filter { it.nextPageUrl.isNotBlank() }.toMutableSet()
            if (pages.isEmpty()) return@repeat
            bundle = runCatching { helper.next(pages) }.getOrElse { println("search paged: stop ($it)"); return@repeat }
            bundle.appList.forEach { all += it.packageName }
        }
        println("search paged: ${all.size} apps")

        val files = PurchaseHelper.with(auth).purchase(details.packageName, details.versionCode, details.offerType.takeIf { it > 0 } ?: 1)
        files.forEach { println("file: ${it.type} ${it.name} ${it.size} ${it.url.take(80)}") }
        val base = files.first { it.type == com.novastore.playapi.data.models.File.FileType.BASE }
        check(base.url.isNotBlank())
        // The delivery URL must be fetchable with a plain request (no Play headers).
        okhttp3.OkHttpClient().newCall(
            okhttp3.Request.Builder().url(base.url).header("Range", "bytes=0-3").build(),
        ).execute().use {
            val magic = it.body!!.bytes()
            println("probe: HTTP ${it.code} bytes=${magic.size} zip=${magic.size >= 2 && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()}")
            check(it.isSuccessful && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte())
        }
    }
}
