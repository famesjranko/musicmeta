package com.landofoz.musicmeta.android.di

import android.content.Context
import com.landofoz.musicmeta.android.cache.EnrichmentCacheDao
import com.landofoz.musicmeta.android.cache.EnrichmentCacheDatabase
import com.landofoz.musicmeta.android.cache.NegativeCacheDao
import com.landofoz.musicmeta.android.cache.RoomEnrichmentCache
import com.landofoz.musicmeta.android.cache.SelectionDao
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
public object HiltEnrichmentModule {

    public const val DEFAULT_DATABASE_NAME: String = "enrichment_cache.db"

    @Provides
    @Singleton
    public fun provideEnrichmentCacheDatabase(
        @ApplicationContext context: Context,
    ): EnrichmentCacheDatabase = EnrichmentCacheDatabase.create(context, DEFAULT_DATABASE_NAME)

    @Provides
    @Singleton
    public fun provideEnrichmentCacheDao(database: EnrichmentCacheDatabase): EnrichmentCacheDao =
        database.enrichmentCacheDao()

    @Provides
    @Singleton
    public fun provideNegativeCacheDao(database: EnrichmentCacheDatabase): NegativeCacheDao =
        database.negativeCacheDao()

    @Provides
    @Singleton
    public fun provideSelectionDao(database: EnrichmentCacheDatabase): SelectionDao =
        database.selectionDao()

    @Provides
    @Singleton
    public fun provideRoomEnrichmentCache(
        dao: EnrichmentCacheDao,
        negativeDao: NegativeCacheDao,
        selectionDao: SelectionDao,
    ): RoomEnrichmentCache = RoomEnrichmentCache(dao, negativeDao, selectionDao)
}
