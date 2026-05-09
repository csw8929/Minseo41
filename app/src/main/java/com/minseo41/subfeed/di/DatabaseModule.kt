package com.minseo41.subfeed.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.minseo41.subfeed.data.db.ChannelDao
import com.minseo41.subfeed.data.db.FavoriteDao
import com.minseo41.subfeed.data.db.SubFeedDatabase
import com.minseo41.subfeed.data.db.VideoDao
import com.minseo41.subfeed.data.db.WatchPositionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

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

private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE channels ADD COLUMN lastFetchAtMs INTEGER")
        db.execSQL("ALTER TABLE channels ADD COLUMN lastFetchOk INTEGER")
        db.execSQL("ALTER TABLE channels ADD COLUMN lastFetchError TEXT")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `videos` (
                `videoId` TEXT NOT NULL PRIMARY KEY,
                `channelId` TEXT NOT NULL,
                `channelName` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `thumbnailUrl` TEXT NOT NULL,
                `durationSeconds` INTEGER NOT NULL,
                `uploadedAt` INTEGER NOT NULL,
                `isUnread` INTEGER NOT NULL,
                `firstSeenAtMs` INTEGER NOT NULL,
                FOREIGN KEY(`channelId`) REFERENCES `channels`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_videos_channelId` ON `videos` (`channelId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_videos_uploadedAt` ON `videos` (`uploadedAt`)")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SubFeedDatabase =
        Room.databaseBuilder(context, SubFeedDatabase::class.java, "subfeed.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()

    @Provides
    fun provideChannelDao(db: SubFeedDatabase): ChannelDao = db.channelDao()

    @Provides
    fun provideFavoriteDao(db: SubFeedDatabase): FavoriteDao = db.favoriteDao()

    @Provides
    fun provideWatchPositionDao(db: SubFeedDatabase): WatchPositionDao = db.watchPositionDao()

    @Provides
    fun provideVideoDao(db: SubFeedDatabase): VideoDao = db.videoDao()
}
