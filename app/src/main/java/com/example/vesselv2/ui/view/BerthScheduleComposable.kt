package com.example.vesselv2.ui.view

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vesselv2.ui.adapter.TimeCalItem
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class LaneItem(val item: TimeCalItem, val lane: Int)

@Composable
fun BerthScheduleGraph(
    items: List<TimeCalItem>,
    baseDateMs: Long? = null,
    onItemClick: (TimeCalItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val kstZone = remember { TimeZone.getTimeZone("Asia/Seoul") }
    
    // 치수 상수 (dp -> px 변환)
    val density = LocalDensity.current
    val berthLabelWidthPx = with(density) { 60.dp.toPx() }
    val timeHeaderHeightPx = with(density) { 40.dp.toPx() }
    val berthRowHeightPx = with(density) { 60.dp.toPx() }
    val hourWidthPx = with(density) { 15.dp.toPx() }
    
    val DISPLAY_HOURS = 192L // 8일
    val MAX_LANES = 2
    val LANE_PADDING = with(density) { 2.dp.toPx() }
    val laneHeight = (berthRowHeightPx - LANE_PADDING * 3) / MAX_LANES

    val sortedBerths = remember(items) {
        val list = mutableListOf("F1", "B1", "B2", "B3", "B4", "B5")
        val additional = items.map { it.berth.substringBefore("(") }.distinct()
            .filter { it !in list }
            .sorted()
        list.addAll(additional)
        list
    }

    val graphStartMs = remember(baseDateMs) {
        val cal = Calendar.getInstance(kstZone).apply {
            timeInMillis = baseDateMs ?: System.currentTimeMillis()
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        cal.timeInMillis
    }
    
    val endTimeMs = graphStartMs + DISPLAY_HOURS * 3_600_000L

    val laneMap = remember(items) {
        computeLaneAssignments(items, MAX_LANES)
    }

    val totalWidth = berthLabelWidthPx + DISPLAY_HOURS * hourWidthPx
    val totalHeight = timeHeaderHeightPx + sortedBerths.size * berthRowHeightPx

    val textMeasurer = rememberTextMeasurer()
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()).apply { timeZone = kstZone } }

    // [최적화] 고정 TextStyle 객체를 remember로 캐싱 — 리컴포지션 시 객체 재생성 방지
    val styleDateHeader = remember { TextStyle(color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
    val styleHourLabel  = remember { TextStyle(color = Color.White, fontSize = 8.sp) }
    val styleBerthLabel = remember { TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }

    /** ETB/ETD ms → "HH:mm" 포맷 (정시이면 "HH" 로 축약) */
    fun formatTimeLabel(ms: Long): String {
        val str = timeFmt.format(Date(ms))
        return if (str.endsWith(":00")) str.substringBefore(":") else str
    }

    Box(
        modifier = modifier
            .size(
                width = with(density) { totalWidth.toDp() },
                height = with(density) { totalHeight.toDp() }
            )
            .background(Color(0xFFF8FBFD)) // Background light (Ocean wave theme)
    ) {
        var vesselRects = emptyList<Pair<Rect, TimeCalItem>>()
        
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(items) {
                    detectTapGestures(
                        onTap = { offset ->
                            val clicked = vesselRects.find { (rect, _) ->
                                val expanded = rect.inflate(20f)
                                expanded.contains(offset)
                            }
                            clicked?.let { onItemClick(it.second) }
                        }
                    )
                }
        ) {
            val w = size.width
            val h = size.height

            sortedBerths.forEachIndexed { idx, _ ->
                val top = timeHeaderHeightPx + idx * berthRowHeightPx
                drawRect(
                    color = if (idx % 2 == 0) Color(0xFFFFFFFF) else Color(0xFFF0F8FC),
                    topLeft = Offset(berthLabelWidthPx, top),
                    size = Size(w - berthLabelWidthPx, berthRowHeightPx)
                )
            }

            drawRect(color = Color(0xFF0077B6), size = Size(w, timeHeaderHeightPx))
            drawRect(color = Color(0xFF0077B6), size = Size(berthLabelWidthPx, h))

            val dateFmt = SimpleDateFormat("M/d(EEE)", Locale.getDefault()).apply { timeZone = kstZone }
            for (day in 0 until (DISPLAY_HOURS / 24).toInt()) {
                val dayMs = graphStartMs + day * 24 * 3_600_000L
                if (dayMs >= endTimeMs) continue
                val centerX = berthLabelWidthPx + (day * 24 + 12) * hourWidthPx
                val dateStr = dateFmt.format(Date(dayMs))
                
                val layoutResult = textMeasurer.measure(
                    text = dateStr,
                    style = styleDateHeader  // [최적화] 캐싱된 TextStyle 사용
                )
                drawText(
                    textLayoutResult = layoutResult,
                    topLeft = Offset(centerX - layoutResult.size.width / 2f, 2f)
                )
            }

            for (hour in 0..DISPLAY_HOURS.toInt()) {
                if (hour % 4 != 0) continue
                val x = berthLabelWidthPx + hour * hourWidthPx
                val cal = Calendar.getInstance(kstZone).apply { timeInMillis = graphStartMs + hour * 3_600_000L }
                val hod = cal.get(Calendar.HOUR_OF_DAY)

                drawLine(
                    color = Color.LightGray,
                    start = Offset(x, 0f),
                    end = Offset(x, h),
                    strokeWidth = if (hod == 0 && hour > 0) 2.5f else 1f
                )
                
                val textRes = textMeasurer.measure(
                    text = "%02d".format(hod),
                    style = styleHourLabel  // [최적화] 캐싱된 TextStyle 사용
                )
                drawText(
                    textLayoutResult = textRes,
                    topLeft = Offset(x - textRes.size.width / 2f, timeHeaderHeightPx - textRes.size.height - 2f)
                )
            }

            sortedBerths.forEachIndexed { idx, berth ->
                val rowTop = timeHeaderHeightPx + idx * berthRowHeightPx
                val rowBottom = rowTop + berthRowHeightPx

                drawLine(color = Color(0xFF0077B6), start = Offset(0f, rowTop), end = Offset(w, rowTop), strokeWidth = 2.5f)
                
                val bRes = textMeasurer.measure(
                    text = berth,
                    style = styleBerthLabel  // [최적화] 캐싱된 TextStyle 사용
                )
                drawText(
                    textLayoutResult = bRes,
                    topLeft = Offset((berthLabelWidthPx - bRes.size.width) / 2f, rowTop + (berthRowHeightPx - bRes.size.height) / 2f)
                )

                val laneDivY = rowTop + LANE_PADDING + laneHeight
                drawLine(color = Color(0xFFCAF0F8), start = Offset(berthLabelWidthPx, laneDivY), end = Offset(w, laneDivY), strokeWidth = 1f)
                drawLine(color = Color(0xFF0077B6), start = Offset(0f, rowBottom), end = Offset(w, rowBottom), strokeWidth = 2.5f)
            }

            val currentRects = mutableListOf<Pair<Rect, TimeCalItem>>()

            for ((berth, laneItems) in laneMap) {
                val berthIdx = sortedBerths.indexOf(berth)
                if (berthIdx == -1) continue

                val rowTop = timeHeaderHeightPx + berthIdx * berthRowHeightPx

                for ((item, lane) in laneItems) {
                    val rawStartX = berthLabelWidthPx + ((item.etbDateMs - graphStartMs) / 3_600_000f * hourWidthPx)
                    val rawEndX = berthLabelWidthPx + ((item.etdDateMs - graphStartMs) / 3_600_000f * hourWidthPx)

                    if (rawEndX < berthLabelWidthPx || rawStartX > w) continue

                    val startX = rawStartX.coerceAtLeast(berthLabelWidthPx)
                    val endX = rawEndX.coerceAtMost(w)

                    val laneTop = rowTop + LANE_PADDING + lane * (laneHeight + LANE_PADDING)
                    val laneBottom = laneTop + laneHeight

                    val rect = Rect(startX, laneTop, endX, laneBottom)
                    currentRects.add(Pair(rect, item))

                    val vesselColor = when (item.vesselStatus) {
                        "WORKING" -> Color(0xFFE8964D)
                        "BERTHED" -> Color(0xFF2E9E6E)
                        "PLANNED" -> Color(0xFF5BA7C8)
                        "DEPARTED" -> Color(0xFF8BA7B5)
                        else -> Color.Gray
                    }

                    drawRoundRect(
                        color = vesselColor,
                        topLeft = rect.topLeft,
                        size = rect.size,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f, 10f)
                    )

                    if (rect.width > 50f) {
                        val textColor = if (item.vesselStatus == "WORKING") Color.White else Color.Black

                        // ── 모선명 (상단) ──────────────────────────────────────────
                        clipRect(
                            left = rect.left + 8f,
                            top = rect.top,
                            right = rect.right - 8f,
                            bottom = rect.bottom
                        ) {
                            val vRes = textMeasurer.measure(
                                text = item.vesselName,
                                style = TextStyle(color = textColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            )
                            drawText(
                                textLayoutResult = vRes,
                                topLeft = Offset(rect.left + 8f, rect.top + laneHeight * 0.48f - vRes.size.height / 2f)
                            )
                        }

                        // ── ETB 정보 (하단 좌측) ──────────────────────────────────
                        val etbStr = formatTimeLabel(item.etbDateMs)
                        val cranePrefix = if (item.craneCount > 0) "${item.craneCount}G " else ""
                        val diffMs = item.etdDateMs - item.etbDateMs
                        val workHourStr = if (diffMs > 0) " ${diffMs / 3_600_000L}h" else ""
                        val etbWithVol = "$etbStr  $cranePrefix[${item.dischargeQty}/${item.loadQty}/${item.shiftQty}]$workHourStr"

                        val infoRes = textMeasurer.measure(
                            text = etbWithVol,
                            style = TextStyle(color = textColor, fontSize = 8.sp)
                        )
                        drawText(
                            textLayoutResult = infoRes,
                            topLeft = Offset(rect.left + 7f, rect.bottom - infoRes.size.height - 2f)
                        )

                        // ── ETD 시간 (하단 우측) — 바 너비 110f 이상일 때만 표시 ──
                        if (rect.width > 110f) {
                            val etdStr = formatTimeLabel(item.etdDateMs)
                            val etdRes = textMeasurer.measure(
                                text = etdStr,
                                style = TextStyle(color = textColor, fontSize = 8.sp)
                            )
                            // ETB 텍스트 영역과 겹치지 않는 경우에만 그리기
                            val etdLeft = rect.right - etdRes.size.width - 7f
                            val etbRight = rect.left + 7f + infoRes.size.width
                            if (etdLeft > etbRight + 4f) {
                                drawText(
                                    textLayoutResult = etdRes,
                                    topLeft = Offset(etdLeft, rect.bottom - etdRes.size.height - 2f)
                                )
                            }
                        }
                    }
                }
            }
            vesselRects = currentRects
        }
    }
}

private fun computeLaneAssignments(allItems: List<TimeCalItem>, maxLanes: Int): Map<String, List<LaneItem>> {
    val result = mutableMapOf<String, MutableList<LaneItem>>()
    val byBerth = allItems.groupBy { it.berth.substringBefore("(") }

    for ((berth, bItems) in byBerth) {
        val laneItems = mutableListOf<LaneItem>()
        val laneEndTimes = MutableList(maxLanes) { Long.MIN_VALUE }

        for (item in bItems.sortedBy { it.etbDateMs }) {
            var assigned = -1
            for (lIdx in 0 until maxLanes) {
                if (item.etbDateMs >= laneEndTimes[lIdx]) {
                    assigned = lIdx
                    laneEndTimes[lIdx] = item.etdDateMs
                    break
                }
            }
            if (assigned == -1) {
                assigned = maxLanes - 1
                laneEndTimes[assigned] = maxOf(laneEndTimes[assigned], item.etdDateMs)
            }
            laneItems.add(LaneItem(item, assigned))
        }
        result[berth] = laneItems
    }
    return result
}
