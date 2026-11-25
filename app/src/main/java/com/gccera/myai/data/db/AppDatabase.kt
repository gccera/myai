package com.gccera.myai.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ConversationEntity::class, MessageEntity::class, ImageEntity::class, ReportEntity::class,
        DocumentEntity::class, ChunkEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun imageDao(): ImageDao
    abstract fun reportDao(): ReportDao
    abstract fun documentDao(): DocumentDao

    companion object {
        /** v1 → v2 (Phase 2): documents + chunks for RAG, photo attachments and sources on messages. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `conversations` ADD COLUMN `documentId` INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `imagePath` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `sources` TEXT DEFAULT NULL")
                db.execSQL("CREATE TABLE IF NOT EXISTS `documents` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `chunkCount` INTEGER NOT NULL, `charCount` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `chunks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `documentId` INTEGER NOT NULL, `idx` INTEGER NOT NULL, `text` TEXT NOT NULL, `embedding` BLOB NOT NULL, FOREIGN KEY(`documentId`) REFERENCES `documents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chunks_documentId` ON `chunks` (`documentId`)")
            }
        }
    }
}
