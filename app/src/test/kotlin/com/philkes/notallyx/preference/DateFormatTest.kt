package com.philkes.notallyx.preference

import com.philkes.notallyx.presentation.viewmodel.preference.DateFormat
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Test

class DateFormatTest {

    @Test
    fun germanDateWithFourDigitYearUsesCalendarYear() {
        val date =
            Calendar.getInstance()
                .apply {
                    clear()
                    set(2018, Calendar.DECEMBER, 31, 12, 0)
                }
                .time

        assertEquals("31.12.2018", DateFormat.DD_MM_YYYY_GER.format(date))
    }
}
