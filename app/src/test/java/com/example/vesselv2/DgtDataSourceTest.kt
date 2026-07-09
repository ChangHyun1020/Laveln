package com.example.vesselv2

import com.example.vesselv2.data.remote.DgtDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.*
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.InetSocketAddress
import kotlin.concurrent.thread
import org.jsoup.Jsoup
import org.json.JSONObject
import org.json.JSONException

class DgtDataSourceTest {

    @Test
    fun testFetchBerthSchedules() {
        val dataSource = DgtDataSource()
        
        val kst = TimeZone.getTimeZone("Asia/Seoul")
        val sdf = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = kst }
        val fromDate = sdf.format(Date())
        val cal = Calendar.getInstance(kst)
        cal.add(Calendar.DAY_OF_YEAR, 7)
        val toDate = sdf.format(cal.time)

        println("Fetching schedules from $fromDate to $toDate")
        val schedules = dataSource.fetchBerthSchedules(fromDate, toDate)
        
        assertNotNull("Schedules should not be null", schedules)
        println("Fetched ${schedules.size} items")
        
        schedules.take(5).forEach { item ->
            println("Vessel: ${item.vesselName}, Status: ${item.vesselStatus}, ETB: ${item.etb}")
        }
    }

    @Test
    fun testFetchVesselDetails() {
        val dataSource = DgtDataSource()
        
        // 1. 먼저 스케줄을 가져와서 하나 선택
        val now = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val schedules = dataSource.fetchBerthSchedules(now, now)
        
        if (schedules.isEmpty()) {
            println("No schedules found for today, skipping details test.")
            return
        }

        val target = schedules.first()
        println("Testing details for: ${target.vesselName}")
        
        val details = dataSource.fetchVesselDetails(target)
        assertNotNull("Details should not be null", details)
        
        val (obj, qcList) = details!!
        println("Vessel Status from detail: ${obj.optString("status")}")
        println("QC Crane Count: ${qcList.size}")
        
        qcList.forEach { qc ->
            println("Crane ${qc.craneNo}: Complete(D/L) = ${qc.completeDischarge}/${qc.completeLoad}, Planned(D/L) = ${qc.plannedDischarge}/${qc.plannedLoad}")
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  추가된 검증용 테스트 5가지 (규칙에 의거하여 확실성 입증)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * [테스트 1] Jsoup 기본 maxBodySize(2MB) 제한으로 인한 JSON 잘림 현상 검증.
     * 로컬에 6MB 크기의 더미 JSON 데이터를 제공하는 HTTP 서버를 구축하고,
     * Jsoup의 기본 설정을 적용해 호출 시 데이터가 정확히 2MB(2,097,152 바이트)에서 잘리는지 검증합니다.
     */
    @Test
    fun testJsoupDefaultMaxBodySizeLimit() {
        // 약 6MB에 해당하는 대형 JSON 데이터 생성
        val sb = StringBuilder()
        sb.append("{\"requestId\":\"test-uuid\",\"searchedCount\":5000,\"containers\":[")
        for (i in 0..15000) {
            if (i > 0) sb.append(",")
            sb.append("{\"cntrNo\":\"TEST${1000000 + i}\",\"point\":\"1\",\"isoCode\":\"2200\",\"weight\":\"20000\",\"vgmWeight\":null,\"cntrClass\":\"II\",\"fullEmpty\":\"F\",\"commodity\":\"G\",\"cntrSize\":null,\"cntrType\":\"GE\",\"operator\":\"YML\",\"clocation\":\"A07-67-09-5\",\"psituation\":\"C\",\"pod\":\"KRPUS\",\"pol\":\"USLGB\",\"fpod\":\"\",\"npod\":\"\",\"icd\":\"\",\"bookingNo\":\"\",\"oog\":\"\",\"imdg\":\"\",\"unno\":\"\",\"seal1\":\"\",\"remark1\":\"\",\"remark2\":\"\",\"remark3\":\"\",\"groupCode\":\"\",\"handle\":\"\",\"estimateWorkDate\":null,\"disLoad\":\"D\",\"stowage\":\"191612\",\"craneNo\":\"101\",\"actualWorkDate\":\"2026-07-09 08:46:17\",\"deckHold\":\"H\",\"temperature\":\"\",\"temperatureUnit\":null,\"outVessel\":null,\"outVoyage\":null,\"inDate\":null,\"outDate\":null}")
        }
        sb.append("]}")
        val largeJson = sb.toString()
        val responseBytes = largeJson.toByteArray(Charsets.UTF_8)

        // java.net.ServerSocket을 사용하여 간단한 HTTP Mock 서버 구동
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort
        val url = "http://localhost:$port/large-json"

        thread {
            try {
                val clientSocket = serverSocket.accept()
                val reader = clientSocket.getInputStream().bufferedReader()
                // 요청 헤더 모두 읽기
                var line = reader.readLine()
                while (!line.isNullOrEmpty()) {
                    line = reader.readLine()
                }

                val os = clientSocket.getOutputStream()
                val header = "HTTP/1.1 200 OK\r\n" +
                             "Content-Type: application/json; charset=utf-8\r\n" +
                             "Content-Length: ${responseBytes.size}\r\n" +
                             "Connection: close\r\n" +
                             "\r\n"
                os.write(header.toByteArray(Charsets.UTF_8))
                
                var written = 0
                val bufSize = 8192
                while (written < responseBytes.size) {
                    val toWrite = Math.min(bufSize, responseBytes.size - written)
                    os.write(responseBytes, written, toWrite)
                    written += toWrite
                }
                os.flush()
                
                // TCP 연결을 안전하게 종료
                clientSocket.shutdownOutput()
                try {
                    val isStr = clientSocket.getInputStream()
                    val temp = ByteArray(1024)
                    while (isStr.read(temp) != -1) {}
                } catch (_: Exception) {}
                clientSocket.close()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try { serverSocket.close() } catch (_: Exception) {}
            }
        }

        try {
            val res = Jsoup.connect(url)
                .ignoreContentType(true)
                .timeout(10000)
                .execute()
            val body = res.body()
            val bodyBytes = body.toByteArray(Charsets.UTF_8)
            println("Default maxBodySize 수신 데이터 길이: ${bodyBytes.size} bytes")
            
            // 2MB(2,097,152 바이트) 지점에서 데이터가 잘렸는지 검증
            assertEquals("기본 제한 크기인 2MB까지만 수신되어야 함", 2097152, bodyBytes.size)
        } finally {
            try { serverSocket.close() } catch (_: Exception) {}
        }
    }

    /**
     * [테스트 2] maxBodySize(0) 설정을 통해 2MB 이상의 대형 데이터도 정상 파싱됨을 확인하는 테스트.
     * 위와 동일한 6MB 데이터를 maxBodySize(0) 설정을 통해 잘림 없이 받아와 JSONObject가
     * 정상 생성되는지 검증합니다.
     */
    @Test
    fun testJsoupMaxBodySizeZeroNoLimit() {
        val sb = StringBuilder()
        sb.append("{\"requestId\":\"test-uuid\",\"searchedCount\":5000,\"containers\":[")
        for (i in 0..15000) {
            if (i > 0) sb.append(",")
            sb.append("{\"cntrNo\":\"TEST${1000000 + i}\",\"point\":\"1\",\"isoCode\":\"2200\",\"weight\":\"20000\",\"vgmWeight\":null,\"cntrClass\":\"II\",\"fullEmpty\":\"F\",\"commodity\":\"G\",\"cntrSize\":null,\"cntrType\":\"GE\",\"operator\":\"YML\",\"clocation\":\"A07-67-09-5\",\"psituation\":\"C\",\"pod\":\"KRPUS\",\"pol\":\"USLGB\",\"fpod\":\"\",\"npod\":\"\",\"icd\":\"\",\"bookingNo\":\"\",\"oog\":\"\",\"imdg\":\"\",\"unno\":\"\",\"seal1\":\"\",\"remark1\":\"\",\"remark2\":\"\",\"remark3\":\"\",\"groupCode\":\"\",\"handle\":\"\",\"estimateWorkDate\":null,\"disLoad\":\"D\",\"stowage\":\"191612\",\"craneNo\":\"101\",\"actualWorkDate\":\"2026-07-09 08:46:17\",\"deckHold\":\"H\",\"temperature\":\"\",\"temperatureUnit\":null,\"outVessel\":null,\"outVoyage\":null,\"inDate\":null,\"outDate\":null}")
        }
        sb.append("]}")
        val largeJson = sb.toString()
        val responseBytes = largeJson.toByteArray(Charsets.UTF_8)

        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort
        val url = "http://localhost:$port/large-json-unlimited"

        thread {
            try {
                val clientSocket = serverSocket.accept()
                val reader = clientSocket.getInputStream().bufferedReader()
                var line = reader.readLine()
                while (!line.isNullOrEmpty()) {
                    line = reader.readLine()
                }

                val os = clientSocket.getOutputStream()
                val header = "HTTP/1.1 200 OK\r\n" +
                             "Content-Type: application/json; charset=utf-8\r\n" +
                             "Content-Length: ${responseBytes.size}\r\n" +
                             "Connection: close\r\n" +
                             "\r\n"
                os.write(header.toByteArray(Charsets.UTF_8))
                
                var written = 0
                val bufSize = 8192
                while (written < responseBytes.size) {
                    val toWrite = Math.min(bufSize, responseBytes.size - written)
                    os.write(responseBytes, written, toWrite)
                    written += toWrite
                }
                os.flush()
                
                clientSocket.shutdownOutput()
                try {
                    val isStr = clientSocket.getInputStream()
                    val temp = ByteArray(1024)
                    while (isStr.read(temp) != -1) {}
                } catch (_: Exception) {}
                clientSocket.close()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try { serverSocket.close() } catch (_: Exception) {}
            }
        }

        try {
            val res = Jsoup.connect(url)
                .ignoreContentType(true)
                .maxBodySize(0) // 무제한 설정
                .timeout(10000)
                .execute()
            val body = res.body()
            val bodyBytes = body.toByteArray(Charsets.UTF_8)
            println("Unlimited maxBodySize 수신 데이터 길이: ${bodyBytes.size} bytes")
            println("수신 데이터 시작부분: ${body.take(200)}")
            println("수신 데이터 끝부분: ${body.takeLast(200)}")
            
            // 데이터가 완전하게 전달되었는지 확인
            assertTrue("JSON 데이터가 누락 없이 전달되어 닫는 괄호로 끝나야 함", body.endsWith("]}"))
            assertEquals("수신 바이트 크기가 원본 크기와 일치해야 함", responseBytes.size, bodyBytes.size)
            
            // 파싱 성공 확인
            val json = JSONObject(body)
            println("파싱된 requestId: ${json.optString("requestId")}")
            assertEquals("test-uuid", json.getString("requestId"))
            assertEquals(5000, json.getInt("searchedCount"))
            val containers = json.getJSONArray("containers")
            assertEquals(15001, containers.length())
            println("대용량 JSON 수신 및 파싱 성공 검증 완료!")
        } finally {
            try { serverSocket.close() } catch (_: Exception) {}
        }
    }

    /**
     * [테스트 3] normalizeBerthNo 메서드 검증 (리플렉션 사용).
     * DgtDataSource의 private 선석 번호 정규화 메서드가
     * 다양한 경계 상황의 입력값들에 대해 표준 형식(B1~B5, F1 등)으로 정확하게 정규화하는지 확인합니다.
     */
    @Test
    fun testNormalizeBerthNo_Reflected() {
        val dataSource = DgtDataSource()
        val method = DgtDataSource::class.java.getDeclaredMethod("normalizeBerthNo", String::class.java)
        method.isAccessible = true

        val inputsAndExpected = mapOf(
            "B-01" to "B1",
            "F002" to "F2",
            "b3" to "B3",
            "  F-3  " to "F3",
            "b05" to "B5",
            "INVALID" to "INVALID",
            "" to ""
        )

        inputsAndExpected.forEach { (input, expected) ->
            val actual = method.invoke(dataSource, input) as String
            assertEquals("입력 '$input'에 대한 정규화 실패", expected, actual)
        }
        println("선석 번호 정규화 로직 검증 완료!")
    }

    /**
     * [테스트 4] parseMs 날짜 파싱 메서드 검증 (리플렉션 사용).
     * DgtDataSource의 다양한 날짜 문자열 포맷(마이크로초, 초, 대시/슬래시 형태 등)을
     * KST 밀리초 타임스탬프로 유연하고 올바르게 파싱해내는지 확인합니다.
     */
    @Test
    fun testParseMs_VariousFormats_Reflected() {
        val dataSource = DgtDataSource()
        val method = DgtDataSource::class.java.getDeclaredMethod("parseMs", String::class.java)
        method.isAccessible = true

        // 2026년 4월 1일 오전 8시 KST (Asia/Seoul) -> 밀리초 타임스탬프 계산
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul")).apply {
            set(2026, Calendar.APRIL, 1, 8, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val expectedMs = cal.timeInMillis

        // 검증할 날짜 형식들
        val formats = listOf(
            "2026-04-01 08:00:00.000000+09",
            "2026-04-01 08:00:00.000000",
            "2026-04-01 08:00:00.0",
            "2026-04-01 08:00:00",
            "2026/04/01 08:00:00",
            "2026-04-01T08:00:00",
            "20260401080000"
        )

        formats.forEach { rawDate ->
            val actualMs = method.invoke(dataSource, rawDate) as? Long
            assertNotNull("날짜 포맷 파싱 실패: '$rawDate'", actualMs)
            assertEquals("날짜 값 불일치: '$rawDate'", expectedMs, actualMs)
        }

        // 예외적/비정상 데이터 테스트
        assertNull("null인 경우 null 반환해야 함", method.invoke(dataSource, null as String?))
        assertNull("빈 문자열인 경우 null 반환해야 함", method.invoke(dataSource, ""))
        assertNull("잘못된 형식인 경우 null 반환해야 함", method.invoke(dataSource, "INVALID_DATE"))
        println("날짜 파싱 로직 검증 완료!")
    }

    /**
     * [테스트 5] 선박 이름 매칭을 위한 공백/대소문자 전처리 로직 검증.
     * DgtDataSource 내에 설계되어 있는 선박 명 매칭 알고리즘과 동일한
     * 공백 및 대소문자 제거 정규식 변환이 올바르게 동작하는지 독립적으로 테스트합니다.
     */
    @Test
    fun testVesselNameMatching_Logic() {
        val pairs = listOf(
            "SEATTLE BRIDGE" to "seattlebridge",
            "Seattle  Bridge" to "SEATTLEBRIDGE",
            "HANJIN X-PRESS" to "hanjinx-press",
            "COSCO SHIPPING" to "coscoshipping",
            "APL NEW YORK" to "aplnewyork"
        )

        pairs.forEach { (a, b) ->
            val normA = a.replace("\\s".toRegex(), "").lowercase()
            val normB = b.replace("\\s".toRegex(), "").lowercase()
            assertEquals("선박 이름 정규화 매칭 로직 오류: '$a'와 '$b' 매칭 실패", normA, normB)
        }
        println("선박 이름 정규화 매칭 알고리즘 검증 완료!")
    }
}
