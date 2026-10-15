package com.example

import android.graphics.Bitmap
import com.example.processor.CornerPoint
import com.example.processor.Corners
import com.example.processor.DocumentProcessor
import com.example.processor.ManualAdjustments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ExampleUnitTest {

    @Test
    fun testCornersDefaultFull() {
        val corners = Corners.defaultFull(1000f, 1500f, insetRatio = 0.05f)
        assertEquals(50f, corners.topLeft.x, 0.01f)
        assertEquals(75f, corners.topLeft.y, 0.01f)
        assertEquals(950f, corners.topRight.x, 0.01f)
        assertEquals(75f, corners.topRight.y, 0.01f)
        assertEquals(950f, corners.bottomRight.x, 0.01f)
        assertEquals(1425f, corners.bottomRight.y, 0.01f)
        assertEquals(50f, corners.bottomLeft.x, 0.01f)
        assertEquals(1425f, corners.bottomLeft.y, 0.01f)
    }

    @Test
    fun testManualAdjustmentsDefault() {
        val adjustments = ManualAdjustments()
        assertTrue(adjustments.isDefault())

        val modified = adjustments.copy(lightness = 0.2f)
        assertFalse(modified.isDefault())
    }

    @Test
    fun testWarpPerspectiveNormal() {
        val bmp = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888)
        val corners = Corners(
            topLeft = CornerPoint(50f, 60f),
            topRight = CornerPoint(750f, 40f),
            bottomRight = CornerPoint(780f, 960f),
            bottomLeft = CornerPoint(40f, 940f)
        )

        val warped = DocumentProcessor.warpPerspective(bmp, corners)
        assertNotNull(warped)
        assertTrue(warped.width > 0)
        assertTrue(warped.height > 0)
        bmp.recycle()
        warped.recycle()
    }

    @Test
    fun testWarpPerspectiveDegenerateCornersDoesNotCrash() {
        val bmp = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        // All points collinear
        val collinearCorners = Corners(
            topLeft = CornerPoint(10f, 10f),
            topRight = CornerPoint(20f, 20f),
            bottomRight = CornerPoint(30f, 30f),
            bottomLeft = CornerPoint(40f, 40f)
        )

        val warped = DocumentProcessor.warpPerspective(bmp, collinearCorners)
        assertNotNull(warped)
        assertTrue(warped.width > 0)
        assertTrue(warped.height > 0)
        bmp.recycle()
        warped.recycle()
    }

    @Test
    fun testLoupeSafeOffsetCalculations() {
        // Test varying viewWidthPx and touch coordinates including zero, small, and negative
        val testWidths = listOf(10f, 100f, 250f, 320f, 360f, 1080f)
        val loupeSizePx = 300f
        val touchPoints = listOf(-50f, 0f, 15f, 150f, 300f, 1200f)

        for (vw in testWidths) {
            val minX = 8f
            val maxX = kotlin.math.max(minX, vw - loupeSizePx - 8f)
            for (tx in touchPoints) {
                val rawOffsetX = tx - loupeSizePx / 2f
                val clamped = rawOffsetX.coerceIn(minOf(minX, maxX), maxOf(minX, maxX))
                assertTrue(clamped.isFinite())
                assertFalse(clamped.isNaN())
            }
        }
    }
}
