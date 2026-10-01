package com.novastore.app.core.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Nova Store brand palette.
 *
 * Dark-first premium Material 3: deep charcoal backgrounds with an emerald
 * accent. Amber is reserved for secondary highlights, violet only for charts.
 * The user-selectable accent palettes live in [AccentPalettes].
 */

// Brand emerald — primary accent.
val EmeraldLight = Color(0xFF00C383) // primary in light mode
val EmeraldDark = Color(0xFF00B377) // primary in dark mode
val EmeraldContainerDark = Color(0xFF06392A)
val OnEmeraldContainerDark = Color(0xFF7BFFCF)
val EmeraldContainerLight = Color(0xFFA5F5D6)
val OnEmeraldContainerLight = Color(0xFF002B1E)

/** Text/icons drawn on top of the accent gradient — bright, not dark. */
val OnEmerald = Color(0xFFFFFFFF)

// Gradient partners: emerald -> teal.
val Teal = Color(0xFF00A8A8)

// Secondary amber.
val Amber = Color(0xFFFFB454)
val OnAmber = Color(0xFF332004)
val AmberContainerDark = Color(0xFF42300B)
val OnAmberContainerDark = Color(0xFFFFD9A0)
val AmberContainerLight = Color(0xFFFFDDB3)
val OnAmberContainerLight = Color(0xFF2B2000)

// Tertiary violet (charts / data viz only).
val Violet = Color(0xFFC9B8FF)

// Dark surfaces.
val Night0 = Color(0xFF0E1013) // background
val Night1 = Color(0xFF16191E) // surfaceContainer
val Night2 = Color(0xFF1D2127) // surfaceContainerHigh
val Night3 = Color(0xFF23272E) // surfaceContainerHighest
val NightVariant = Color(0xFF262B33)
val OnNight = Color(0xFFE4E7EB)
val OnNightVariant = Color(0xFF9BA6AF)
val NightOutline = Color(0xFF454C55)
val NightOutlineVariant = Color(0xFF2A2F36)

// Light surfaces.
val Paper = Color(0xFFF7FAF8)
val PaperContainerLowest = Color(0xFFFFFFFF)
val PaperContainerLow = Color(0xFFF1F5F2)
val PaperContainer = Color(0xFFEBEFEA)
val PaperContainerHigh = Color(0xFFE5EAE4)
val PaperContainerHighest = Color(0xFFDFE5DE)
val PaperVariant = Color(0xFFDFE5E1)
val OnPaper = Color(0xFF16191E)
val OnPaperVariant = Color(0xFF414947)
val PaperOutline = Color(0xFF71787A)
val PaperOutlineVariant = Color(0xFFC1C9C6)

// AMOLED surfaces (pure black theme).
val Amoled0 = Color(0xFF000000)
val Amoled1 = Color(0xFF0C0E10)
val Amoled2 = Color(0xFF141719)
val Amoled3 = Color(0xFF1B1E21)
val AmoledVariant = Color(0xFF202427)
val AmoledOutline = Color(0xFF3D444C)
val AmoledOutlineVariant = Color(0xFF24282C)

/**
 * A selectable accent palette: everything the theme and the gradients need.
 */
data class NovaAccent(
    /** Gradient start (the "primary" brand color). */
    val start: Color,
    /** Gradient end. */
    val end: Color,
    /** Material primary in light mode. */
    val primaryLight: Color,
    /** Material primary in dark mode. */
    val primaryDark: Color,
    val containerLight: Color,
    val onContainerLight: Color,
    val containerDark: Color,
    val onContainerDark: Color,
)

/** Gradient + Material colors for every [com.novastore.app.core.model.AccentPalette]. */
object AccentPalettes {
    val EMERALD = NovaAccent(
        start = Color(0xFF00B377),
        end = Color(0xFF00A8A8),
        primaryLight = EmeraldLight,
        primaryDark = EmeraldDark,
        containerLight = EmeraldContainerLight,
        onContainerLight = OnEmeraldContainerLight,
        containerDark = EmeraldContainerDark,
        onContainerDark = OnEmeraldContainerDark,
    )
    val OCEAN = NovaAccent(
        start = Color(0xFF0E9CB0),
        end = Color(0xFF0A7E96),
        primaryLight = Color(0xFF0E97AC),
        primaryDark = Color(0xFF2FB8CB),
        containerLight = Color(0xFFC8F0F6),
        onContainerLight = Color(0xFF03303A),
        containerDark = Color(0xFF073B45),
        onContainerDark = Color(0xFFA9EBF5),
    )
    val VIOLET = NovaAccent(
        start = Color(0xFF8B5CF6),
        end = Color(0xFF6D28D9),
        primaryLight = Color(0xFF7C4DF0),
        primaryDark = Color(0xFFB39DFF),
        containerLight = Color(0xFFE9DDFF),
        onContainerLight = Color(0xFF271266),
        containerDark = Color(0xFF392B69),
        onContainerDark = Color(0xFFE4DBFF),
    )
    val AMBER = NovaAccent(
        start = Color(0xFFD97706),
        end = Color(0xFFB45309),
        primaryLight = Color(0xFFB45309),
        primaryDark = Color(0xFFF5B04C),
        containerLight = Color(0xFFFFE3B8),
        onContainerLight = Color(0xFF3B2404),
        containerDark = Color(0xFF453009),
        onContainerDark = Color(0xFFFFDCA8),
    )
    val ROSE = NovaAccent(
        start = Color(0xFFE11D48),
        end = Color(0xFF9F1239),
        primaryLight = Color(0xFFC21442),
        primaryDark = Color(0xFFFF7A9A),
        containerLight = Color(0xFFFFDDE4),
        onContainerLight = Color(0xFF55071A),
        containerDark = Color(0xFF570B20),
        onContainerDark = Color(0xFFFFDAE1),
    )
    val LIME = NovaAccent(
        start = Color(0xFF65A30D),
        end = Color(0xFF3F6212),
        primaryLight = Color(0xFF58910B),
        primaryDark = Color(0xFFAEDB58),
        containerLight = Color(0xFFE2F3C2),
        onContainerLight = Color(0xFF233502),
        containerDark = Color(0xFF28350A),
        onContainerDark = Color(0xFFD3F08E),
    )

    val NOVA = NovaAccent(
        start = Color(0xFF6965F1),
        end = Color(0xFFA556F7),
        primaryLight = Color(0xFF5B57E8),
        primaryDark = Color(0xFFB3B0FF),
        containerLight = Color(0xFFE3E1FF),
        onContainerLight = Color(0xFF1B1664),
        containerDark = Color(0xFF34307A),
        onContainerDark = Color(0xFFE3E1FF),
    )
    val SAPPHIRE = NovaAccent(
        start = Color(0xFF2563EB),
        end = Color(0xFF1D4ED8),
        primaryLight = Color(0xFF1D4ED8),
        primaryDark = Color(0xFF93B4FF),
        containerLight = Color(0xFFDBE6FF),
        onContainerLight = Color(0xFF08205C),
        containerDark = Color(0xFF16306E),
        onContainerDark = Color(0xFFDBE6FF),
    )
    val SKY = NovaAccent(
        start = Color(0xFF0EA5E9),
        end = Color(0xFF0284C7),
        primaryLight = Color(0xFF0284C7),
        primaryDark = Color(0xFF7DD3FC),
        containerLight = Color(0xFFD6F1FF),
        onContainerLight = Color(0xFF03324A),
        containerDark = Color(0xFF0A3A55),
        onContainerDark = Color(0xFFD6F1FF),
    )
    val TEAL = NovaAccent(
        start = Color(0xFF14B8A6),
        end = Color(0xFF0F766E),
        primaryLight = Color(0xFF0F8F82),
        primaryDark = Color(0xFF5EEAD4),
        containerLight = Color(0xFFCCF5EF),
        onContainerLight = Color(0xFF02302B),
        containerDark = Color(0xFF0B3D38),
        onContainerDark = Color(0xFFCCF5EF),
    )
    val PINK = NovaAccent(
        start = Color(0xFFEC4899),
        end = Color(0xFFBE185D),
        primaryLight = Color(0xFFD02C80),
        primaryDark = Color(0xFFFF8CC4),
        containerLight = Color(0xFFFFD9EB),
        onContainerLight = Color(0xFF4D0526),
        containerDark = Color(0xFF5A1235),
        onContainerDark = Color(0xFFFFD9EB),
    )
    val CORAL = NovaAccent(
        start = Color(0xFFF97316),
        end = Color(0xFFEA580C),
        primaryLight = Color(0xFFD9570B),
        primaryDark = Color(0xFFFFA764),
        containerLight = Color(0xFFFFE0CC),
        onContainerLight = Color(0xFF4A1C02),
        containerDark = Color(0xFF55270C),
        onContainerDark = Color(0xFFFFE0CC),
    )
    val CRIMSON = NovaAccent(
        start = Color(0xFFDC2626),
        end = Color(0xFF991B1B),
        primaryLight = Color(0xFFC21F1F),
        primaryDark = Color(0xFFFF8A80),
        containerLight = Color(0xFFFFDAD6),
        onContainerLight = Color(0xFF4A0707),
        containerDark = Color(0xFF5C1414),
        onContainerDark = Color(0xFFFFDAD6),
    )
    val GOLD = NovaAccent(
        start = Color(0xFFEAB308),
        end = Color(0xFFCA8A04),
        primaryLight = Color(0xFFA87204),
        primaryDark = Color(0xFFFACC15),
        containerLight = Color(0xFFFFF0B8),
        onContainerLight = Color(0xFF3A2A00),
        containerDark = Color(0xFF4A3900),
        onContainerDark = Color(0xFFFFF0B8),
    )
    val GRAPHITE = NovaAccent(
        start = Color(0xFF64748B),
        end = Color(0xFF334155),
        primaryLight = Color(0xFF475569),
        primaryDark = Color(0xFFB8C4D4),
        containerLight = Color(0xFFE2E8F0),
        onContainerLight = Color(0xFF1E293B),
        containerDark = Color(0xFF2B3544),
        onContainerDark = Color(0xFFE2E8F0),
    )

    fun of(palette: com.novastore.app.core.model.AccentPalette): NovaAccent = when (palette) {
        com.novastore.app.core.model.AccentPalette.EMERALD -> EMERALD
        com.novastore.app.core.model.AccentPalette.OCEAN -> OCEAN
        com.novastore.app.core.model.AccentPalette.VIOLET -> VIOLET
        com.novastore.app.core.model.AccentPalette.AMBER -> AMBER
        com.novastore.app.core.model.AccentPalette.ROSE -> ROSE
        com.novastore.app.core.model.AccentPalette.LIME -> LIME
        com.novastore.app.core.model.AccentPalette.NOVA -> NOVA
        com.novastore.app.core.model.AccentPalette.SAPPHIRE -> SAPPHIRE
        com.novastore.app.core.model.AccentPalette.SKY -> SKY
        com.novastore.app.core.model.AccentPalette.TEAL -> TEAL
        com.novastore.app.core.model.AccentPalette.PINK -> PINK
        com.novastore.app.core.model.AccentPalette.CORAL -> CORAL
        com.novastore.app.core.model.AccentPalette.CRIMSON -> CRIMSON
        com.novastore.app.core.model.AccentPalette.GOLD -> GOLD
        com.novastore.app.core.model.AccentPalette.GRAPHITE -> GRAPHITE
    }
}
