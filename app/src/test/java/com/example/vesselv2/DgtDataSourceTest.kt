package com.example.vesselv2

import com.example.vesselv2.data.remote.DgtDataSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.*

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
}
