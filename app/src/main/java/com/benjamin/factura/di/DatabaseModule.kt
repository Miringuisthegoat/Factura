package com.benjamin.factura.di

import android.content.Context
import androidx.room.Room
import com.benjamin.factura.data.local.AppDatabase
import com.benjamin.factura.data.local.ClientDao
import com.benjamin.factura.data.local.ExpenseDao
import com.benjamin.factura.data.local.InvoiceDao
import com.benjamin.factura.data.local.PaymentDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/*
 * Factura — di/DatabaseModule.kt
 *
 * Provides the single AppDatabase instance and its four DAOs to the Hilt
 * graph. This is now the source of truth for AppDatabase in the app - any
 * class that needs a DAO should be @Inject-constructed and receive it here,
 * rather than calling AppDatabase.getInstance(context) directly.
 *
 * AppDatabase.getInstance() (the manual double-checked-locking accessor
 * already on the companion object) is left in place for genuinely DI-free
 * contexts - e.g. a raw WorkManager Worker not created through
 * HiltWorkerFactory, or a unit test - but the "Via Appia" sync workers should
 * be @HiltWorker-annotated so they pull the *same* instance from this module
 * instead of opening a second connection to factura.db.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        ).build()

    @Provides
    fun provideClientDao(database: AppDatabase): ClientDao = database.clientDao()

    @Provides
    fun provideInvoiceDao(database: AppDatabase): InvoiceDao = database.invoiceDao()

    @Provides
    fun providePaymentDao(database: AppDatabase): PaymentDao = database.paymentDao()

    @Provides
    fun provideExpenseDao(database: AppDatabase): ExpenseDao = database.expenseDao()
}