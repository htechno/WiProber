package com.example.wiprober

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageCoordinateMapperTest {
    @Test
    fun mapsOriginalPixelsToDownsampledDrawableAndBack() {
        val mapper = ImageCoordinateMapper(
            sourceWidth = 3_072,
            sourceHeight = 1_253,
            drawableWidth = 1_080,
            drawableHeight = 441
        )

        val drawablePoint = mapper.sourceToDrawable(2_922.2651f, 1_200.5339f)
        val restoredPoint = mapper.drawableToSource(drawablePoint[0], drawablePoint[1])

        assertArrayEquals(floatArrayOf(2_922.2651f, 1_200.5339f), restoredPoint, 0.001f)
        assertTrue(mapper.containsDrawablePoint(drawablePoint[0], drawablePoint[1]))
        assertFalse(mapper.containsDrawablePoint(1_081f, 100f))
    }
}
