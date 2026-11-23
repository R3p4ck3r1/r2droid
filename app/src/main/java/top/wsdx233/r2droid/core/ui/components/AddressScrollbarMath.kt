package top.wsdx233.r2droid.core.ui.components

/** Address ranges are end-exclusive, just like the mapped sections backing the viewer. */
internal fun scrollbarAddressAtRatio(start: Long, end: Long, ratio: Float): Long {
    if (end <= start) return start
    val last = end - 1
    val clamped = if (ratio.isNaN()) 0.0 else ratio.toDouble().coerceIn(0.0, 1.0)
    // Subtract before converting to Double to preserve small ranges at high addresses.
    return start + ((last - start).toDouble() * clamped).toLong().coerceIn(0, last - start)
}

internal fun scrollbarRatioAtPosition(y: Float, trackHeight: Float, thumbHeight: Float, grabOffset: Float): Float {
    val travel = trackHeight - thumbHeight
    return if (travel > 0f) ((y - grabOffset) / travel).coerceIn(0f, 1f) else 0f
}
