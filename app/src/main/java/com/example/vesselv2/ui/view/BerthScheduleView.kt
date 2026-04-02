package com.example.vesselv2.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.Log
import android.view.View
import androidx.core.graphics.toColorInt
import com.example.vesselv2.ui.adapter.TimeCalItem
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

/**
 * 선석 배정 현황 커스텀 뷰 (Canvas 직접 드로잉)
 *
 * [기능 #1] 바 좌측 ETB, 우측 ETD 시간 레이블 (HH:mm KST)
 * [기능 #2] graphStartMs KST 정규화 기반 정확한 x좌표 계산
 * [기능 #3] 선석당 레인 2개 — 겹침 발생 시 아래 레인으로 분리
 *           선석 상단·하단 구분선 2개로 영역 명확히 표시
 */
class BerthScheduleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── 데이터 ────────────────────────────────────────────────────────────────
    private var items: List<TimeCalItem> = emptyList()
    private val sortedBerths = mutableListOf<String>()
    private var laneMap: Map<String, List<LaneItem>> = emptyMap()

    var onItemClickListener: ((TimeCalItem) -> Unit)? = null

    // 터치 영역 확인용
    private val vesselRects = mutableListOf<Pair<RectF, TimeCalItem>>()
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    init {
        isClickable = true
    }

    // ── [기능 #2] 시간 기준점 ─────────────────────────────────────────────────
    private var graphStartMs: Long = 0L
    private var startTimeMs: Long = 0L
    private var endTimeMs: Long = 0L

    // ── 치수 상수 ─────────────────────────────────────────────────────────────
    private val berthLabelWidth = 120f   // 선석 레이블 열 너비
    private val timeHeaderHeight = 120f   // 시간 헤더 행 높이
    private val berthRowHeight = 160f   // 선석 1개 행 총 높이 (레인 2개 포함) - 컴팩트하게 축소
    private val hourWidth = 30f    // [개선] 1시간당 px (40f -> 30f 로 축소하여 촘촘하게 표시)
    private val DISPLAY_HOURS = 168L   // 7일 표시

    private val laneHeight: Float
        get() = (berthRowHeight - LANE_PADDING * 3) / MAX_LANES

    // ── Paint ─────────────────────────────────────────────────────────────────
    private val headerBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#1565C0".toColorInt(); style = Paint.Style.FILL
    }
    private val rowEvenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#F5F5F5".toColorInt(); style = Paint.Style.FILL
    }
    private val rowOddPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.FILL
    }
    private val textCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 30f; textAlign = Paint.Align.CENTER
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY; strokeWidth = 1f; style = Paint.Style.STROKE
    }

    // 선석 상·하단 굵은 구분선 (진한 파랑)
    private val berthDivPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#1565C0".toColorInt(); strokeWidth = 2.5f; style = Paint.Style.STROKE
    }

    // 레인 내부 경계선 (연회색 점선 효과 — dashEffect 대신 얇은 solid)
    private val laneDivPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#BDBDBD".toColorInt(); strokeWidth = 1f; style = Paint.Style.STROKE
    }
    private val vesselPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // [기능 #1] 시간 레이블 페인트
    private val sp9 get() = resources.displayMetrics.density * 9f
    private val timeLabelWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.LEFT
    }
    private val timeLabelDark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#1A237E".toColorInt(); typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.LEFT
    }

    private val kstZone = TimeZone.getTimeZone("Asia/Seoul")
    private val timeFmt =
        SimpleDateFormat("HH:mm", Locale.getDefault()).apply { timeZone = kstZone }
    private val dateFmt =
        SimpleDateFormat("M/d(EEE)", Locale.getDefault()).apply { timeZone = kstZone }

    // ── 레인 데이터 ───────────────────────────────────────────────────────────
    private data class LaneItem(val item: TimeCalItem, val lane: Int)

    // ── public API ────────────────────────────────────────────────────────────

    fun setData(newItems: List<TimeCalItem>, baseDateMs: Long? = null) {
        items = newItems

        sortedBerths.clear()
        // [수정] 항상 B1~B5 선석을 표시하도록 변경
        sortedBerths.addAll(listOf("B1", "B2", "B3", "B4", "B5"))
        // 추가 데이터가 있다면 뒤에 붙임 (중복 제외)
        val additionalBerths = items.map { it.berth.substringBefore("(") }.distinct()
            .filter { it !in sortedBerths }
            .sorted()
        sortedBerths.addAll(additionalBerths)

        // [기능 #2] KST 00:00:00 정규화
        val cal = Calendar.getInstance(kstZone).apply {
            timeInMillis = baseDateMs ?: System.currentTimeMillis()
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        graphStartMs = cal.timeInMillis
        startTimeMs = graphStartMs
        endTimeMs = startTimeMs + DISPLAY_HOURS * 3_600_000L

        // [기능 #3] 레인 배정
        laneMap = computeLaneAssignments(items)

        Log.d(TAG, "setData: ${Date(graphStartMs)}, items=${items.size}, berths=$sortedBerths")
        requestLayout()
        invalidate()
    }

    // ── 측정 ──────────────────────────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (berthLabelWidth + DISPLAY_HOURS * hourWidth).toInt()
        val h = (timeHeaderHeight + sortedBerths.size * berthRowHeight).toInt()
        setMeasuredDimension(w, h)
    }

    // ── 드로잉 진입점 ─────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (graphStartMs == 0L) return

        timeLabelWhite.textSize = sp9
        timeLabelDark.textSize = sp9

        drawRowBackgrounds(canvas)
        drawTimeHeader(canvas)
        drawBerthRows(canvas)
        drawVessels(canvas)
    }

    // ── 행 배경 ───────────────────────────────────────────────────────────────

    private fun drawRowBackgrounds(canvas: Canvas) {
        sortedBerths.forEachIndexed { idx, _ ->
            val top = timeHeaderHeight + idx * berthRowHeight
            canvas.drawRect(
                berthLabelWidth, top, width.toFloat(), top + berthRowHeight,
                if (idx % 2 == 0) rowEvenPaint else rowOddPaint
            )
        }
    }

    // ── 시간 헤더 ─────────────────────────────────────────────────────────────

    private fun drawTimeHeader(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), timeHeaderHeight, headerBgPaint)
        canvas.drawRect(0f, 0f, berthLabelWidth, height.toFloat(), headerBgPaint)

        // 날짜 라벨 (상단 절반)
        textCenterPaint.textSize = 28f
        textCenterPaint.isFakeBoldText = true
        for (day in 0 until (DISPLAY_HOURS / 24).toInt()) {
            val dayMs = startTimeMs + day * 24 * 3_600_000L
            if (dayMs >= endTimeMs) continue
            val centerX = berthLabelWidth + (day * 24 + 12) * hourWidth
            canvas.drawText(
                dateFmt.format(Date(dayMs)),
                centerX,
                timeHeaderHeight / 2f - 2f,
                textCenterPaint
            )
        }
        textCenterPaint.isFakeBoldText = false

        // 시각 눈금 (하단 절반, 4시간 단위로 변경하여 정밀도 향상)
        textCenterPaint.textSize = 24f
        for (h in 0..DISPLAY_HOURS.toInt()) {
            if (h % 4 != 0) continue
            val x = berthLabelWidth + h * hourWidth
            val cal =
                Calendar.getInstance(kstZone).apply { timeInMillis = startTimeMs + h * 3_600_000L }
            val hod = cal.get(Calendar.HOUR_OF_DAY)

            gridPaint.strokeWidth = if (hod == 0 && h > 0) 2.5f else 1f
            canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
            canvas.drawText("%02d".format(hod), x, timeHeaderHeight - 10f, textCenterPaint)
        }
    }

    // ── 선석 행 구분선 + 레이블 ───────────────────────────────────────────────

    private fun drawBerthRows(canvas: Canvas) {
        sortedBerths.forEachIndexed { idx, berth ->
            val rowTop = timeHeaderHeight + idx * berthRowHeight
            val rowBottom = rowTop + berthRowHeight

            // ① 선석 상단 굵은 구분선
            canvas.drawLine(0f, rowTop, width.toFloat(), rowTop, berthDivPaint)

            // ② 선석 레이블 텍스트
            textCenterPaint.textSize = 30f
            canvas.drawText(
                berth,
                berthLabelWidth / 2f,
                rowTop + berthRowHeight / 2f + 10f,
                textCenterPaint
            )

            // ③ 레인 0↔1 경계선 (중간 구분선)
            val laneDivY = rowTop + LANE_PADDING + laneHeight
            canvas.drawLine(berthLabelWidth, laneDivY, width.toFloat(), laneDivY, laneDivPaint)

            // ④ 선석 하단 굵은 구분선
            canvas.drawLine(0f, rowBottom, width.toFloat(), rowBottom, berthDivPaint)
        }
    }

    // ── 선박 바 ───────────────────────────────────────────────────────────────

    private fun drawVessels(canvas: Canvas) {
        vesselRects.clear()
        for ((berth, laneItems) in laneMap) {
            val berthIdx = sortedBerths.indexOf(berth)
            if (berthIdx == -1) continue

            val rowTop = timeHeaderHeight + berthIdx * berthRowHeight

            for ((item, lane) in laneItems) {
                // [기능 #2] ms → x 좌표
                val rawStartX = berthLabelWidth + msToX(item.etbDateMs)
                val rawEndX = berthLabelWidth + msToX(item.etdDateMs)

                if (rawEndX < berthLabelWidth || rawStartX > width.toFloat()) continue

                val startX = rawStartX.coerceAtLeast(berthLabelWidth)
                val endX = rawEndX.coerceAtMost(width.toFloat())

                // [기능 #3] 레인별 y 좌표
                val laneTop = rowTop + LANE_PADDING + lane * (laneHeight + LANE_PADDING)
                val laneBottom = laneTop + laneHeight

                val rect = RectF(startX, laneTop, endX, laneBottom)
                vesselRects.add(Pair(rect, item))

                vesselPaint.color = when (item.vesselStatus) {
                    "WORKING" -> "#1A237E".toColorInt()
                    "BERTHED" -> "#43A047".toColorInt()
                    "PLANNED" -> "#FBC02D".toColorInt()
                    "DEPARTED" -> "#9E9E9E".toColorInt()
                    else -> Color.GRAY
                }
                canvas.drawRoundRect(rect, 10f, 10f, vesselPaint)

                // 모선명 / 루트 텍스트 (영역 초과 시 스크롤)
                if (rect.width() > 50f) {
                    val tp = if (item.vesselStatus == "PLANNED") timeLabelDark else timeLabelWhite
                    tp.textSize = sp9 * 1.15f
                    tp.textAlign = Paint.Align.LEFT
                    
                    val text = item.vesselName
                    val textWidth = tp.measureText(text)
                    val availableWidth = rect.width() - 16f // 좌우 패딩 제외
                    
                    canvas.save()
                    canvas.clipRect(rect.left + 8f, rect.top, rect.right - 8f, rect.bottom)
                    
                    if (textWidth > availableWidth) {
                        // 스크롤 오프셋 계산 (약 5초 주기)
                        val elapsed = System.currentTimeMillis() % 5000L
                        val scrollRange = textWidth - availableWidth
                        val offset = if (elapsed < 2500) {
                            (elapsed / 2500f) * scrollRange
                        } else {
                            ((5000 - elapsed) / 2500f) * scrollRange
                        }
                        
                        canvas.drawText(
                            text,
                            rect.left + 8f - offset,
                            rect.top + laneHeight * 0.48f,
                            tp
                        )
                        postInvalidateOnAnimation() // 애니메이션 연속 실행
                    } else {
                        canvas.drawText(
                            text,
                            rect.left + 8f,
                            rect.top + laneHeight * 0.48f,
                            tp
                        )
                    }
                    canvas.restore()
                }

                // [기능 #1] 시간 레이블
                drawTimeLabels(canvas, item, rect)
            }
        }
    }

    // ── [기능 #1] 시간 레이블 ─────────────────────────────────────────────────

    private fun drawTimeLabels(canvas: Canvas, item: TimeCalItem, barRect: RectF) {
        val w = barRect.width()
        if (w < MIN_ETB_WIDTH) return

        val paint = if (item.vesselStatus == "PLANNED") timeLabelDark else timeLabelWhite
        paint.textSize = sp9
        val textY = barRect.bottom - paint.textSize * 0.45f
        val padX = 7f

        val etbStr = formatTimeLabel(item.etbDateMs)
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText(etbStr, barRect.left + padX, textY, paint)

        if (w >= MIN_ETD_WIDTH) {
            val etdStr = formatTimeLabel(item.etdDateMs)
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(etdStr, barRect.right - padX, textY, paint)
        }
        paint.textAlign = Paint.Align.LEFT
    }

    private fun formatTimeLabel(ms: Long): String {
        val str = timeFmt.format(Date(ms))
        return if (str.endsWith(":00")) str.substringBefore(":") else str
    }

    // ── [기능 #3] 레인 배정 알고리즘 ─────────────────────────────────────────

    private fun computeLaneAssignments(allItems: List<TimeCalItem>): Map<String, List<LaneItem>> {
        val result = mutableMapOf<String, MutableList<LaneItem>>()
        val byBerth = allItems.groupBy { it.berth.substringBefore("(") }

        for ((berth, bItems) in byBerth) {
            val laneItems = mutableListOf<LaneItem>()
            val laneEndTimes = MutableList(MAX_LANES) { Long.MIN_VALUE }

            for (item in bItems.sortedBy { it.etbDateMs }) {
                var assigned = -1
                for (lIdx in 0 until MAX_LANES) {
                    if (item.etbDateMs >= laneEndTimes[lIdx]) {
                        assigned = lIdx
                        laneEndTimes[lIdx] = item.etdDateMs
                        break
                    }
                }
                if (assigned == -1) {
                    // 모든 레인 충돌 → 마지막 레인 강제 배정
                    assigned = MAX_LANES - 1
                    laneEndTimes[assigned] = maxOf(laneEndTimes[assigned], item.etdDateMs)
                    Log.w(TAG, "레인 오버플로(겹침): ${item.vesselName} @ $berth")
                }
                laneItems.add(LaneItem(item, assigned))
            }
            result[berth] = laneItems
        }
        return result
    }

    // ── [기능 #2] 좌표 변환 ───────────────────────────────────────────────────

    private fun msToX(timeMs: Long): Float = (timeMs - graphStartMs) / 3_600_000f * hourWidth

    // ── 터치 이벤트 처리 ─────────────────────────────────────────────────────

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.action) {
            android.view.MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
            }

            android.view.MotionEvent.ACTION_UP -> {
                val dx = abs(event.x - lastTouchX)
                val dy = abs(event.y - lastTouchY)
                if (dx < 15f && dy < 15f) { // 스크롤이 아닌 클릭으로 간주
                    val clickedItem =
                        vesselRects.find { it.first.contains(event.x, event.y) }?.second
                    if (clickedItem != null) {
                        onItemClickListener?.invoke(clickedItem)
                        return true
                    }
                }
            }
        }
        return super.onTouchEvent(event)
    }

    // ── 상수 ─────────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "BerthScheduleView"
        private const val MAX_LANES = 2
        private const val LANE_PADDING = 8f
        private const val MIN_ETB_WIDTH = 70f
        private const val MIN_ETD_WIDTH = 110f
    }
}
