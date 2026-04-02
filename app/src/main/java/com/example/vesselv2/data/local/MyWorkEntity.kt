package com.example.vesselv2.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "my_work")
data class MyWorkEntity(
        @PrimaryKey(autoGenerate = true) val id: Int = 0,
        val vesselName: String,
        val workDate: String, // yyyy-MM-dd
        val totalHours: Double,
        val amount: Int,
        val isNight: Boolean,
        val isWeekend: Boolean,
        val isSkill: Boolean,
        val isVessel: Boolean,
        val rainHours: Int, // 우천 할증 시간 (정수)
        val startTimeMs: Long,
        val endTimeMs: Long
)
