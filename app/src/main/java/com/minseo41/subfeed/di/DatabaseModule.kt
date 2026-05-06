package com.minseo41.subfeed.di

import android.content.Context
import androidx.room.Room
import com.minseo41.subfeed.data.db.ChannelDao
import com.minseo41.subfeed.data.db.FavoriteDao
import com.minseo41.subfeed.data.db.SubFeedDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SubFeedDatabase =
        Room.databaseBuilder(context, SubFeedDatabase::class.java, "subfeed.db")
            .build()

    @Provides
    fun provideChannelDao(db: SubFeedDatabase): ChannelDao = db.channelDao()

    @Provides
    fun provideFavoriteDao(db: SubFeedDatabase): FavoriteDao = db.favoriteDao()
}
