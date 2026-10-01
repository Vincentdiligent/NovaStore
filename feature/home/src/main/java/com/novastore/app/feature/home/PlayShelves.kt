package com.novastore.app.feature.home

import java.util.Locale

/**
 * Google Play storefront shelves shown on Home (public pages, no account).
 * Chip keys are prefixed with "play:" so they never collide with the
 * repository categories.
 */
internal object PlayShelves {
    private const val PREFIX = "play:"

    /** Shelves offered as chips, in display order. */
    private val chipIds = listOf(
        "GAME", "COMMUNICATION", "SOCIAL", "TOOLS", "PRODUCTIVITY", "VIDEO_PLAYERS",
        "MUSIC_AND_AUDIO", "PHOTOGRAPHY", "ENTERTAINMENT", "FINANCE", "EDUCATION",
        "PERSONALIZATION", "MAPS_AND_NAVIGATION", "SHOPPING", "HEALTH_AND_FITNESS",
        "BOOKS_AND_REFERENCE", "NEWS_AND_MAGAZINES", "TRAVEL_AND_LOCAL", "WEATHER",
    )

    /** Shelves streamed into the "All" feed after the Play home page. */
    val allFeed: List<String> = chipIds

    val chipKeys: List<String> = chipIds.map { PREFIX + it }

    fun isShelf(key: String?): Boolean = key != null && key.startsWith(PREFIX)

    fun idOf(key: String): String = key.removePrefix(PREFIX)

    fun label(key: String): String {
        val id = idOf(key)
        val lang = Locale.getDefault().language
        val row = LABELS[id] ?: return id.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
        return when (lang) {
            "ru" -> row[1]
            "es" -> row[2]
            "fr" -> row[3]
            else -> row[0]
        }
    }

    // en, ru, es, fr
    private val LABELS = mapOf(
        "GAME" to listOf("Games", "Игры", "Juegos", "Jeux"),
        "COMMUNICATION" to listOf("Communication", "Связь", "Comunicación", "Communication"),
        "SOCIAL" to listOf("Social", "Соцсети", "Social", "Réseaux sociaux"),
        "TOOLS" to listOf("Tools", "Инструменты", "Herramientas", "Outils"),
        "PRODUCTIVITY" to listOf("Productivity", "Работа", "Productividad", "Productivité"),
        "VIDEO_PLAYERS" to listOf("Video", "Видео", "Vídeo", "Vidéo"),
        "MUSIC_AND_AUDIO" to listOf("Music", "Музыка", "Música", "Musique"),
        "PHOTOGRAPHY" to listOf("Photo", "Фото", "Fotografía", "Photo"),
        "ENTERTAINMENT" to listOf("Entertainment", "Развлечения", "Entretenimiento", "Divertissement"),
        "FINANCE" to listOf("Finance", "Финансы", "Finanzas", "Finance"),
        "EDUCATION" to listOf("Education", "Образование", "Educación", "Éducation"),
        "PERSONALIZATION" to listOf("Personalization", "Персонализация", "Personalización", "Personnalisation"),
        "MAPS_AND_NAVIGATION" to listOf("Maps", "Карты", "Mapas", "Cartes"),
        "SHOPPING" to listOf("Shopping", "Покупки", "Compras", "Shopping"),
        "HEALTH_AND_FITNESS" to listOf("Health", "Здоровье", "Salud", "Santé"),
        "BOOKS_AND_REFERENCE" to listOf("Books", "Книги", "Libros", "Livres"),
        "NEWS_AND_MAGAZINES" to listOf("News", "Новости", "Noticias", "Actualités"),
        "TRAVEL_AND_LOCAL" to listOf("Travel", "Путешествия", "Viajes", "Voyages"),
        "WEATHER" to listOf("Weather", "Погода", "Tiempo", "Météo"),
    )
}
