package com.minseo41.subfeed.di

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.minseo41.subfeed.data.DispatchingVideoExtractor
import com.minseo41.subfeed.data.VideoExtractor
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ExtractorModule {
    @Binds
    @Singleton
    abstract fun bindVideoExtractor(impl: DispatchingVideoExtractor): VideoExtractor
}

@Module
@InstallIn(SingletonComponent::class)
object FirebaseModule {
    @Provides @Singleton
    fun provideFirestore(): FirebaseFirestore = FirebaseFirestore.getInstance()

    @Provides @Singleton
    fun provideAuth(): FirebaseAuth = FirebaseAuth.getInstance()
}
