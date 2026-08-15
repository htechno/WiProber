package com.example.wiprober

internal data class ImageCoordinateMapper(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val drawableWidth: Int,
    val drawableHeight: Int
) {
    init {
        require(sourceWidth > 0 && sourceHeight > 0) { "Invalid source image dimensions" }
        require(drawableWidth > 0 && drawableHeight > 0) { "Invalid drawable dimensions" }
    }

    fun sourceToDrawable(x: Float, y: Float): FloatArray = floatArrayOf(
        x * drawableWidth / sourceWidth,
        y * drawableHeight / sourceHeight
    )

    fun drawableToSource(x: Float, y: Float): FloatArray = floatArrayOf(
        x * sourceWidth / drawableWidth,
        y * sourceHeight / drawableHeight
    )

    fun containsDrawablePoint(x: Float, y: Float): Boolean =
        x in 0f..drawableWidth.toFloat() && y in 0f..drawableHeight.toFloat()
}
