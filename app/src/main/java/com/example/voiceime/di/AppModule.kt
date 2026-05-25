package com.example.voiceime.di

import android.content.Context
import com.example.voiceime.ai.DeviceCapability
import com.example.voiceime.ai.GemmaInferenceManager
import com.example.voiceime.ai.ModelDownloadManager
import com.example.voiceime.dictionary.DictionaryDao
import com.example.voiceime.dictionary.DictionaryDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): DictionaryDatabase =
        DictionaryDatabase.create(ctx)

    @Provides
    fun provideDictionaryDao(db: DictionaryDatabase): DictionaryDao = db.dictionaryDao()

    @Provides @Singleton
    fun provideDeviceCapability(@ApplicationContext ctx: Context): DeviceCapability =
        DeviceCapability(ctx)

    @Provides @Singleton
    fun provideGemmaInferenceManager(
        @ApplicationContext ctx: Context,
        deviceCapability: DeviceCapability
    ): GemmaInferenceManager = GemmaInferenceManager(ctx, deviceCapability)

    @Provides @Singleton
    fun provideModelDownloadManager(@ApplicationContext ctx: Context): ModelDownloadManager =
        ModelDownloadManager(ctx)
}
