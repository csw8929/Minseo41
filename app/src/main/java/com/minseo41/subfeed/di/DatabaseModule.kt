package com.minseo41.subfeed.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.minseo41.subfeed.data.db.ChannelDao
import com.minseo41.subfeed.data.db.FavoriteDao
import com.minseo41.subfeed.data.db.SubFeedDatabase
import com.minseo41.subfeed.data.db.WatchPositionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `watch_positions` (
                    `videoId` TEXT NOT NULL PRIMARY KEY,
                    `positionMs` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SubFeedDatabase =
        Room.databaseBuilder(context, SubFeedDatabase::class.java, "subfeed.db")
            .addMigrations(MIGRATION_1_2)
            .build()

    @Provides
    fun provideChannelDao(db: SubFeedDatabase): ChannelDao = db.channelDao()

    @Provides
    fun provideFavoriteDao(db: SubFeedDatabase): FavoriteDao = db.favoriteDao()

    @Provides
    fun provideWatchPositionDao(db: SubFeedDatabase): WatchPositionDao = db.watchPositionDao()
}
