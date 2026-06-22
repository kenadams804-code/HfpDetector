package com.example.hfpdetector.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SmsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(e: SmsEntity)

    @Query("SELECT * FROM sms_box ORDER BY ts DESC")
    fun listAll(): List<SmsEntity>

    @Query("DELETE FROM sms_box")
    fun clear()
}
