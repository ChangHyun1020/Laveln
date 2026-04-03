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
 * DGT 웹사이트 데이터 조회를 담당하는 데이터 소스.
 * 세션(쿠키, CSRF 토큰)을 유지하여 네트워크 성능을 최적화합니다.
 */
class DgtDataSource {

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private val kstZone = TimeZone.getTimeZone("Asia/Seoul")
    
    // 세션 정보 캐싱
    private var cachedCookies: Map<String, String> = emptyMap()
    private var cachedCsrfHeader: String = ""
    private var cachedCsrfToken: String = ""
    private var lastSessionFetchTime: Long = 0
    private val sessionTtl = 30 * 60 * 1000 // 30분 사용 가능

    // SSL 검증 우회를 위한 SocketFactory (Lazy)
    private val sslSocketFactory: SSLSocketFactory by lazy { getTrustAllSocketFactory() }

    /**
     * 모든 인증서를 신뢰하는 SSLSocketFactory를 생성합니다.
     */
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

    /**
     * 세션 정보(CSRF 토큰, 쿠키)를 갱신하거나 반환합니다.
     */
    private fun ensureSession() {
        val now = System.currentTimeMillis()
        if (cachedCookies.isNotEmpty() && (now - lastSessionFetchTime < sessionTtl)) {
            return
        }

        try {
            val baseUrl = "https://info.dgtbusan.com/DGT/esvc/vessel/vesselStatus"
            Log.d("DgtDataSource", "Refreshing session from: $baseUrl")
            val res = Jsoup.connect(baseUrl)
                .sslSocketFactory(sslSocketFactory)
                .userAgent(userAgent)
                .timeout(10000)
                .method(Connection.Method.GET)
                .execute()

            cachedCookies = res.cookies()
            val doc = res.parse()
            cachedCsrfToken = doc.select("meta[name=_csrf]").attr("content")
            cachedCsrfHeader = doc.select("meta[name=_csrf_header]").attr("content")
            lastSessionFetchTime = now
            
            if (cachedCsrfToken.isEmpty()) {
                Log.w("DgtDataSource", "Session refreshed but CSRF token is EMPTY. API calls might fail.")
            } else {
                Log.d("DgtDataSource", "Session refreshed successfully. Token: ${cachedCsrfToken.take(10)}...")
            }
        } catch (e: Exception) {
            Log.e("DgtDataSource", "Failed to fetch session: ${e.message}", e)
        }
    }

    /**
     * 선석 스케줄 데이터를 가져옵니다.
     */
    fun fetchBerthSchedules(fromDate: String, toDate: String): List<TimeCalItem> {
        ensureSession()
        val items = mutableListOf<TimeCalItem>()
        
        try {
            // [개선] HTML 스크래핑 대신 JSON API 호출
            val apiUrl = "https://info.dgtbusan.com/DGT/berth/vesselSchedule"
            val payload = JSONObject().apply {
                put("fromDate", fromDate)
                put("toDate", toDate)
            }

            val response = Jsoup.connect(apiUrl)
                .sslSocketFactory(sslSocketFactory)
                .userAgent(userAgent)
                .timeout(15000)
                .cookies(cachedCookies)
                .apply { if (cachedCsrfHeader.isNotEmpty()) header(cachedCsrfHeader, cachedCsrfToken) }
                .header("Content-Type", "application/json")
                .requestBody(payload.toString())
                .ignoreContentType(true)
                .method(Connection.Method.POST)
                .execute()

            val body = response.body()
            Log.d("DgtDataSource", "BerthSchedule Response Content-Type: ${response.contentType()}")
            
            if (body.isNullOrEmpty() || body == "null") {
                Log.e("DgtDataSource", "Empty or null response body")
                return emptyList()
            }

            // 응답이 JSON이 아닌 경우 (예: HTML 에러 페이지) 처리
            if (!body.trim().startsWith("{")) {
                Log.e("DgtDataSource", "Response is not a JSON object: ${body.take(200)}")
                return emptyList()
            }

            val json = JSONObject(body)
            val schedules = json.optJSONArray("vesselSchedules")
            
            if (schedules == null || schedules.length() == 0) {
                val msg = if (json.has("message")) json.optString("message") else "No schedules found"
                Log.w("DgtDataSource", "vesselSchedules is empty or missing. Msg: $msg")
                return emptyList()
            }

            Log.d("DgtDataSource", "Found ${schedules.length()} schedules in JSON")

            for (i in 0 until schedules.length()) {
                val obj = schedules.optJSONObject(i) ?: continue
                try {
                    val item = convertToTimeCalItem(obj)
                    if (item != null) {
                        items.add(item)
                    } else {
                        Log.w("DgtDataSource", "Skipping item due to parsing failure: ${obj.optString("vesselName")}")
                    }
                } catch (ee: Exception) {
                    Log.e("DgtDataSource", "Error converting object at index $i: ${obj.optString("vesselName")}", ee)
                }
            }
        } catch (e: Exception) {
            Log.e("DgtDataSource", "Error fetching berth schedules", e)
        }
        return items
    }

    /**
     * 특정 선박의 상세 작업 현황 및 QC 정보를 가져옵니다.
     */
    fun fetchVesselDetails(item: TimeCalItem): Pair<JSONObject, List<QcWorkInfo>>? {
        ensureSession()
        try {
            // 1. 선박 스케줄에서 정확한 정보를 다시 조회 (vesselCode, voyage 명시를 위함)
            val apiUrl = "https://info.dgtbusan.com/DGT/berth/vesselSchedule"
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

            val response = Jsoup.connect(apiUrl)
                .sslSocketFactory(sslSocketFactory)
                .userAgent(userAgent)
                .cookies(cachedCookies)
                .apply { if (cachedCsrfHeader.isNotEmpty()) header(cachedCsrfHeader, cachedCsrfToken) }
                .header("Content-Type", "application/json")
                .requestBody(payload.toString())
                .ignoreContentType(true)
                .method(Connection.Method.POST)
                .execute()

            val schedules = JSONObject(response.body()).optJSONArray("vesselSchedules") ?: return null
            
            var targetVsl: JSONObject? = null
            var minDiff = Long.MAX_VALUE
            for (i in 0 until schedules.length()) {
                val obj = schedules.optJSONObject(i) ?: continue
                
                // 1. 고유 코드 정확히 일치 검사
                if (item.vesselCode.isNotEmpty() &&
                    obj.optString("vesselCode") == item.vesselCode &&
                    obj.optString("voyageSeq") == item.voyageSeq &&
                    obj.optString("voyageYear") == item.voyageYear) {
                    targetVsl = obj
                    break // 완전 일치하면 탈출
                }

                // 2. Fallback: 이름 기반 매칭 시, 현재 아이템 일정과 가장 가까운 스케줄 선택
                val parsedName = obj.optString("vesselName", "").trim()
                if (parsedName.contains(item.vesselName.trim(), ignoreCase = true) || item.vesselName.contains(parsedName, ignoreCase = true)) {
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

            if (targetVsl == null) return null

            val vCode = targetVsl.optString("vesselCode").ifEmpty { item.vesselCode }
            val vSeq = targetVsl.optString("voyageSeq").ifEmpty { item.voyageSeq }
            val vYear = targetVsl.optString("voyageYear").ifEmpty { item.voyageYear }
            
            val qcList = mutableListOf<QcWorkInfo>()
            if (vCode.isNotEmpty() && vSeq.isNotEmpty()) {
                val qcUrl = "https://info.dgtbusan.com/DGT/document/vesselContainer"
                val qcPayload = JSONObject().apply {
                    put("vessel", vCode)
                    put("voyage", "$vSeq/$vYear")
                    put("inOutCodes", JSONArray().put("D").put("L"))
                }
                
                val qcRes = Jsoup.connect(qcUrl)
                    .sslSocketFactory(sslSocketFactory)
                    .userAgent(userAgent)
                    .cookies(cachedCookies)
                    .apply { if (cachedCsrfHeader.isNotEmpty()) header(cachedCsrfHeader, cachedCsrfToken) }
                    .header("Content-Type", "application/json")
                    .requestBody(qcPayload.toString())
                    .ignoreContentType(true)
                    .method(Connection.Method.POST)
                    .execute()

                val containers = JSONObject(qcRes.body()).optJSONArray("containers")
                if (containers != null) {
                    val qcMap = mutableMapOf<String, QcWorkInfo>()
                    for (k in 0 until containers.length()) {
                        val cObj = containers.optJSONObject(k) ?: continue
                        val craneNo = cObj.optString("craneNo", "").trim()
                        if (craneNo.isEmpty()) continue
                        
                        val psit = cObj.optString("psituation", "") // C: Complete, P: Planned
                        val dl = cObj.optString("disLoad", "") // D: Discharge, L: Load
                        
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
            Log.e("DgtDataSource", "Error fetching vessel details", e)
            return null
        }
    }

    private fun convertToTimeCalItem(obj: JSONObject): TimeCalItem? {
        // [보완] 날짜 필드 우선순위: ETB/ETD -> ATB/ATD -> ETA/ATA 순으로 유연하게 선택
        val stMs = parseMs(obj.optString("etb")) 
            ?: parseMs(obj.optString("atb")) 
            ?: parseMs(obj.optString("eta"))
            ?: parseMs(obj.optString("ata"))

        val enMs = parseMs(obj.optString("etd")) 
            ?: parseMs(obj.optString("atd"))
            ?: stMs // 종료 시간이 없을 경우 일단 시작 시간과 동일하게 처리

        if (stMs == null) {
            Log.w("DgtDataSource", "Cannot parse any valid start date for ${obj.optString("vesselName")}")
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
            berth = "${obj.optString("berthNo")}(${obj.optString("alongSide")})",
            etb = fmtDate(stMs),
            etd = fmtDate(finalEnMs),
            tradeTime = "-",
            totalHours = calc.totalHours,
            vesselStatus = status,
            etbDateMs = stMs,
            etdDateMs = finalEnMs,
            calculatedAmount = calc.amount,
            // [보완] 숫자(Number)와 문자열(String) 타입 모두 대응 가능한 방식으로 추출
            dischargeQty = obj.opt("dischargeQty")?.toString() ?: "0",
            loadQty = obj.opt("loadQty")?.toString() ?: "0",
            shiftQty = obj.opt("shiftQty")?.toString() ?: "0",
            vesselCode = obj.optString("vesselCode", ""),
            voyageSeq = obj.optString("voyageSeq", ""),
            voyageYear = obj.optString("voyageYear", "")
        )
    }

    private fun parseMs(raw: String?): Long? {
        if (raw.isNullOrEmpty() || raw == "null") return null
        
        // 정규식을 사용하여 날짜 외의 불필요한 공백이나 제어 문자 제거
        var clean = raw.trim().replace("T", " ").replace("/", "-")
        
        // ISO8601의 시간대 정보가 있을 경우 공백으로 변경 (단순화를 위해)
        if (clean.contains("+")) {
            clean = clean.substringBefore("+").trim()
        }

        val formats = listOf(
            "yyyy-MM-dd HH:mm:ss.SSSSSS", // 마이크로초 대응
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
                    isLenient = false // 엄격한 파싱
                }
                val date = sdf.parse(clean)
                if (date != null) return date.time
            } catch (_: Exception) {}
        }
        
        // 마지막 시도로 다양한 포맷 유연하게 검색
        Log.e("DgtDataSource", "All parsing failed for date string: '$raw' (cleaned: '$clean')")
        return null
    }

    private fun fmtDate(ms: Long): String {
        return SimpleDateFormat("yy/MM/dd HH:mm", Locale.KOREAN).apply { 
            timeZone = kstZone 
        }.format(Date(ms))
    }
}
