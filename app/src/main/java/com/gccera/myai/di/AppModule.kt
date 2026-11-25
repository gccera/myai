package com.gccera.myai.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.gccera.myai.data.db.AppDatabase
import com.gccera.myai.data.db.ChatDao
import com.gccera.myai.data.db.ImageDao
import com.gccera.myai.data.db.ReportDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** Long-lived scope for work that must outlive a screen (image generation, DB writes). */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class AppScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun database(@ApplicationContext ctx: Context): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, "myai.db")
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()

    @Provides fun chatDao(db: AppDatabase): ChatDao = db.chatDao()
    @Provides fun imageDao(db: AppDatabase): ImageDao = db.imageDao()
    @Provides fun reportDao(db: AppDatabase): ReportDao = db.reportDao()
    @Provides fun documentDao(db: AppDatabase): com.gccera.myai.data.db.DocumentDao = db.documentDao()

    @Provides @Singleton
    fun workManager(@ApplicationContext ctx: Context): WorkManager = WorkManager.getInstance(ctx)

    /** Used ONLY for model downloads. No analytics/telemetry endpoints exist in this app. */
    @Provides @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true) // Hugging Face redirects /resolve/ URLs to its CDN.
        .retryOnConnectionFailure(true)
        .build()

    @Provides @Singleton
    fun json(): Json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    @Provides @Singleton @AppScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
