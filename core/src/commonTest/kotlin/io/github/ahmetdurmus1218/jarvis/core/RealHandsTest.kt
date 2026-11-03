package io.github.ahmetdurmus1218.jarvis.core

import io.github.ahmetdurmus1218.jarvis.core.vision.Gesture
import io.github.ahmetdurmus1218.jarvis.core.vision.GestureClassifier
import io.github.ahmetdurmus1218.jarvis.core.vision.Hand
import io.github.ahmetdurmus1218.jarvis.core.vision.Point
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Landmarks produced by the real MediaPipe hand landmarker (the same model file as in models.lock.json)
 * on MediaPipe's own gesture test photos (storage.googleapis.com/mediapipe-assets: thumb_up.jpg,
 * pointing_up.jpg, victory.jpg, fist.jpg). Guards the classifier against real, imperfect hands.
 */
class RealHandsTest {
    private val photos = mapOf(
        "thumb_up" to (Gesture.THUMBS_UP to Hand(listOf(Point(0.6388f, 0.6713f, -0.0f), Point(0.6349f, 0.5367f, -0.0697f), Point(0.5747f, 0.4128f, -0.0938f), Point(0.4997f, 0.3255f, -0.108f), Point(0.4736f, 0.251f, -0.1059f), Point(0.4075f, 0.4713f, -0.0469f), Point(0.3372f, 0.4674f, -0.0997f), Point(0.4418f, 0.5096f, -0.1112f), Point(0.4806f, 0.5188f, -0.1102f), Point(0.3922f, 0.5495f, -0.0289f), Point(0.3405f, 0.5561f, -0.0821f), Point(0.4615f, 0.5831f, -0.0839f), Point(0.4706f, 0.5641f, -0.0789f), Point(0.3924f, 0.6186f, -0.022f), Point(0.343f, 0.628f, -0.0813f), Point(0.45f, 0.643f, -0.0621f), Point(0.464f, 0.6222f, -0.039f), Point(0.3923f, 0.6819f, -0.0202f), Point(0.3579f, 0.6986f, -0.0523f), Point(0.427f, 0.6989f, -0.0376f), Point(0.4442f, 0.6876f, -0.0204f)))),
        "pointing_up" to (Gesture.POINTING to Hand(listOf(Point(0.4792f, 0.7426f, 0.0f), Point(0.5404f, 0.6618f, -0.0446f), Point(0.5775f, 0.5608f, -0.0758f), Point(0.5265f, 0.5025f, -0.1055f), Point(0.4429f, 0.4949f, -0.1343f), Point(0.4729f, 0.4393f, -0.0581f), Point(0.4803f, 0.3289f, -0.1012f), Point(0.4844f, 0.2588f, -0.1284f), Point(0.4739f, 0.1959f, -0.1509f), Point(0.3913f, 0.4721f, -0.0684f), Point(0.418f, 0.4222f, -0.1296f), Point(0.4576f, 0.5233f, -0.1413f), Point(0.451f, 0.5554f, -0.1388f), Point(0.3313f, 0.5178f, -0.0823f), Point(0.357f, 0.4869f, -0.1471f), Point(0.4075f, 0.5737f, -0.1298f), Point(0.4001f, 0.5931f, -0.1056f), Point(0.2783f, 0.5736f, -0.0997f), Point(0.3088f, 0.5395f, -0.1403f), Point(0.3547f, 0.5918f, -0.1182f), Point(0.3486f, 0.6106f, -0.0951f)))),
        "victory" to (Gesture.VICTORY to Hand(listOf(Point(0.5164f, 0.8041f, 0.0f), Point(0.6064f, 0.7111f, -0.0441f), Point(0.628f, 0.5885f, -0.0624f), Point(0.5265f, 0.5208f, -0.0853f), Point(0.4243f, 0.4993f, -0.1078f), Point(0.5606f, 0.449f, -0.0162f), Point(0.5767f, 0.3226f, -0.0493f), Point(0.5796f, 0.2418f, -0.0732f), Point(0.5828f, 0.1694f, -0.0907f), Point(0.4696f, 0.46f, -0.0322f), Point(0.4436f, 0.3169f, -0.0757f), Point(0.427f, 0.2227f, -0.1082f), Point(0.407f, 0.1428f, -0.1267f), Point(0.3954f, 0.5061f, -0.0551f), Point(0.3618f, 0.4388f, -0.1298f), Point(0.4154f, 0.5267f, -0.1463f), Point(0.4492f, 0.5944f, -0.1347f), Point(0.3318f, 0.5732f, -0.081f), Point(0.3092f, 0.504f, -0.1326f), Point(0.3558f, 0.5576f, -0.1271f), Point(0.3934f, 0.6119f, -0.111f)))),
        "fist" to (Gesture.FIST to Hand(listOf(Point(0.4771f, 0.6613f, -0.0f), Point(0.6126f, 0.5578f, -0.0414f), Point(0.7112f, 0.4317f, -0.0645f), Point(0.6836f, 0.32f, -0.0875f), Point(0.5593f, 0.3206f, -0.0988f), Point(0.6083f, 0.3069f, -0.0148f), Point(0.6294f, 0.2141f, -0.0601f), Point(0.6244f, 0.3287f, -0.0833f), Point(0.6078f, 0.3685f, -0.0966f), Point(0.5157f, 0.3219f, -0.0217f), Point(0.5293f, 0.2477f, -0.0626f), Point(0.5485f, 0.3805f, -0.0703f), Point(0.5443f, 0.3881f, -0.0746f), Point(0.4316f, 0.3492f, -0.0375f), Point(0.4486f, 0.2765f, -0.0817f), Point(0.4772f, 0.3965f, -0.065f), Point(0.4679f, 0.4083f, -0.049f), Point(0.3483f, 0.3781f, -0.0574f), Point(0.3862f, 0.3282f, -0.0728f), Point(0.4114f, 0.3973f, -0.0476f), Point(0.394f, 0.4115f, -0.0296f)))),
    )

    @Test
    fun realPhotosAreRecognized() {
        for ((name, pair) in photos) {
            val (expected, hand) = pair
            assertEquals(expected, GestureClassifier.classify(hand), name)
        }
    }
}
