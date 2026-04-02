package com.example.vesselv2

import org.jsoup.Jsoup
import org.junit.Test
import java.util.*
import java.text.SimpleDateFormat

class DgtApiTest {
    @Test
    fun testVesselStatusHtml() {
        val baseUrl = "https://info.dgtbusan.com/DGT/esvc/vessel/vesselStatus"
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36"
        
        println("=== GET 요청 시작 (CSRF / Cookie 획득) ===")
        val initRes = Jsoup.connect(baseUrl)
            .userAgent(userAgent)
            .timeout(30000)
            .method(org.jsoup.Connection.Method.GET)
            .execute()
            
        val cookies = initRes.cookies()
        val docInit = initRes.parse()
        val csrfToken = docInit.select("meta[name=_csrf]").attr("content")
        val csrfHeader = docInit.select("meta[name=_csrf_header]").attr("content")
        
        println("CSRF Header: $csrfHeader")
        println("CSRF Token: $csrfToken")
        
        // 날짜 세팅
        val cal = Calendar.getInstance()
        val sdf = SimpleDateFormat("yyyyMMdd", Locale.US)
        cal.add(Calendar.DAY_OF_YEAR, -5)
        val fromDate = sdf.format(cal.time)
        cal.add(Calendar.DAY_OF_YEAR, 10)
        val toDate = sdf.format(cal.time)
        
        println("=== POST 요청 시작 (fromDate: $fromDate, toDate: $toDate, vessel: ENSENADA) ===")
        val listRes = Jsoup.connect("https://info.dgtbusan.com/DGT/esvc/vessel/vesselStatus")
            .userAgent(userAgent)
            .timeout(30000)
            .cookies(cookies)
            .apply { if (csrfHeader.isNotEmpty()) header(csrfHeader, csrfToken) }
            .data("fromDate", fromDate)
            .data("toDate", toDate)
            .data("vessel", "ENSENADA")
            .data("voyage", "")
            .method(org.jsoup.Connection.Method.POST)
            .execute()
            
        val doc = listRes.parse()
        val text = doc.outerHtml()
        java.io.File("c:/00_Project/00_Android/03_VesselV2.storage/real_get.html").writeText(text)
        println("Saved to real_get.html length: ${text.length}")
        println("Is 'QC' in text?: ${text.contains("QC")}")
        
        val bodyHtml = doc.body().html()
        println("Partial HTML length: ${bodyHtml.length}")
        println("Partial HTML snippet:\n" + bodyHtml.take(3000))
    }

    @Test
    fun testQcApiEndpoints() {
        val baseUrl = "https://info.dgtbusan.com/DGT/esvc/vessel/vesselStatus"
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36"
        
        println("=== 1. CSRF / Cookie 획득 ===")
        val initRes = Jsoup.connect(baseUrl).userAgent(userAgent).method(org.jsoup.Connection.Method.GET).execute()
        val cookies = initRes.cookies()
        val docInit = initRes.parse()
        val csrfToken = docInit.select("meta[name=_csrf]").attr("content")
        val csrfHeader = docInit.select("meta[name=_csrf_header]").attr("content")
        
        // 2. 전 선석 스케줄 먼저 조회해서 VesselCode/Voyage 하나 가져오기
        println("=== 2. 스케줄 조회 (vesselSchedule) ===")
        val schedUrl = "https://info.dgtbusan.com/DGT/berth/vesselSchedule"
        val now = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val schedPayload = "{\"fromDate\":\"$now\",\"toDate\":\"$now\"}"
        
        val schedRes = Jsoup.connect(schedUrl)
            .userAgent(userAgent).cookies(cookies)
            .apply { if (csrfHeader.isNotEmpty()) header(csrfHeader, csrfToken) }
            .header("Content-Type", "application/json")
            .requestBody(schedPayload).ignoreContentType(true)
            .method(org.jsoup.Connection.Method.POST).execute()
            
        println("Schedule Response: ${schedRes.body()}")
        val schedObj = org.json.JSONObject(schedRes.body())
        val arrays = schedObj.optJSONArray("vesselSchedules")
        
        if (arrays == null || arrays.length() == 0) {
            println("조회된 선박 스케줄이 없습니다.")
            return
        }
        
        // 첫 번째 Working 상태인 선박 찾기
        var targetVsl: org.json.JSONObject? = null
        for (i in 0 until arrays.length()) {
            val v = arrays.getJSONObject(i)
            if (v.optString("status") == "Working") {
                targetVsl = v
                break
            }
        }
        if (targetVsl == null) targetVsl = arrays.getJSONObject(0)
        
        val targetVslNotNull = targetVsl!!
        val vCode = targetVslNotNull.getString("vesselCode")
        val vSeq = targetVslNotNull.getString("voyageSeq")
        val vYear = targetVslNotNull.getString("voyageYear")
        val vVoyage = "$vSeq/$vYear"
        
        println("Target Vessel: ${targetVslNotNull.optString("vesselName")} ($vCode), Voyage: $vVoyage")

        // 3. vesselContainer 테스트
        println("=== 3. vesselContainer 테스트 ===")
        val containerUrl = "https://info.dgtbusan.com/DGT/document/vesselContainer"
        val cPayload = org.json.JSONObject().apply {
            put("vessel", vCode)
            put("voyage", vVoyage)
            put("inOutCodes", org.json.JSONArray().put("D").put("L"))
        }.toString()
        
        val cRes = Jsoup.connect(containerUrl)
            .userAgent(userAgent).cookies(cookies)
            .apply { if (csrfHeader.isNotEmpty()) header(csrfHeader, csrfToken) }
            .header("Content-Type", "application/json")
            .requestBody(cPayload).ignoreContentType(true)
            .method(org.jsoup.Connection.Method.POST).execute()
        
        println("vesselContainer Body: ${cRes.body().take(1000)}...")
        
        // 4. qcWorkAmount 테스트
        println("=== 4. qcWorkAmount 테스트 ===")
        val amountUrl = "https://info.dgtbusan.com/DGT/esvc/qcWorkAmount"
        val aPayload = org.json.JSONObject().apply {
            put("vessel", vCode)
            put("voyage", vVoyage)
        }.toString()
        
        val aRes = Jsoup.connect(amountUrl)
            .userAgent(userAgent).cookies(cookies)
            .apply { if (csrfHeader.isNotEmpty()) header(csrfHeader, csrfToken) }
            .header("Content-Type", "application/json")
            .requestBody(aPayload).ignoreContentType(true)
            .method(org.jsoup.Connection.Method.POST).execute()
            
        println("qcWorkAmount Body: ${aRes.body()}")
    }
}
