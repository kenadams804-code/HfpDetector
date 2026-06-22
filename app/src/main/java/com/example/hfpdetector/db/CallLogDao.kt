package com.example.hfpdetector.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CallLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(e: CallLogEntity)

    @Query("SELECT * FROM call_log ORDER BY ts DESC")
    fun listAll(): List<CallLogEntity>

    @Query("UPDATE call_log SET state=:state, lastUpdateTs=:ts WHERE callId=:callId")
    fun updateState(callId: String, state: String, ts: Long)

    @Query("DELETE FROM call_log")
    fun clear()
}
