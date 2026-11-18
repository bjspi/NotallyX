package com.philkes.notallyx.presentation.activity.main

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import com.github.takahirom.roborazzi.RoborazziRule
import com.github.takahirom.roborazzi.captureRoboImage
import com.philkes.notallyx.R
import com.philkes.notallyx.test.FakeAndroidKeyStore
import com.philkes.notallyx.utils.showColorSelectDialog
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ColorPickerScreenshotTest {
    @get:Rule val roborazziRule = RoborazziRule()

    @Test
    fun wheelAndSlidersUpdateHexWhileDragging() {
        FakeAndroidKeyStore.setup
        var savedColor: String? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.showColorSelectDialog(
                    emptySet(),
                    null,
                    null,
                    { color, _ -> savedColor = color },
                    { _, _ -> },
                )
            }
            ShadowLooper.idleMainLooper()
            scenario.onActivity {
                val colors =
                    ShadowDialog.getLatestDialog().findViewById<RecyclerView>(R.id.MainListView)
                colors.getChildAt(0).findViewById<View>(R.id.CardView).performClick()
            }
            ShadowLooper.idleMainLooper(150, TimeUnit.MILLISECONDS)
            scenario.onActivity {
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                val wheel = dialog.findViewById<View>(R.id.ColorPicker)!!
                val hue = dialog.findViewById<View>(R.id.HueSlider)!!
                val brightness = dialog.findViewById<View>(R.id.BrightnessSlideBar)!!
                val code = dialog.findViewById<EditText>(R.id.ColorCode)!!
                code.requestFocus()

                drag(dialog, wheel, fromX = 0.5f, fromY = 0.5f, toX = 0.7f, toY = 0.35f) {
                    code.text.toString()
                }
                drag(dialog, hue, fromX = 0.2f, fromY = 0.5f, toX = 0.6f, toY = 0.5f) {
                    code.text.toString()
                }
                drag(dialog, brightness, fromX = 0.85f, fromY = 0.5f, toX = 0.35f, toY = 0.5f) {
                    code.text.toString()
                }
            }
            onView(isRoot())
                .inRoot(isDialog())
                .captureRoboImage("ColorPickerScreenshotTest/dragged.png")
            scenario.onActivity {
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                val code = dialog.findViewById<EditText>(R.id.ColorCode)!!
                val expected = "#${code.text}"
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                ShadowLooper.idleMainLooper()
                assertEquals(expected, savedColor)
            }
        }
    }

    private fun drag(
        dialog: AlertDialog,
        target: View,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        readColor: () -> String,
    ) {
        val origin = IntArray(2)
        target.getLocationInWindow(origin)
        val downTime = SystemClock.uptimeMillis()
        fun dispatch(action: Int, x: Float, y: Float) {
            val event =
                MotionEvent.obtain(
                    downTime,
                    SystemClock.uptimeMillis(),
                    action,
                    origin[0] + x * target.width,
                    origin[1] + y * target.height,
                    0,
                )
            try {
                assertTrue(dialog.dispatchTouchEvent(event))
                ShadowLooper.idleMainLooper()
            } finally {
                event.recycle()
            }
        }
        dispatch(MotionEvent.ACTION_DOWN, fromX, fromY)
        val colorOnDown = readColor()
        dispatch(MotionEvent.ACTION_MOVE, toX, toY)
        assertNotEquals(colorOnDown, readColor())
        dispatch(MotionEvent.ACTION_UP, toX, toY)
    }
}
