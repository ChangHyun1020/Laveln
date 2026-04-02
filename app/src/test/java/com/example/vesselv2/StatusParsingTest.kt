package com.example.vesselv2

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.*

class StatusParsingTest {

    // 제안된 수정 로직
    private fun parseStatus(raw: String): String {
        val rawUpper = raw.uppercase()
        return when {
            rawUpper.startsWith("D") || rawUpper.contains("DEPART") || rawUpper.contains("출항") -> "DEPARTED"
            rawUpper.startsWith("W") || rawUpper.contains("WORK") || rawUpper.contains("작업") -> "WORKING"
            rawUpper.startsWith("B") || rawUpper.contains("BERTH") || rawUpper.contains("접안") -> "BERTHED"
            else -> "PLANNED"
        }
    }

    // 제안된 시간 포맷팅 수정 로직
    private fun formatTimeLabel(ms: Long): String {
        val kstZone = TimeZone.getTimeZone("Asia/Seoul")
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault()).apply { timeZone = kstZone }
        val str = timeFmt.format(Date(ms))
        return if (str.endsWith(":00")) str.substringBefore(":") else str
    }

    @Test
    fun `test 1 - parse BERTHED`() {
        assertEquals("BERTHED", parseStatus("B"))
        assertEquals("BERTHED", parseStatus("Berth"))
        assertEquals("BERTHED", parseStatus("BERTHED"))
    }

    @Test
    fun `test 2 - parse Korean 접안`() {
        val oldParsed = "접안".uppercase().take(1)
        // 기존 코드의 문제점: "접"이 나오면 PLANNED로 처리됨
        assertEquals("접", oldParsed) // 확인용
        assertEquals("BERTHED", parseStatus("접안"))
    }

    @Test
    fun `test 3 - parse Korean 작업중`() {
        assertEquals("WORKING", parseStatus("작업중"))
        assertEquals("WORKING", parseStatus("W"))
        assertEquals("WORKING", parseStatus("WORKING"))
    }

    @Test
    fun `test 4 - parse Korean 출항`() {
        assertEquals("DEPARTED", parseStatus("출항"))
        assertEquals("DEPARTED", parseStatus("Departured"))
    }

    @Test
    fun `test 5 - format time 18_00 to 18`() {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul"))
        cal.set(Calendar.HOUR_OF_DAY, 18)
        cal.set(Calendar.MINUTE, 0)
        assertEquals("18", formatTimeLabel(cal.timeInMillis))
        
        cal.set(Calendar.HOUR_OF_DAY, 6)
        cal.set(Calendar.MINUTE, 0)
        assertEquals("06", formatTimeLabel(cal.timeInMillis))
    }

    @Test
    fun `test 6 - format time 18_30 to 18_30`() {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul"))
        cal.set(Calendar.HOUR_OF_DAY, 18)
        cal.set(Calendar.MINUTE, 30)
        assertEquals("18:30", formatTimeLabel(cal.timeInMillis))
    }
}
