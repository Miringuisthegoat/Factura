package com.benjamin.factura.data.local


import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.Upsert
import android.content.Context
import com.benjamin.factura.data.local.dao.ClientDao
import com.benjamin.factura.data.local.dao.ExpenseDao
import com.benjamin.factura.data.local.dao.InvoiceDao
import com.benjamin.factura.data.local.dao.PaymentDao
import com.benjamin.factura.data.model.Client
import com.benjamin.factura.data.model.Converters
import com.benjamin.factura.data.model.Expense
import com.benjamin.factura.data.model.Invoice
import com.benjamin.factura.data.model.Payment
import kotlinx.coroutines.flow.Flow

/*
 * Factura — data/local/AppDatabase.kt
 *
 * Room database + DAOs for the four locally-persisted entities. AppUser and
 * BusinessProfile are NOT here by design — single-user/single-business means
 * there's exactly one of each, cached via PreferencesManager (DataStore)
 * rather than a Room table.
 *
 * Every DAO exposes a matching pair of query shapes:
 *   - Flow<...> queries for reactive UI (ViewModels collect these directly)
 *   - suspend one-shot queries for repository/WorkManager logic that needs a
 *     single read without holding a live collector open
 *
 * Every DAO also exposes the "Via Appia" sync hooks that the offline-sync
 * engine (built in a later phase) needs:
 *   - getUnsynced___()      -> rows with syncStatus != SYNCED, to push to Firestore
 *   - updateSyncStatus()    -> flip a row to SYNCED / FAILED after a push attempt
 *   - hardDelete___By Id()  -> purge a row locally once its PENDING_DELETE has
 *                              been confirmed deleted on Firestore
 *
 * Soft-delete convention: user-facing "delete" always sets isDeleted = true and
 * syncStatus = PENDING_DELETE via softDelete___(); the row is only physically
 * removed from Room after Via Appia confirms the delete propagated.
 */

@Database(
    entities = [Client::class, Invoice::class, Payment::class, Expense::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun clientDao(): ClientDao
    abstract fun invoiceDao(): InvoiceDao
    abstract fun paymentDao(): PaymentDao
    abstract fun expenseDao(): ExpenseDao

    companion object {
        const val DATABASE_NAME = "factura.db"

        @Volatile
        private var instance: AppDatabase? = null

        /**
         * Manual fallback accessor. In practice DatabaseModule (Hilt, next DI
         * phase) provides the singleton via @Provides/@Singleton — this
         * double-checked-locking builder exists so AppDatabase is still usable
         * from contexts without DI (e.g. a raw WorkManager Worker created via
         * WorkManagerFactory, or unit tests) without waiting on that phase.
         */
        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                ).build().also { instance = it }
            }
    }
}