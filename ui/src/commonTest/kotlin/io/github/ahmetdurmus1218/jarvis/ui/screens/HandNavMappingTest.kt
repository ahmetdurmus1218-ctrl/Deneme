package io.github.ahmetdurmus1218.jarvis.ui.screens

import io.github.ahmetdurmus1218.jarvis.core.vision.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Full-screen hand navigation: the cursor lands where the user sees their fingertip. */
class HandNavMappingTest {
    // An upright 480 × 640 camera frame on a 1080 × 2200 phone screen (the picture is cropped at the sides).
    private val fw = 480f
    private val fh = 640f
    private val w = 1080f
    private val h = 2200f

    @Test
    fun theMiddleOfThePictureIsTheMiddleOfTheScreen() {
        val p = toFullScreen(Point(0.5f, 0.5f, 0f), fw, fh, w, h)
        assertEquals(w / 2, p.x, 0.5f)
        assertEquals(h / 2, p.y, 0.5f)
    }

    @Test
    fun theEnlargedPictureBringsTheEdgesWithinReach() {
        // The top and bottom edges are reached before the hand leaves the picture.
        val top = toFullScreen(Point(0.5f, 0.11f, 0f), fw, fh, w, h)
        val bottom = toFullScreen(Point(0.5f, 0.89f, 0f), fw, fh, w, h)
        assertEquals(0f, top.y, 0.5f)
        assertEquals(h, bottom.y, 0.5f)
        // Nothing lands off the screen.
        val corner = toFullScreen(Point(0f, 0f, 0f), fw, fh, w, h)
        assertTrue(corner.x in 0f..w && corner.y in 0f..h)
    }
}
