package com.philkes.notallyx.presentation.widget

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import com.github.takahirom.roborazzi.RoborazziRule
import com.github.takahirom.roborazzi.captureRoboImage
import com.philkes.notallyx.R
import com.philkes.notallyx.presentation.widget.WidgetProvider.Companion.setRecordingControls
import com.philkes.notallyx.test.app
import com.philkes.notallyx.utils.audio.AudioRecordService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetRecordingScreenshotTest {
    @get:Rule val roborazziRule = RoborazziRule()

    @Test
    fun onlyOriginatingWidgetShowsStopEvenAfterItsNoteChanges() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setTheme(R.style.AppTheme)
        val recording = AudioRecordService.WidgetRecording(1, 10, SystemClock.elapsedRealtime())
        val sourceWidget = render(activity, 1, 11, recording)
        val otherWidget = render(activity, 2, 10, recording)

        val timer = sourceWidget.findViewById<Chronometer>(R.id.WidgetRecordingTimer)
        assertEquals(View.VISIBLE, timer.visibility)
        assertEquals(recording.startedAt, timer.base)
        assertEquals(
            app.getString(R.string.stop),
            sourceWidget.findViewById<View>(R.id.WidgetVoice).contentDescription,
        )
        assertEquals(
            View.VISIBLE,
            sourceWidget.findViewById<View>(R.id.WidgetDiscardRecording).visibility,
        )
        assertEquals(
            View.GONE,
            otherWidget.findViewById<View>(R.id.WidgetDiscardRecording).visibility,
        )
        assertEquals(
            View.GONE,
            otherWidget.findViewById<View>(R.id.WidgetRecordingTimer).visibility,
        )
        assertEquals(
            app.getString(R.string.widget_voice_plus),
            otherWidget.findViewById<View>(R.id.WidgetVoice).contentDescription,
        )

        timer.stop()
        timer.text = "00:00"
        capture(activity, sourceWidget, "recording")
        capture(activity, otherWidget, "idle")
    }

    @Test
    fun longTitleSharesTheControlsRowInANarrowWidget() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setTheme(R.style.AppTheme)
        val widget = render(activity, widgetId = 1, noteId = 10, recording = null)
        capture(activity, widget, "narrow", width = 132)

        val title = widget.findViewById<TextView>(R.id.WidgetTitle)
        val changeNote = widget.findViewById<View>(R.id.ChangeNote)
        val voice = widget.findViewById<View>(R.id.WidgetVoice)
        assertTrue(title.layout.getEllipsisCount(0) > 0)
        assertEquals(changeNote.parent, title.parent)
        assertEquals(voice.parent, title.parent)
        assertTrue(
            kotlin.math.abs(title.top + title.height / 2 - voice.top - voice.height / 2) <= 1
        )
        assertTrue(title.right <= changeNote.left)
    }

    private fun render(
        activity: Activity,
        widgetId: Int,
        noteId: Long,
        recording: AudioRecordService.WidgetRecording?,
    ): FrameLayout {
        val parent = FrameLayout(activity)
        val view =
            RemoteViews(app.packageName, R.layout.widget)
                .apply {
                    setRecordingControls(activity, widgetId, noteId, recording)
                    setTextViewText(R.id.WidgetTitle, "A long example note title")
                    setViewVisibility(R.id.Empty, View.GONE)
                }
                .apply(activity, parent)
        parent.addView(view)
        return parent
    }

    private fun capture(activity: Activity, widget: View, name: String, width: Int = 240) {
        val root = FrameLayout(activity)
        root.addView(widget, FrameLayout.LayoutParams(width, 100))
        activity.setContentView(root)
        ShadowLooper.idleMainLooper()
        widget.captureRoboImage("WidgetRecordingScreenshotTest/$name.png")
    }
}
