package com.benjamin.factura.di


import com.benjamin.factura.BuildConfig
import com.google.ai.client.generativeai.GenerativeModel
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/*
 * Factura — di/GeminiModule.kt
 *
 * Provides a single GenerativeModel configured for gemini-1.5-flash, used by
 * both AI features (auto-fill for returning clients, smart overdue-reminder
 * drafts) - they differ only in the prompt/context sent per-call, not in the
 * model configuration, so one shared instance covers both.
 *
 * Assumes GEMINI_API_KEY is already injected as a BuildConfig field from the
 * (locked) Gradle phase, the same way DEFAULT_CURRENCY is. If that field
 * doesn't exist yet under that exact name, this won't compile - let me know
 * the actual field name and I'll adjust.
 */
@Module
@InstallIn(SingletonComponent::class)
object GeminiModule {

    @Provides
    @Singleton
    fun provideGenerativeModel(): GenerativeModel = GenerativeModel(
        modelName = "gemini-2.5-flash-lite",
        apiKey = BuildConfig.GEMINI_API_KEY
    )
}