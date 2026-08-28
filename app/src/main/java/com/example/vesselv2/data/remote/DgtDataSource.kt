package com.example.vesselv2.data.remote

import android.util.Log
import com.example.vesselv2.ui.adapter.TimeCalItem
import com.example.vesselv2.util.WorkCalculator
import com.example.vesselv2.data.model.QcWorkInfo
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Connection
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.*
import java.util.*

/**
 * [데이터 소스] DgtDataSource — DGT 항만 정보 시스템 API 연동
 *
 * ▶ 역할:
 *   DGT(동부산컨테이너터미널) 웹 시스템에서 선석 배정 스케줄 및
 *   선박별 컨테이너 작업 현황(QC 크레인)을 크롤링/API 호출로 가져옵니다.
 *
 * ▶ 속도 최적화 적용 [2026-08-28]:
 *   - fetchVesselDetails() 호출 시 전달받은 TimeCalItem에 이미 vesselCode와 voyageSeq가 있는 경우,
 *     무거운 전체 선석 스케줄 API(BERTH_SCHEDULE_URL) 조회를 통째로 생략(Skip)합니다.
 *   - 이로 인해 모선 조회 속도가 극대화되고 네트워크 대기 시간이 최소화됩니다.
 */
class DgtDataSource {

    companion object {
        private const val TAG = "DgtDataSource"

        private const val BASE_URL = "https://info.dgtbusan.com/DGT/esvc/vessel/vesselStatus"
        private const val BERTH_SCHEDULE_URL = "https://info.dgtbusan.com/DGT/berth/vesselSchedule"
        private const val CONTAINER_STATUS_URL = "https://info.dgtbusan.com/DGT/document/vesselContainer"

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private const val SESSION_TTL_MS = 30 * 60 * 1000L
    }

    private val kstZone = TimeZone.getTimeZone("Asia/Seoul")

    private var cachedCookies: Map<String, String> = emptyMap()
    private var cachedCsrfHeader: String = ""
    private var cachedCsrfToken: String = ""
    private var lastSessionFetchTime: Long = 0

    private val sslSocketFactory: SSLSocketFactory by lazy { getTrustAllSocketFactory() }

    private fun getTrustAllSocketFactory(): SSLSocketFactory {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, SecureRandom())
        return sslContext.socketFactory
    }

    private fun ensureSession() {
        val now = System.currentTimeMillis()
        if (cachedCookies.isNotEmpty() && (now - lastSessionFetchTime < SESSION_TTL_MS)) {
            return
        }

        try {
            Log.d(TAG, "세션 갱신 중: $BASE_URL")
            val res = Jsoup.connect(BASE_URL)
                .sslSocketFactory(sslSocketFactory)
                .userAgent(USER_AGENT)
                .timeout(10_000)
                .method(Connection.Method.GET)
                .execute()

            cachedCookies = res.cookies()
            val doc = res.parse()
            cachedCsrfToken = doc.select("meta[name=_csrf]").attr("content")
            cachedCsrfHeader = doc.select("meta[name=_csrf_header]").attr("content")
            lastSessionFetchTime = now

            if (cachedCsrfToken.isEmpty()) {
                Log.w(TAG, "세션 갱신 완료, CSRF 토큰이 비어 있음 — API 호출 실패 가능")
            } else {
                Log.d(TAG, "세션 갱신 성공. Token: ${cachedCsrfToken.take(10)}...")
            }
        } catch (e: Exception) {
            Log.e(TAG, "세션 획득 실패: ${e.message}", e)
        }
    }

    fun fetchBerthSchedules(fromDate: String, toDate: String): List<TimeCalItem> {
        ensureSession()
        val items = mutableListOf<TimeCalItem>()

        try {
            val payload = JSONObject().apply {
                put("fromDate", fromDate)
                put("toDate", toDate)
            }

            val response = Jsoup.connect(BERTH_SCHEDULE_URL)
                .sslSocketFactory(sslSocketFactory)
                .userAgent(USER_AGENT)
                .timeout(15_000)
                .cookies(cachedCookies)
                .apply {
                    if (cachedCsrfHeader.isNotEmpty()) header(cachedCsrfHeader, cachedCsrfToken)
                }
                .header("Content-Type", "application/json")
                .requestBody(payload.toString())
                .ignoreContentType(true)
                .method(Connection.Method.POST)
                .execute()

            val body = response.body()
            if (body.isNullOrEmpty() || body == "null") {
                Log.e(TAG, "응답 본문이 비어 있음")
                return emptyList()
            }

            if (!body.trim().startsWith("{")) {
                Log.e(TAG, "JSON이 아닌 응답 수신 (세션 만료 의심): ${body.take(200)}")
                return emptyList()
            }

            val json = JSONObject(body)
            val schedules = json.optJSONArray("vesselSchedules")

            if (schedules == null || schedules.length() == 0) {
                val msg = if (json.has("message")) json.optString("message") else "스케줄 없음"
                Log.w(TAG, "vesselSchedules 비어 있거나 없음. 메시지: $msg")
                return emptyList()
            }

            for (i in 0 until schedules.length()) {
                val obj = schedules.optJSONObject(i) ?: continue
                try {
                    val item = convertToTimeCalItem(obj)
                    if (item != null) {
                        items.add(item)
                    }
                } catch (ee: Exception) {
                    Log.e(TAG, "[$i]번째 항목 변환 오류: ${obj.optString("vesselName")}", ee)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "선석 스케줄 조회 중 오류 발생", e)
        }
        return items
    }

    /**
     * 특정 선박의 상세 정보와 QC 크레인별 작업 현황을 조회합니다.
     *
     * [최적화 적용]: 이미 고유 코드(vesselCode, voyageSeq)가 존재하는 경우, 무거운 스케줄 조회를 완전히 건너뜁니다.
     */
    fun fetchVesselDetails(item: TimeCalItem): Pair<JSONObject, List<QcWorkInfo>>? {
        ensureSession()
        try {
            var vCode = item.vesselCode
            var vSeq = item.voyageSeq
            var vYear = item.voyageYear
            var targetVsl: JSONObject? = null

            // [최적화] 이미 vesselCode와 voyageSeq가 모두 존재한다면, 무거운 스케줄 조회를 생략하고 즉시 정보 구성
            if (vCode.isNotEmpty() && vSeq.isNotEmpty()) {
                targetVsl = JSONObject().apply {
                    put("vesselCode", vCode)
                    put("voyageSeq", vSeq)
                    put("voyageYear", vYear)
                    put("dischargeQty", item.dischargeQty)
                    put("loadQty", item.loadQty)
                    put("shiftQty", item.shiftQty)
                    put("status", item.vesselStatus)
                    put("vesselName", item.vesselName)
                }
            } else {
                // 기존 스케줄 재조회 API Fallback (vesselCode가 비어 있는 비정상 케이스 대비)
                val sdf = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = kstZone }
                val cal = Calendar.getInstance(kstZone)
                cal.add(Calendar.DAY_OF_YEAR, -5)
                val fromDate = sdf.format(cal.time)
                cal.add(Calendar.DAY_OF_YEAR, 20)
                val toDate = sdf.format(cal.time)

                val payload = JSONObject().apply {
                    put("fromDate", fromDate)
                    put("toDate", toDate)
                }

                val response = Jsoup.connect(BERTH_SCHEDULE_URL)
                    .sslSocketFactory(sslSocketFactory)
                    .userAgent(USER_AGENT)
                    .cookies(cachedCookies)
                    .apply { if (cachedCsrfHeader.isNotEmpty()) header(cachedCsrfHeader, cachedCsrfToken) }
                    .header("Content-Type", "application/json")
                    .requestBody(payload.toString())
                    .ignoreContentType(true)
                    .method(Connection.Method.POST)
                    .execute()

                val schedules = JSONObject(response.body()).optJSONArray("vesselSchedules") ?: return null

                var minDiff = Long.MAX_VALUE
                for (i in 0 until schedules.length()) {
                    val obj = schedules.optJSONObject(i) ?: continue

                    if (item.vesselCode.isNotEmpty() &&
                        obj.optString("vesselCode") == item.vesselCode &&
                        obj.optString("voyageSeq") == item.voyageSeq &&
                        obj.optString("voyageYear") == item.voyageYear
                    ) {
                        targetVsl = obj
                        break
                    }

                    val parsedName = obj.optString("vesselName", "").trim()
                    val normItemName = item.vesselName.replace("\\s".toRegex(), "").lowercase()
                    val normParsedName = parsedName.replace("\\s".toRegex(), "").lowercase()

                    if (normParsedName.contains(normItemName) || normItemName.contains(normParsedName)) {
                        val stMs = parseMs(obj.optString("etb"))
                            ?: parseMs(obj.optString("atb"))
                            ?: parseMs(obj.optString("eta"))
                            ?: parseMs(obj.optString("ata"))

                        if (stMs != null) {
                            val diff = Math.abs(stMs - item.etbDateMs)
                            if (diff < minDiff) {
                                minDiff = diff
                                targetVsl = obj
                            }
                        } else if (targetVsl == null) {
                            targetVsl = obj
                        }
                    }
                }

                if (targetVsl != null) {
                    vCode = targetVsl.optString("vesselCode").ifEmpty { item.vesselCode }
                    vSeq = targetVsl.optString("voyageSeq").ifEmpty { item.voyageSeq }
                    vYear = targetVsl.optString("voyageYear").ifEmpty { item.voyageYear }
                }
            }

            if (targetVsl == null) {
                Log.w(TAG, "해당 선박을 스케줄에서 찾을 수 없음: ${item.vesselName}")
                return null
            }

            // ── QC 크레인별 작업 현황 조회 ──────────────────────────────────
            val qcList = mutableListOf<QcWorkInfo>()
            if (vCode.isNotEmpty() && vSeq.isNotEmpty()) {
                val qcPayload = JSONObject().apply {
                    put("vessel", vCode)
                    val voyageStr = if (vYear.isNotEmpty()) "$vSeq/$vYear" else vSeq
                    put("voyage", voyageStr)
                    put("inOutCodes", JSONArray().put("D").put("L"))
                }

                val qcRes = Jsoup.connect(CONTAINER_STATUS_URL)
                    .sslSocketFactory(sslSocketFactory)
                    .userAgent(USER_AGENT)
                    .cookies(cachedCookies)
                    .apply { if (cachedCsrfHeader.isNotEmpty()) header(cachedCsrfHeader, cachedCsrfToken) }
                    .header("Content-Type", "application/json")
                    .requestBody(qcPayload.toString())
                    .ignoreContentType(true)
                    .maxBodySize(0)
                    .method(Connection.Method.POST)
                    .execute()

                val containers = JSONObject(qcRes.body()).optJSONArray("containers")
                if (containers != null) {
                    val qcMap = mutableMapOf<String, QcWorkInfo>()
                    for (k in 0 until containers.length()) {
                        val cObj = containers.optJSONObject(k) ?: continue
                        val craneNo = cObj.optString("craneNo", "").trim()
                        if (craneNo.isEmpty()) continue

                        val psit = cObj.optString("psituation", "")
                        val dl = cObj.optString("disLoad", "")

                        var info = qcMap[craneNo] ?: QcWorkInfo(craneNo, 0, 0, 0, 0)
                        if (psit == "C") {
                            if (dl == "D") info = info.copy(completeDischarge = info.completeDischarge + 1)
                            else if (dl == "L") info = info.copy(completeLoad = info.completeLoad + 1)
                        } else if (psit == "P") {
                            if (dl == "D") info = info.copy(plannedDischarge = info.plannedDischarge + 1)
                            else if (dl == "L") info = info.copy(plannedLoad = info.plannedLoad + 1)
                        }
                        qcMap[craneNo] = info
                    }
                    qcList.addAll(qcMap.values.sortedBy { it.craneNo })
                }
            }
            return Pair(targetVsl, qcList)
        } catch (e: Exception) {
            Log.e(TAG, "선박 상세 정보 조회 오류", e)
            return null
        }
    }

    private fun convertToTimeCalItem(obj: JSONObject): TimeCalItem? {
        val stMs = parseMs(obj.optString("etb"))
            ?: parseMs(obj.optString("atb"))
            ?: parseMs(obj.optString("eta"))
            ?: parseMs(obj.optString("ata"))

        val enMs = parseMs(obj.optString("etd"))
            ?: parseMs(obj.optString("atd"))
            ?: stMs

        if (stMs == null) {
            Log.w(TAG, "${obj.optString("vesselName")}의 날짜를 파싱할 수 없음")
            return null
        }

        val finalEnMs = enMs ?: stMs
        val calc = WorkCalculator.calculate(stMs, finalEnMs, false, 0, false)

        val rawStatus = obj.optString("status").uppercase()
        val status = when {
            rawStatus.startsWith("D") || rawStatus.contains("DEPART") || rawStatus.contains("출항") -> "DEPARTED"
            rawStatus.startsWith("W") || rawStatus.contains("WORK") || rawStatus.contains("작업") -> "WORKING"
            rawStatus.startsWith("B") || rawStatus.contains("BERTH") || rawStatus.contains("접안") -> "BERTHED"
            else -> "PLANNED"
        }

        return TimeCalItem(
            vesselName = obj.optString("vesselName"),
            vesselRoute = "${obj.optString("vesselName")}(${obj.optString("serviceLane")})",
            berth = "${normalizeBerthNo(obj.optString("berthNo"))}(${obj.optString("alongSide")})",
            etb = fmtDate(stMs),
            etd = fmtDate(finalEnMs),
            tradeTime = "-",
            totalHours = calc.totalHours,
            vesselStatus = status,
            etbDateMs = stMs,
            etdDateMs = finalEnMs,
            calculatedAmount = calc.amount,
            dischargeQty = obj.opt("dischargeQty")?.toString() ?: "0",
            loadQty = obj.opt("loadQty")?.toString() ?: "0",
            shiftQty = obj.opt("shiftQty")?.toString() ?: "0",
            vesselCode = obj.optString("vesselCode", ""),
            voyageSeq = obj.optString("voyageSeq", ""),
            voyageYear = obj.optString("voyageYear", ""),
            craneCount = when {
                obj.has("craneWorkCount") -> obj.optInt("craneWorkCount", 0)
                obj.has("allocCraneCount") -> obj.optInt("allocCraneCount", 0)
                obj.has("qcCount")         -> obj.optInt("qcCount", 0)
                obj.has("craneCount")      -> obj.optInt("craneCount", 0)
                else                       -> 0
            }
        )
    }

    private fun normalizeBerthNo(raw: String): String {
        if (raw.isBlank()) return raw
        val s = raw.trim().replace("-", "").replace(" ", "").uppercase()
        val match = Regex("^([A-Z]+)0*(\\d+)$").find(s)
        return if (match != null) {
            val prefix = match.groupValues[1]
            val num = match.groupValues[2]
            when {
                prefix.startsWith("F") -> "F$num"
                prefix == "B" -> "B$num"
                else -> "$prefix$num"
            }
        } else s
    }

    private fun parseMs(raw: String?): Long? {
        if (raw.isNullOrEmpty() || raw == "null") return null

        var clean = raw.trim().replace("T", " ").replace("/", "-")
        if (clean.contains("+")) {
            clean = clean.substringBefore("+").trim()
        }

        val formats = listOf(
            "yyyy-MM-dd HH:mm:ss.SSSSSS",
            "yyyy-MM-dd HH:mm:ss.S",
            "yyyy-MM-dd HH:mm:ss.SS",
            "yyyy-MM-dd HH:mm:ss.SSS",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd",
            "yyyyMMddHHmmss"
        )

        for (f in formats) {
            try {
                val sdf = SimpleDateFormat(f, Locale.US).apply {
                    timeZone = kstZone
                    isLenient = false
                }
                val date = sdf.parse(clean)
                if (date != null) return date.time
            } catch (_: Exception) {}
        }
        return null
    }

    private fun fmtDate(ms: Long): String {
        return SimpleDateFormat("yy/MM/dd HH:mm", Locale.KOREAN).apply {
            timeZone = kstZone
        }.format(Date(ms))
    }
}
