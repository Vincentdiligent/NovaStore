package com.novastore.app.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class StoreLinksTest {

    private fun app(pkg: String) = StoreLink.App(pkg)

    @Test
    fun playLinks() {
        assertEquals(app("org.telegram.messenger"), StoreLinks.parse("https://play.google.com/store/apps/details?id=org.telegram.messenger&hl=ru"))
        assertEquals(app("com.whatsapp"), StoreLinks.parse("market://details?id=com.whatsapp"))
        assertEquals(StoreLink.Search("vpn"), StoreLinks.parse("market://search?q=vpn"))
        assertEquals(app("com.spotify.music"), StoreLinks.parse("market://search?q=pname:com.spotify.music"))
    }

    @Test
    fun sharedText() {
        // Play's share sheet: title + URL.
        assertEquals(
            app("com.airbnb.android"),
            StoreLinks.parse("Попробуйте Airbnb https://play.google.com/store/apps/details?id=com.airbnb.android"),
        )
    }

    @Test
    fun fdroidAndMirrors() {
        assertEquals(app("org.fdroid.fdroid"), StoreLinks.parse("https://f-droid.org/en/packages/org.fdroid.fdroid/"))
        assertEquals(app("com.nextcloud.client"), StoreLinks.parse("https://apt.izzysoft.de/fdroid/index/apk/com.nextcloud.client"))
        assertEquals(app("org.telegram.messenger"), StoreLinks.parse("https://apkpure.com/telegram/org.telegram.messenger"))
    }

    @Test
    fun wrappedLinks() {
        assertEquals(
            app("com.duolingo"),
            StoreLinks.parse("https://play.app.goo.gl/?link=https%3A%2F%2Fplay.google.com%2Fstore%2Fapps%2Fdetails%3Fid%3Dcom.duolingo&ddl=1"),
        )
    }

    @Test
    fun barePackageAndText() {
        assertEquals(app("network.loki.messenger"), StoreLinks.parse("network.loki.messenger"))
        assertEquals(StoreLink.Search("Hello world"), StoreLinks.parse("Hello world"))
    }
}
