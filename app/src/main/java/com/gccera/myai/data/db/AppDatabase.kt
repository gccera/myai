package com.gccera.myai.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, ImageEntity::class, ReportEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun imageDao(): ImageDao
    abstract fun reportDao(): ReportDao
}
