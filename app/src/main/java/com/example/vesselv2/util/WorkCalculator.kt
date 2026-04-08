package com.example.vesselv2.util

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * [유틸리티] WorkCalculator — 항만 근무 시급 계산기
 *
 * ▶ 역할:
 *   근무 시작/종료 시각을 기반으로 시간대별 시급 배율을 적용하여
 *   총 근무 시간 및 예상 금액을 계산합니다.
 *
 * ▶ 사용 위치:
 *   - DgtDataSource: 선석 스케줄의 예상 작업 시간/금액 표시용
 *   - MyWorkActivity: 내 근무 기록 저장 시 실제 금액 계산
 *
 * ▶ 계산 기준 (2026년 기준, 항만 하역 근로자 협약 기준):
 *   ┌──────────┬─────────┬──────────┬──────────┐
 *   │  시간대  │ 평일    │  토요일  │ 일/공휴일 │
 *   ├──────────┼─────────┼──────────┼──────────┤
 *   │ 주간(DAY)  08~17  │ x1.0 │ x1.5 │ x2.0 │
 *   │ 야간(NIGHT) 18~23 │ x1.5 │ x2.0 │ x2.5 │
 *   │ 심야(DEEP)  00~03 │ x2.0 │ x2.5 │ x3.0 │
 *   │ 새벽(EARLY) 04~07 │ x1.5 │ x2.0 │ x2.5 │
 *   └──────────┴─────────┴──────────┴──────────┘
 *
 * ▶ 추가 수당:
 *   - 우천 할증: 적용 시간만큼 시급 x1.5
 *   - 기능공 수당: 주간 +2,000원/시간, 야간 +3,000원/시간
 *
 * ▶ 기준 최저시급: MIN_WAGE = 10,030원 (2026년 기준)
 *
 * ⚠️ 주의: 이 계산기는 정수 시간 단위로만 계산합니다.
 *   소수점 이하 시간(예: 0.5시간)은 금액 계산에서 제외됩니다.
 */
object WorkCalculator {

    /** 2026년 기준 최저시급 (원) */
    const val MIN_WAGE = 10030.0

    /**
     * 근무 시간과 조건을 입력받아 총 근무 시간과 예상 금액을 계산합니다.
     *
     * ▶ 계산 로직:
     *   1시간 단위로 루프를 돌며 각 시간마다:
     *   1. 요일 판별 (평일/토요일/일요일+공휴일)
     *   2. 시간대 판별 (주간/야간/심야/새벽)
     *   3. 배율(multiplier) 결정
     *   4. 우천 할증 적용 (rainHours 초과 시간까지만)
     *   5. 기능공 수당 추가
     *   누적 금액을 최종 반올림하여 반환
     *
     * @param startTimeMs 근무 시작 타임스탬프 (밀리초)
     * @param endTimeMs   근무 종료 타임스탬프 (밀리초)
     * @param isSkill     기능공 수당 적용 여부 (true: 적용)
     * @param rainHours   우천 할증 적용 시간 수 (0이면 미적용)
     * @param isHoliday   공휴일 여부 (true이면 일요일과 동일 배율 적용)
     * @return CalculationResult(totalHours, amount)
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

        // 총 근무 시간 (소수점 포함, 음수 방지)
        val totalHours = (durationMs / 3_600_000.0).coerceAtLeast(0.0)
        val fullHours = totalHours.toInt() // 정수 시간 단위로만 금액 계산

        // 1시간 단위로 순회하며 각 시간대의 시급 계산
        for (h in 0 until fullHours) {
            val currentHourStartMs = startTimeMs + (h * 3_600_000L)
            val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul")).apply {
                timeInMillis = currentHourStartMs
            }

            val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
            val hourOfDay = cal.get(Calendar.HOUR_OF_DAY)

            // ── 1단계: 요일 구분 ──────────────────────────────────────────
            val dayType = when {
                isHoliday || dayOfWeek == Calendar.SUNDAY -> "SUNDAY"    // 일요일 or 공휴일
                dayOfWeek == Calendar.SATURDAY -> "SATURDAY"               // 토요일
                else -> "WEEKDAY"                                           // 평일 (월~금)
            }

            // ── 2단계: 시간대 구분 ────────────────────────────────────────
            val timeType = when (hourOfDay) {
                in 8..17 -> "DAY"    // 주간 (오전 8시 ~ 오후 5시)
                in 18..23 -> "NIGHT" // 야간 (오후 6시 ~ 자정)
                in 0..3 -> "DEEP"    // 심야 (자정 ~ 오전 3시)
                in 4..7 -> "EARLY"   // 새벽 (오전 4시 ~ 오전 7시)
                else -> "DAY"
            }

            // ── 3단계: 요일 × 시간대 배율(Multiplier) 결정 ───────────────
            val multiplier = when (dayType) {
                "WEEKDAY" -> when (timeType) {
                    "DAY" -> 1.0    // 평일 주간: 기본 시급
                    "NIGHT" -> 1.5  // 평일 야간: 1.5배
                    "DEEP" -> 2.0   // 평일 심야: 2배
                    "EARLY" -> 1.5  // 평일 새벽: 1.5배
                    else -> 1.0
                }
                "SATURDAY" -> when (timeType) {
                    "DAY" -> 1.5    // 토요일 주간: 1.5배
                    "NIGHT" -> 2.0  // 토요일 야간: 2배
                    "DEEP" -> 2.5   // 토요일 심야: 2.5배
                    "EARLY" -> 2.0  // 토요일 새벽: 2배
                    else -> 1.5
                }
                "SUNDAY" -> when (timeType) {
                    "DAY" -> 2.0    // 일요일/공휴일 주간: 2배
                    "NIGHT" -> 2.5  // 일요일/공휴일 야간: 2.5배
                    "DEEP" -> 3.0   // 일요일/공휴일 심야: 3배 (최고 배율)
                    "EARLY" -> 2.5  // 일요일/공휴일 새벽: 2.5배
                    else -> 2.0
                }
                else -> 1.0
            }

            // ── 4단계: 기본 시급 계산 = 최저시급 × 배율 ──────────────────
            var hourlyRate = MIN_WAGE * multiplier

            // ── 5단계: 우천 할증 — rainHours 범위 내 시간에만 시급 x1.5 ──
            // rainHours = 3이면 근무 시작 후 처음 3시간에만 우천 할증 적용
            if (h < rainHours) {
                hourlyRate *= 1.5
            }

            // ── 6단계: 기능공 수당 추가 ───────────────────────────────────
            // 주간: +2,000원/시간, 야간/심야/새벽: +3,000원/시간
            if (isSkill) {
                val skillAllowance = if (timeType == "DAY") 2000.0 else 3000.0
                hourlyRate += skillAllowance
            }

            totalAmount += hourlyRate
        }

        return CalculationResult(totalHours = totalHours, amount = totalAmount.roundToInt())
    }

    /**
     * 계산 결과 데이터 클래스
     *
     * @param totalHours 총 근무 시간 (소수점 포함, 예: 8.5시간)
     * @param amount     예상 총 금액 (원, 반올림 처리)
     */
    data class CalculationResult(val totalHours: Double, val amount: Int)
}
