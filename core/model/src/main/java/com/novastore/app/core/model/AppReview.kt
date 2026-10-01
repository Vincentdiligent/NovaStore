package com.novastore.app.core.model

/**
 * One user review as shown in the app details screen. Sources: Google Play
 * public review feed (anonymous, no account) and future community sources.
 */
data class AppReview(
    /** Stable review identifier from the source feed. */
    val id: String,
    /** Display name of the reviewer. */
    val author: String,
    /** Profile picture URL, already sized by the source. */
    val avatarUrl: String?,
    /** Star rating 1..5 given by the reviewer. */
    val rating: Int,
    /** Full review text. */
    val text: String,
    /** When the review was published, epoch millis. */
    val timestampMillis: Long?,
    /** How many people marked this review as helpful, when reported. */
    val thumbsUpCount: Int? = null,
    /** The app version the review was written about, when reported. */
    val reviewAppVersion: String? = null,
    /** Developer reply text, when the developer answered. */
    val replyText: String? = null,
    /** When the developer replied, epoch millis. */
    val replyTimestampMillis: Long? = null,
)

/**
 * Star distribution of an app's ratings (index 0 = 5 stars … index 4 = 1 star,
 * matching the Play listing layout).
 */
data class RatingHistogram(
    val five: Long,
    val four: Long,
    val three: Long,
    val two: Long,
    val one: Long,
) {
    val total: Long get() = five + four + three + two + one

    /** Shares 0..1 for [five]..[one]. */
    fun shares(): List<Float> {
        val t = total
        if (t <= 0L) return listOf(0f, 0f, 0f, 0f, 0f)
        return listOf(five, four, three, two, one).map { it / t.toFloat() }
    }

    companion object {
        fun fromCountsDesc(countsDesc: List<Long>): RatingHistogram? {
            if (countsDesc.size < 5) return null
            return RatingHistogram(
                five = countsDesc[0],
                four = countsDesc[1],
                three = countsDesc[2],
                two = countsDesc[3],
                one = countsDesc[4],
            )
        }
    }
}
