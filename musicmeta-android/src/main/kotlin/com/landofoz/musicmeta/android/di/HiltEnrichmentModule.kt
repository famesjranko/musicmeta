package com.landofoz.musicmeta.android.di

import android.content.Context
import androidx.room.Room
import com.landofoz.musicmeta.android.cache.EnrichmentCacheDao
import com.landofoz.musicmeta.android.cache.EnrichmentCacheDatabase
import com.landofoz.musicmeta.android.cache.RoomEnrichmentCache
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing enrichment-android dependencies.
 * Apps that use Hilt can install this module to get Room cache wiring for free.
 */
@Module
@InstallIn(SingletonComponent::class)
object HiltEnrichmentModule {

    @Provides
    @Singleton
    fun provideEnrichmentCacheDatabase(
        @ApplicationContext context: Context,
    ): EnrichmentCacheDatabase = Room.databaseBuilder(
        context,
        EnrichmentCacheDatabase::class.java,
        "enrichment_cache.db",
    ).build()

    @Provides
    @Singleton
    fun provideEnrichmentCacheDao(database: EnrichmentCacheDatabase): EnrichmentCacheDao =
        database.enrichmentCacheDao()

    @Provides
    @Singleton
    fun provideRoomEnrichmentCache(dao: EnrichmentCacheDao): RoomEnrichmentCache =
        RoomEnrichmentCache(dao)
}
