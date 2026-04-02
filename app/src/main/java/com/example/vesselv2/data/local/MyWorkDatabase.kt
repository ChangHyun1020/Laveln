package com.example.vesselv2.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [MyWorkEntity::class], version = 2, exportSchema = false)
abstract class MyWorkDatabase : RoomDatabase() {
    abstract fun myWorkDao(): MyWorkDao

    companion object {
        @Volatile
        private var INSTANCE: MyWorkDatabase? = null

        fun getDatabase(context: Context): MyWorkDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MyWorkDatabase::class.java,
                    "vessel_v2_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
