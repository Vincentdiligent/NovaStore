package com.novastore.app.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.novastore.app.core.ui.R

/**
 * Curated, localized store categories.
 *
 * F-Droid indices expose 120+ niche categories ("Cast", "Dice", "Visual
 * Novel", …) which turn the Home chips row into noise. Nova Store keeps a
 * curated whitelist of the well-known top-level ones and localizes them;
 * unknown categories fall back to their raw name.
 */
object NovaCategories {

    /** Popular top-level categories, display order. */
    val CURATED = listOf(
        "Internet",
        "Games",
        "System",
        "Multimedia",
        "Security",
        "Development",
        "Navigation",
        "Reading",
        "Writing",
        "Science & Education",
        "Phone & SMS",
        "Theming",
        "Graphics",
        "Sports & Health",
        "Time",
        "Money",
        "Connectivity",
    )

    /**
     * Picks the categories to show for the given raw list: the curated
     * intersection (in curated order) or, when the repository only speaks
     * an exotic taxonomy, the raw list capped to a readable number.
     */
    fun select(raw: List<String>): List<String> {
        val lowerSet = raw.map { it.trim().lowercase() }.toSet()
        val curated = CURATED.filter { it.lowercase() in lowerSet }
        return if (curated.size >= 5) curated else raw.distinct().take(12)
    }

    /** Localized display label for a raw category name. */
    @Composable
    fun label(raw: String): String = when (raw.trim().lowercase()) {
        "internet" -> stringResource(R.string.cat_internet)
        "games" -> stringResource(R.string.cat_games)
        "system" -> stringResource(R.string.cat_system)
        "multimedia" -> stringResource(R.string.cat_multimedia)
        "security" -> stringResource(R.string.cat_security)
        "development" -> stringResource(R.string.cat_development)
        "navigation" -> stringResource(R.string.cat_navigation)
        "reading" -> stringResource(R.string.cat_reading)
        "writing" -> stringResource(R.string.cat_writing)
        "science & education" -> stringResource(R.string.cat_science)
        "phone & sms" -> stringResource(R.string.cat_phone)
        "theming" -> stringResource(R.string.cat_theming)
        "graphics" -> stringResource(R.string.cat_graphics)
        "sports & health" -> stringResource(R.string.cat_sports)
        "time" -> stringResource(R.string.cat_time)
        "money" -> stringResource(R.string.cat_money)
        "connectivity" -> stringResource(R.string.cat_connectivity)
        else -> raw
    }
}
