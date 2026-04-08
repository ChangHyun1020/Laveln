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
 * ▶ 실행 흐름:
 *   1. ensureSession()  — 첫 접속 시 DGT 페이지에서 세션 쿠키 + CSRF 토큰 획득
 *   2. fetchBerthSchedules() — 선석 스케줄 JSON API 호출 → TimeCalItem 파싱
 *   3. fetchVesselDetails() — 선박 상세 정보 + QC 크레인별 작업 현황 조회
 *
 * ▶ 기술적 주의사항:
 *   - 모든 API 호출은 Jsoup 라이브러리 사용 (Android 메인 스레드 금지 — IO 스레드에서만 호출)
 *   - 세션(쿠키/CSRF)은 30분 캐싱 후 자동 갱신
 *
 * ⚠️ 보안 경고 [SSL 인증서 검증 우회]:
 *   getTrustAllSocketFactory()는 모든 SSL 인증서를 신뢰하도록 설정됩니다.
 *   이는 일반적으로 중간자 공격(MITM, Man-In-The-Middle)에 취약한 방식입니다.
 *   
 *   [우회 이유]:
 *     DGT 내부망 서버(info.dgtbusan.com)가 자체 서명 인증서(Self-Signed Certificate)를
 *     사용하기 때문에 Android 기본 SSL 검증 시 연결 오류가 발생합니다.
 *   
 *   [위험 수준]:
 *     해당 서버는 항만 내부 인트라넷 환경에서만 접근 가능하므로 외부 공격 노출이
 *     제한적입니다. 단, 공용 Wi-Fi 환경에서는 위험할 수 있습니다.
 *   
 *   [개선 권고]:
 *     서버 측에서 공인 CA(인증 기관)의 인증서를 적용하면 이 우회 코드를 제거할 수 있습니다.
 *     그 전까지는 이 방식을 유지하되, 민감한 인증 정보(아이디/비밀번호)를 전송하지 않습니다.
 */
class DgtDataSource {

    /**
     * ─────────────────────────────────────────────────────────────────────
     * URL 상수 — 하드코딩 방지, 변경 시 이 한 곳만 수정
     * ─────────────────────────────────────────────────────────────────────
     *
     * ⚠️ 보안 참고:
     *   아래 URL은 DGT 항만 정보 시스템의 공개(비인증) API 엔드포인트입니다.
     *   사용자 개인 자격 증명(아이디/비밀번호)은 포함되지 않으며,
     *   세션 쿠키와 CSRF 토큰으로만 인증합니다.
     */
    companion object {
        private const val TAG = "DgtDataSource"

        /** DGT 세션 획득 기준 URL (선박 현황 메인 페이지) */
        private const val BASE_URL = "https://info.dgtbusan.com/DGT/esvc/vessel/vesselStatus"

        /** 선석 스케줄 조회 API URL (JSON POST) */
        private const val BERTH_SCHEDULE_URL = "https://info.dgtbusan.com/DGT/berth/vesselSchedule"

        /** 컨테이너 작업 현황(QC 크레인) 조회 API URL (JSON POST) */
        private const val CONTAINER_STATUS_URL = "https://info.dgtbusan.com/DGT/document/vesselContainer"

        /** HTTP 요청 시 사용할 User-Agent (Chrome 브라우저 위장) */
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /** 세션 유효 시간: 30분 (밀리초) */
        private const val SESSION_TTL_MS = 30 * 60 * 1000L
    }

    /** KST(한국 표준시) 타임존 — 날짜/시간 파싱 및 포맷에 사용 */
    private val kstZone = TimeZone.getTimeZone("Asia/Seoul")

    // ── 세션 캐싱 변수 ────────────────────────────────────────────────────
    /** 마지막으로 획득한 세션 쿠키 (Jsoup 요청 시 함께 전송) */
    private var cachedCookies: Map<String, String> = emptyMap()

    /** CSRF 헤더명 (예: "X-CSRF-TOKEN") */
    private var cachedCsrfHeader: String = ""

    /** CSRF 토큰 값 (서버에서 발급받은 위조 방지 토큰) */
    private var cachedCsrfToken: String = ""

    /** 마지막 세션 획득 시각 (밀리초) — TTL 만료 여부 판단에 사용 */
    private var lastSessionFetchTime: Long = 0

    /**
     * ⚠️ 보안 경고: SSL 인증서 전체 신뢰 SocketFactory (Lazy 초기화)
     *
     * 이 SocketFactory는 서버의 SSL 인증서가 유효하지 않아도 연결을 허용합니다.
     * 클래스 주석의 보안 경고 내용을 반드시 참고하세요.
     */
    private val sslSocketFactory: SSLSocketFactory by lazy { getTrustAllSocketFactory() }

    // ──────────────────────────────────────────────────────────────────────
    //  SSL 우회 처리 (내부망 자체 서명 인증서 대응)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 모든 SSL 인증서를 신뢰하는 SSLSocketFactory를 생성합니다.
     *
     * ⚠️ MITM(중간자 공격) 취약점이 있으므로 공용 인터넷 환경에서는 주의가 필요합니다.
     * [사용 목적]: DGT 내부 서버의 자체 서명 인증서 우회 (Android 기본 SSL 검증 오류 방지)
     *
     * @return 모든 인증서를 허용하는 SSLSocketFactory
     */
    private fun getTrustAllSocketFactory(): SSLSocketFactory {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            // 클라이언트/서버 인증서 검증을 모두 생략 (신뢰)
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, SecureRandom())
        return sslContext.socketFactory
    }

    // ──────────────────────────────────────────────────────────────────────
    //  세션 관리
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 세션(쿠키, CSRF 토큰)의 유효성을 확인하고 필요 시 갱신합니다.
     *
     * ▶ 세션 갱신 조건:
     *   - 캐싱된 쿠키가 없거나 SESSION_TTL_MS(30분) 이상 경과한 경우
     *
     * ▶ 처리 흐름:
     *   1. DGT 메인 페이지(BASE_URL) GET 요청
     *   2. 응답에서 쿠키, CSRF 토큰 헤더명, 토큰 값 추출
     *   3. 캐싱하여 이후 API 요청에서 재사용
     */
    private fun ensureSession() {
        val now = System.currentTimeMillis()
        // 세션이 유효하면 갱신하지 않음
        if (cachedCookies.isNotEmpty() && (now - lastSessionFetchTime < SESSION_TTL_MS)) {
            return
        }

        try {
            Log.d(TAG, "세션 갱신 중: $BASE_URL")
            val res = Jsoup.connect(BASE_URL)
                .sslSocketFactory(sslSocketFactory) // ⚠️ SSL 우회 적용
                .userAgent(USER_AGENT)
                .timeout(10_000) // 10초 타임아웃
                .method(Connection.Method.GET)
                .execute()

            // 응답에서 쿠키 및 CSRF 정보 추출
            cachedCookies = res.cookies()
            val doc = res.parse()
            cachedCsrfToken = doc.select("meta[name=_csrf]").attr("content")
            cachedCsrfHeader = doc.select("meta[name=_csrf_header]").attr("content")
            lastSessionFetchTime = now

            if (cachedCsrfToken.isEmpty()) {
                Log.w(TAG, "세션 갱신 완료, CSRF 토큰이 비어 있음 — API 호출 실패 가능")
            } else {
                // 보안: 토큰 전체를 로그에 출력하지 않고 앞 10자만 표시
                Log.d(TAG, "세션 갱신 성공. Token: ${cachedCsrfToken.take(10)}...")
            }
        } catch (e: Exception) {
            Log.e(TAG, "세션 획득 실패: ${e.message}", e)
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  선석 스케줄 조회
    // ──────────────────────────────────────────────────────────────────────

    /**
     * DGT API에서 지정 기간의 선석 배정 스케줄을 조회합니다.
     *
     * ▶ API: POST BERTH_SCHEDULE_URL
     * ▶ 요청 본문: { "fromDate": "yyyyMMdd", "toDate": "yyyyMMdd" }
     * ▶ 응답: { "vesselSchedules": [ {...}, {...}, ... ] }
     *
     * ▶ 파싱 순서:
     *   JSON 배열 → convertToTimeCalItem() → TimeCalItem 리스트
     *
     * ⚠️ IO 스레드에서만 호출해야 합니다 (네트워크 요청이기 때문).
     *
     * @param fromDate 조회 시작일 (형식: yyyyMMdd, 예: "20260401")
     * @param toDate   조회 종료일 (형식: yyyyMMdd, 예: "20260408")
     * @return 파싱된 TimeCalItem 리스트 (오류 시 빈 리스트 반환)
     */
    fun fetchBerthSchedules(fromDate: String, toDate: String): List<TimeCalItem> {
        ensureSession() // 세션 유효성 확인 및 갱신
        val items = mutableListOf<TimeCalItem>()

        try {
            // JSON 요청 본문 구성
            val payload = JSONObject().apply {
                put("fromDate", fromDate)
                put("toDate", toDate)
            }

            val response = Jsoup.connect(BERTH_SCHEDULE_URL)
                .sslSocketFactory(sslSocketFactory) // ⚠️ SSL 우회 적용
                .userAgent(USER_AGENT)
                .timeout(15_000) // 15초 타임아웃 (데이터 양이 많아 여유 있게 설정)
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
            Log.d(TAG, "BerthSchedule 응답 Content-Type: ${response.contentType()}")

            // 빈 응답 처리
            if (body.isNullOrEmpty() || body == "null") {
                Log.e(TAG, "응답 본문이 비어 있음")
                return emptyList()
            }

            // JSON이 아닌 응답 처리 (예: 로그인 페이지 HTML이 반환된 경우)
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

            Log.d(TAG, "수신된 스케줄 수: ${schedules.length()}")

            // 각 스케줄 항목을 TimeCalItem으로 변환
            for (i in 0 until schedules.length()) {
                val obj = schedules.optJSONObject(i) ?: continue
                try {
                    val item = convertToTimeCalItem(obj)
                    if (item != null) {
                        items.add(item)
                    } else {
                        Log.w(TAG, "파싱 실패로 건너뜀: ${obj.optString("vesselName")}")
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

    // ──────────────────────────────────────────────────────────────────────
    //  선박 상세 정보 + QC 작업 현황 조회
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 특정 선박의 상세 정보와 QC 크레인별 작업 현황을 조회합니다.
     *
     * ▶ 처리 단계:
     *   1. 선박 스케줄 API를 재조회하여 vesselCode/voyageSeq/voyageYear 정확히 획득
     *      (그래프에서 클릭한 선박과 정확히 일치하는 항차 찾기)
     *   2. QC 크레인 작업 현황 API (CONTAINER_STATUS_URL) 조회
     *   3. 컨테이너별 QC 번호/완료/잔여 수량 집계
     *
     * ▶ 선박 매칭 우선순위:
     *   1순위: vesselCode + voyageSeq + voyageYear 3개 정확히 일치 (고유 코드 기반)
     *   2순위: 이름 부분 일치 + ETB 시각이 가장 가까운 항차 선택 (Fallback)
     *
     * ⚠️ IO 스레드에서만 호출해야 합니다.
     *
     * @param item 그래프에서 클릭된 선박의 TimeCalItem (vesselCode, voyageSeq, voyageYear 포함)
     * @return Pair(선박 정보 JSONObject, QcWorkInfo 리스트) 또는 null (실패 시)
     */
    fun fetchVesselDetails(item: TimeCalItem): Pair<JSONObject, List<QcWorkInfo>>? {
        ensureSession()
        try {
            // 스케줄 조회 기간: 현재 기준 -5일 ~ +15일 범위로 검색
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
                .sslSocketFactory(sslSocketFactory) // ⚠️ SSL 우회 적용
                .userAgent(USER_AGENT)
                .cookies(cachedCookies)
                .apply { if (cachedCsrfHeader.isNotEmpty()) header(cachedCsrfHeader, cachedCsrfToken) }
                .header("Content-Type", "application/json")
                .requestBody(payload.toString())
                .ignoreContentType(true)
                .method(Connection.Method.POST)
                .execute()

            val schedules = JSONObject(response.body()).optJSONArray("vesselSchedules") ?: return null

            // ── 선박 매칭: 고유 코드 일치 → 이름+ETB 기준 Fallback ────────────
            var targetVsl: JSONObject? = null
            var minDiff = Long.MAX_VALUE

            for (i in 0 until schedules.length()) {
                val obj = schedules.optJSONObject(i) ?: continue

                // [1순위] vesselCode + voyageSeq + voyageYear 완전 일치
                if (item.vesselCode.isNotEmpty() &&
                    obj.optString("vesselCode") == item.vesselCode &&
                    obj.optString("voyageSeq") == item.voyageSeq &&
                    obj.optString("voyageYear") == item.voyageYear
                ) {
                    targetVsl = obj
                    break // 완전 일치 발견 → 즉시 종료
                }

                // [2순위 Fallback] 이름 부분 일치 + ETB 기준 가장 가까운 항차
                val parsedName = obj.optString("vesselName", "").trim()
                if (parsedName.contains(item.vesselName.trim(), ignoreCase = true) ||
                    item.vesselName.contains(parsedName, ignoreCase = true)
                ) {
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

            if (targetVsl == null) {
                Log.w(TAG, "해당 선박을 스케줄에서 찾을 수 없음: ${item.vesselName}")
                return null
            }

            // 최종 선박 코드/항차 정보 결정 (타겟 스케줄 우선, 없으면 원래 item 값 사용)
            val vCode = targetVsl.optString("vesselCode").ifEmpty { item.vesselCode }
            val vSeq = targetVsl.optString("voyageSeq").ifEmpty { item.voyageSeq }
            val vYear = targetVsl.optString("voyageYear").ifEmpty { item.voyageYear }

            // ── QC 크레인별 작업 현황 조회 ──────────────────────────────────
            val qcList = mutableListOf<QcWorkInfo>()
            if (vCode.isNotEmpty() && vSeq.isNotEmpty()) {
                val qcPayload = JSONObject().apply {
                    put("vessel", vCode)
                    put("voyage", "$vSeq/$vYear") // 형식: "0123/2026"
                    // D: 양하 (Discharge), L: 적하 (Load)
                    put("inOutCodes", JSONArray().put("D").put("L"))
                }

                val qcRes = Jsoup.connect(CONTAINER_STATUS_URL)
                    .sslSocketFactory(sslSocketFactory) // ⚠️ SSL 우회 적용
                    .userAgent(USER_AGENT)
                    .cookies(cachedCookies)
                    .apply { if (cachedCsrfHeader.isNotEmpty()) header(cachedCsrfHeader, cachedCsrfToken) }
                    .header("Content-Type", "application/json")
                    .requestBody(qcPayload.toString())
                    .ignoreContentType(true)
                    .method(Connection.Method.POST)
                    .execute()

                // 컨테이너별 QC 크레인 작업 현황을 크레인별로 집계
                val containers = JSONObject(qcRes.body()).optJSONArray("containers")
                if (containers != null) {
                    val qcMap = mutableMapOf<String, QcWorkInfo>()
                    for (k in 0 until containers.length()) {
                        val cObj = containers.optJSONObject(k) ?: continue
                        val craneNo = cObj.optString("craneNo", "").trim()
                        if (craneNo.isEmpty()) continue

                        // psituation: "C" = 완료(Complete), "P" = 예정(Planned)
                        val psit = cObj.optString("psituation", "")
                        // disLoad: "D" = 양하(Discharge), "L" = 적하(Load)
                        val dl = cObj.optString("disLoad", "")

                        // 크레인별로 집계 (없으면 초기값으로 생성)
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
                    // 크레인 번호 기준 오름차순 정렬
                    qcList.addAll(qcMap.values.sortedBy { it.craneNo })
                }
            }
            return Pair(targetVsl, qcList)
        } catch (e: Exception) {
            Log.e(TAG, "선박 상세 정보 조회 오류", e)
            return null
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  JSON → TimeCalItem 변환 (파싱)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * JSON 스케줄 항목 하나를 TimeCalItem 데이터 클래스로 변환합니다.
     *
     * ▶ 날짜 필드 우선순위 (유연한 처리):
     *   시작: ETB(예정 접안) > ATB(실제 접안) > ETA(예정 도착) > ATA(실제 도착)
     *   종료: ETD(예정 출항) > ATD(실제 출항) > 시작시간(fallback)
     *
     * ▶ 상태(status) 정규화:
     *   DGT 원시 상태 문자열을 앱 내 표준값(WORKING/BERTHED/PLANNED/DEPARTED)으로 변환
     *
     * @param obj Firestore JSON 스케줄 항목
     * @return TimeCalItem 또는 null (날짜 파싱 실패 시)
     */
    private fun convertToTimeCalItem(obj: JSONObject): TimeCalItem? {
        // 시작 시간: ETB → ATB → ETA → ATA 순으로 유효한 첫 번째 값 사용
        val stMs = parseMs(obj.optString("etb"))
            ?: parseMs(obj.optString("atb"))
            ?: parseMs(obj.optString("eta"))
            ?: parseMs(obj.optString("ata"))

        // 종료 시간: ETD → ATD → 시작 시간(fallback)
        val enMs = parseMs(obj.optString("etd"))
            ?: parseMs(obj.optString("atd"))
            ?: stMs

        // 시작 시간을 파싱하지 못하면 이 항목은 처리 불가
        if (stMs == null) {
            Log.w(TAG, "${obj.optString("vesselName")}의 날짜를 파싱할 수 없음")
            return null
        }

        val finalEnMs = enMs ?: stMs

        // 근무 시간 및 예상 금액 계산
        val calc = WorkCalculator.calculate(stMs, finalEnMs, false, 0, false)

        // DGT 원시 상태 → 앱 표준 상태 변환
        val rawStatus = obj.optString("status").uppercase()
        val status = when {
            rawStatus.startsWith("D") || rawStatus.contains("DEPART") || rawStatus.contains("출항") -> "DEPARTED"
            rawStatus.startsWith("W") || rawStatus.contains("WORK") || rawStatus.contains("작업") -> "WORKING"
            rawStatus.startsWith("B") || rawStatus.contains("BERTH") || rawStatus.contains("접안") -> "BERTHED"
            else -> "PLANNED"
        }

        return TimeCalItem(
            vesselName = obj.optString("vesselName"),
            // 형식: "모선명(서비스레인)" — 그래프/리스트 표시용
            vesselRoute = "${obj.optString("vesselName")}(${obj.optString("serviceLane")})",
            // 형식: "선석번호(접안 방향)" — 예: "B3(S)"
            berth = "${obj.optString("berthNo")}(${obj.optString("alongSide")})",
            etb = fmtDate(stMs),          // 접안 시각 표시용 문자열
            etd = fmtDate(finalEnMs),     // 출항 시각 표시용 문자열
            tradeTime = "-",
            totalHours = calc.totalHours,
            vesselStatus = status,
            etbDateMs = stMs,
            etdDateMs = finalEnMs,
            calculatedAmount = calc.amount,
            // 수량 필드는 숫자/문자열 양쪽 응답 모두 대응 (.opt()로 유연하게 처리)
            dischargeQty = obj.opt("dischargeQty")?.toString() ?: "0",
            loadQty = obj.opt("loadQty")?.toString() ?: "0",
            shiftQty = obj.opt("shiftQty")?.toString() ?: "0",
            // 고유 식별 코드 — 동명 선박 구분 및 정확한 QC 조회에 사용
            vesselCode = obj.optString("vesselCode", ""),
            voyageSeq = obj.optString("voyageSeq", ""),
            voyageYear = obj.optString("voyageYear", "")
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    //  날짜 파싱 유틸리티
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 다양한 날짜 문자열 포맷을 밀리초(Long)로 파싱합니다.
     *
     * DGT API 응답의 날짜 포맷이 일관되지 않아 여러 형식을 순차적으로 시도합니다.
     * (예: 마이크로초, 밀리초, 초, ISO8601 등 혼용)
     *
     * @param raw DGT API에서 받은 날짜 문자열 (예: "2026-04-01 08:00:00.000000+09")
     * @return 밀리초 타임스탬프, 파싱 불가 시 null
     */
    private fun parseMs(raw: String?): Long? {
        if (raw.isNullOrEmpty() || raw == "null") return null

        // 전처리: ISO8601 T 구분자 제거, 슬래시→대시, 타임존 정보(+09:00) 제거
        var clean = raw.trim().replace("T", " ").replace("/", "-")
        if (clean.contains("+")) {
            clean = clean.substringBefore("+").trim()
        }

        // 순차적으로 시도할 날짜 포맷 목록 (정밀도 높은 순서로 정렬)
        val formats = listOf(
            "yyyy-MM-dd HH:mm:ss.SSSSSS", // 마이크로초 (DGT 주 사용 포맷)
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
                    isLenient = false // 엄격한 파싱 (33일 같은 잘못된 날짜 거부)
                }
                val date = sdf.parse(clean)
                if (date != null) return date.time
            } catch (_: Exception) {
                // 이 포맷으로 파싱 실패 → 다음 포맷 시도
            }
        }

        // 모든 포맷 시도 실패
        Log.e(TAG, "날짜 파싱 전체 실패: '$raw' (정제값: '$clean')")
        return null
    }

    /**
     * 밀리초 타임스탬프를 화면 표시용 날짜 문자열로 변환합니다.
     *
     * @param ms 타임스탬프 (밀리초)
     * @return "yy/MM/dd HH:mm" 형식의 KST 날짜 문자열 (예: "26/04/01 08:00")
     */
    private fun fmtDate(ms: Long): String {
        return SimpleDateFormat("yy/MM/dd HH:mm", Locale.KOREAN).apply {
            timeZone = kstZone
        }.format(Date(ms))
    }
}
