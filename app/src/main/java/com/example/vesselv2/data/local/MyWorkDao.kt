package com.example.vesselv2.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MyWorkDao {
    @Query("SELECT * FROM my_work ORDER BY workDate DESC, startTimeMs DESC")
    fun getAllWork(): Flow<List<MyWorkEntity>>

    @Query("SELECT * FROM my_work WHERE workDate LIKE :month || '%' ORDER BY workDate ASC")
    fun getWorkByMonth(month: String): Flow<List<MyWorkEntity>> // month format: yyyy-MM

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWork(work: MyWorkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(works: List<MyWorkEntity>)

    @Delete
    suspend fun deleteWork(work: MyWorkEntity)

    @Query("DELETE FROM my_work WHERE vesselName = :vesselName AND workDate = :workDate")
    suspend fun deleteWorkByNameAndDate(vesselName: String, workDate: String)
}
