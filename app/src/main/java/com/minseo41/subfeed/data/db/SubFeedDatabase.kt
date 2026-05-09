package com.minseo41.subfeed.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ChannelEntity::class,
        FavoriteEntity::class,
        WatchPositionEntity::class,
        VideoEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class SubFeedDatabase : RoomDatabase() {
    abstract fun channelDao(): ChannelDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun watchPositionDao(): WatchPositionDao
    abstract fun videoDao(): VideoDao
}
