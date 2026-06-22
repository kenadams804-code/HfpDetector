package com.example.hfpdetector.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [CallLogEntity::class, SmsEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDb : RoomDatabase() {
    abstract fun callLogDao(): CallLogDao
    abstract fun smsDao(): SmsDao

    companion object {
        @Volatile private var inst: AppDb? = null

        fun get(context: Context): AppDb {
            return inst ?: synchronized(this) {
                inst ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDb::class.java,
                    "lancall.db"
                ).build().also { inst = it }
            }
        }
    }
}
