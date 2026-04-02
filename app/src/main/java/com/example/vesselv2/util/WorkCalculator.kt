package com.example.vesselv2.util

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.roundToInt

object WorkCalculator {
    const val MIN_WAGE = 10030.0

    /**
     * 2026년 기준 근무 시급 계산
     * @param startTimeMs 근무 시작 (밀리초)
     * @param endTimeMs 근무 종료 (밀리초)
     * @param isSkill 기능공 수당 적용 여부
     * @param rainHours 우천 수당 적용 시간 (Int)
     * @param isHoliday 공휴일 여부
     */
    fun calculate(
        startTimeMs: Long,
        endTimeMs: Long,
        isSkill: Boolean,
        rainHours: Int,
        isHoliday: Boolean = false
    ): CalculationResult {
        var totalAmount = 0.0
        val durationMs = endTimeMs - startTimeMs

        // 시간 단위 소수점 계산
        val totalHours = (durationMs / 3600000.0).coerceAtLeast(0.0)
        val fullHours = totalHours.toInt()

        for (h in 0 until fullHours) {
            val currentHourStartMs = startTimeMs + (h * 3600000L)
            val cal =
                Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul")).apply {
                    timeInMillis = currentHourStartMs
                }

            val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
            val hourOfDay = cal.get(Calendar.HOUR_OF_DAY)

            // 1. 요일/공휴일 구분
            val dayType =
                when {
                    isHoliday || dayOfWeek == Calendar.SUNDAY -> "SUNDAY"
                    dayOfWeek == Calendar.SATURDAY -> "SATURDAY"
                    else -> "WEEKDAY"
                }

            // 2. 시간대 구분
            val timeType =
                when (hourOfDay) {
                    in 8..17 -> "DAY"
                    in 18..23 -> "NIGHT"
                    in 0..3 -> "DEEP"
                    in 4..7 -> "EARLY"
                    else -> "DAY"
                }

            // 3. 시간대 + 요일 결합 배율 (Multiplier)
            val multiplier =
                when (dayType) {
                    "WEEKDAY" ->
                        when (timeType) {
                            "DAY" -> 1.0
                            "NIGHT" -> 1.5
                            "DEEP" -> 2.0
                            "EARLY" -> 1.5
                            else -> 1.0
                        }

                    "SATURDAY" ->
                        when (timeType) {
                            "DAY" -> 1.5
                            "NIGHT" -> 2.0
                            "DEEP" -> 2.5
                            "EARLY" -> 2.0
                            else -> 1.5
                        }

                    "SUNDAY" ->
                        when (timeType) {
                            "DAY" -> 2.0
                            "NIGHT" -> 2.5
                            "DEEP" -> 3.0
                            "EARLY" -> 2.5
                            else -> 2.0
                        }

                    else -> 1.0
                }

            // 4. 시급 계산 (기본 시급 * 배율)
            var hourlyRate = MIN_WAGE * multiplier

            // 5. 우천 할증 (시급의 50% 추가 -> x1.5)
            // 사용자가 입력한 우천 시간(rainHours)만큼 순차적으로 적용 (최대 근무 시간 이내)
            if (h < rainHours) {
                hourlyRate *= 1.5
            }

            // 6. 기능공 수당
            if (isSkill) {
                val skillAllowance = if (timeType == "DAY") 2000.0 else 3000.0
                hourlyRate += skillAllowance
            }

            totalAmount += hourlyRate
        }

        return CalculationResult(totalHours = totalHours, amount = totalAmount.roundToInt())
    }

    data class CalculationResult(val totalHours: Double, val amount: Int)
}
