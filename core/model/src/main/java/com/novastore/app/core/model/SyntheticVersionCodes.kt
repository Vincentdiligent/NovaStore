package com.novastore.app.core.model

/**
 * APKCombo exposes no numeric version codes in its HTML; Nova Store (and its
 * community predecessors) keep the version list ordered with SYNTHETIC codes
 * derived from a 900,000,000 base minus the rank. Those numbers are mirror
 * LIST ORDERING, not Android version codes.
 *
 * This helper lets every comparison site (update rows, EXACT filters,
 * download routing) recognize such codes so a synthetic 899,999,999 can
 * never pose as "newer than installed 34,194" and resurrect dead
 * "11.11.3 → 11.11.3" update rows.
 */
object SyntheticVersionCodes {

    /** APKCombo's synthetic base (see ApkComboClient.SYNTHETIC_BASE). */
    const val BASE: Long = 900_000_000L

    /** APKCombo ranks at most ~50 versions, all just below [BASE]. */
    const val MAX_RANK: Long = 50L

    /**
     * True when [versionCode] falls inside the synthetic APKCombo window.
     * Real Android versionCodes essentially never land here (they are either
     * far smaller or, for a handful of giants like Facebook, far larger —
     * but never inside this exact 50-code slice below 900M).
     */
    fun isSynthetic(versionCode: Long): Boolean =
        versionCode > BASE - MAX_RANK && versionCode <= BASE
}
