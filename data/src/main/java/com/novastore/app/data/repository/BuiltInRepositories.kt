package com.novastore.app.data.repository

/**
 * F-Droid compatible repositories shipped with Nova Store. Every entry was
 * checked to serve a live index. Users can disable any of them in Settings.
 */
internal data class BuiltInRepository(
    val id: String,
    val name: String,
    val url: String,
    val enabledByDefault: Boolean = true,
)

internal val BUILT_IN_REPOSITORIES = listOf(
    BuiltInRepository("fdroid", "F-Droid", "https://f-droid.org/repo"),
    BuiltInRepository("izzyondroid", "IzzyOnDroid", "https://apt.izzysoft.de/fdroid/repo"),
    BuiltInRepository("guardianproject", "Guardian Project", "https://guardianproject.info/fdroid/repo"),
    BuiltInRepository("microg", "microG", "https://microg.org/fdroid/repo"),
    BuiltInRepository("newpipe", "NewPipe", "https://archive.newpipe.net/fdroid/repo"),
    BuiltInRepository("molly", "Molly", "https://molly.im/fdroid/foss/fdroid/repo"),
    BuiltInRepository("briar", "Briar", "https://briarproject.org/fdroid/repo"),
    BuiltInRepository("kde", "KDE", "https://cdn.kde.org/android/stable-releases/fdroid/repo"),
    BuiltInRepository("threema", "Threema Libre", "https://releases.threema.ch/fdroid/repo"),
    BuiltInRepository("session", "Session", "https://fdroid.getsession.org/fdroid/repo"),
    BuiltInRepository("bitwarden", "Bitwarden", "https://mobileapp.bitwarden.com/fdroid/repo"),
    BuiltInRepository("calyxos", "CalyxOS", "https://calyxos.gitlab.io/calyx-fdroid-repo/fdroid/repo"),
    BuiltInRepository("ironfox", "IronFox", "https://fdroid.ironfoxoss.org/fdroid/repo"),
    BuiltInRepository("simplex", "SimpleX Chat", "https://app.simplex.chat/fdroid/repo"),
    BuiltInRepository("schildichat", "SchildiChat", "https://s2.spiritcroc.de/fdroid/repo"),
    BuiltInRepository("collabora", "Collabora Office", "https://www.collaboraoffice.com/downloads/fdroid/repo"),
    BuiltInRepository("gadgetbridge", "Gadgetbridge (nightly)", "https://freeyourgadget.codeberg.page/fdroid/repo"),
    BuiltInRepository("fedilab", "Fedilab", "https://fdroid.fedilab.app/repo"),
    BuiltInRepository("i2pd", "PurpleI2P", "https://fdroid.i2pd.xyz/fdroid/repo"),
    BuiltInRepository("cakewallet", "Cake Wallet", "https://fdroid.cakelabs.com"),
    BuiltInRepository("twinhelix", "TwinHelix (Signal FOSS)", "https://fdroid.twinhelix.com/fdroid/repo"),
    BuiltInRepository("typeblog", "Typeblog", "https://fdroid.typeblog.net"),
    BuiltInRepository("metatrans", "Metatrans Apps", "https://fdroid.metatransapps.com/fdroid/repo"),
    BuiltInRepository("anonymousmessenger", "Anonymous Messenger", "https://anonymousmessenger.ly/fdroid/repo"),
    BuiltInRepository("nanolx", "Nanolx", "https://nanolx.org/fdroid/repo"),
    BuiltInRepository("libretro", "RetroArch", "https://fdroid.libretro.com/repo"),
    BuiltInRepository("xarantolus", "GitHub releases (xarantolus)", "https://raw.githubusercontent.com/xarantolus/fdroid/main/fdroid/repo"),
    // Large or specialised: available but off until the user turns them on.
    BuiltInRepository("fdroid-archive", "F-Droid Archive (old versions)", "https://f-droid.org/archive", enabledByDefault = false),
    BuiltInRepository("nethunter", "Kali NetHunter Store", "https://store.nethunter.com/repo", enabledByDefault = false),
)
