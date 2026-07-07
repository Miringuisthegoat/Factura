package com.benjamin.factura.data.local


import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.Upsert
import android.content.Context
import com.benjamin.factura.data.model.Client
import com.benjamin.factura.data.model.Converters
import com.benjamin.factura.data.model.Expense
import com.benjamin.factura.data.model.ExpenseCategory
import com.benjamin.factura.data.model.Invoice
import com.benjamin.factura.data.model.InvoiceStatus
import com.benjamin.factura.data.model.Payment
import com.benjamin.factura.data.model.SyncStatus
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

// =============================================================================
// ClientDao
// =============================================================================

@Dao
interface ClientDao {

    @Upsert
    suspend fun upsert(client: Client)

    @Upsert
    suspend fun upsertAll(clients: List<Client>)

    @Query("SELECT * FROM clients WHERE businessId = :businessId AND isDeleted = 0 ORDER BY name ASC")
    fun getClientsFlow(businessId: String): Flow<List<Client>>

    @Query("SELECT * FROM clients WHERE id = :id LIMIT 1")
    fun getClientByIdFlow(id: String): Flow<Client?>

    @Query("SELECT * FROM clients WHERE id = :id LIMIT 1")
    suspend fun getClientByIdOnce(id: String): Client?

    @Query(
        """
        SELECT * FROM clients
        WHERE businessId = :businessId AND isDeleted = 0
        AND (name LIKE '%' || :query || '%' OR email LIKE '%' || :query || '%' OR phone LIKE '%' || :query || '%')
        ORDER BY name ASC
        """
    )
    fun searchClientsFlow(businessId: String, query: String): Flow<List<Client>>

    @Query("SELECT * FROM clients WHERE syncStatus != :synced")
    suspend fun getUnsyncedClients(synced: SyncStatus = SyncStatus.SYNCED): List<Client>

    @Query("UPDATE clients SET syncStatus = :status WHERE id = :id")
    suspend fun updateSyncStatus(id: String, status: SyncStatus)

    @Query("UPDATE clients SET isDeleted = 1, syncStatus = :pendingDelete, updatedAt = :nowMillis WHERE id = :id")
    suspend fun softDelete(id: String, nowMillis: Long = System.currentTimeMillis(), pendingDelete: SyncStatus = SyncStatus.PENDING_DELETE)

    @Query("DELETE FROM clients WHERE id = :id")
    suspend fun hardDeleteById(id: String)
}

// =============================================================================
// InvoiceDao
// =============================================================================

@Dao
interface InvoiceDao {

    @Upsert
    suspend fun upsert(invoice: Invoice)

    @Upsert
    suspend fun upsertAll(invoices: List<Invoice>)

    @Query("SELECT * FROM invoices WHERE businessId = :businessId AND isDeleted = 0 ORDER BY issueDateMillis DESC")
    fun getInvoicesFlow(businessId: String): Flow<List<Invoice>>

    @Query("SELECT * FROM invoices WHERE id = :id LIMIT 1")
    fun getInvoiceByIdFlow(id: String): Flow<Invoice?>

    @Query("SELECT * FROM invoices WHERE id = :id LIMIT 1")
    suspend fun getInvoiceByIdOnce(id: String): Invoice?

    @Query("SELECT * FROM invoices WHERE clientId = :clientId AND isDeleted = 0 ORDER BY issueDateMillis DESC")
    fun getInvoicesForClientFlow(clientId: String): Flow<List<Invoice>>

    @Query("SELECT * FROM invoices WHERE businessId = :businessId AND status = :status AND isDeleted = 0 ORDER BY issueDateMillis DESC")
    fun getInvoicesByStatusFlow(businessId: String, status: InvoiceStatus): Flow<List<Invoice>>

    @Query(
        """
        SELECT * FROM invoices
        WHERE businessId = :businessId AND isDeleted = 0
        AND (invoiceNumber LIKE '%' || :query || '%' OR notes LIKE '%' || :query || '%')
        ORDER BY issueDateMillis DESC
        """
    )
    fun searchInvoicesFlow(businessId: String, query: String): Flow<List<Invoice>>

    @Query("SELECT * FROM invoices WHERE businessId = :businessId AND isDeleted = 0 ORDER BY createdAt DESC LIMIT :limit")
    fun getRecentInvoicesFlow(businessId: String, limit: Int = 5): Flow<List<Invoice>>

    /** Most recent invoices for a specific client, used by Gemini auto-fill (last 5, by default). */
    @Query("SELECT * FROM invoices WHERE clientId = :clientId AND isDeleted = 0 ORDER BY issueDateMillis DESC LIMIT :limit")
    suspend fun getRecentInvoicesForClientOnce(clientId: String, limit: Int = 5): List<Invoice>

    @Query("SELECT * FROM invoices WHERE syncStatus != :synced")
    suspend fun getUnsyncedInvoices(synced: SyncStatus = SyncStatus.SYNCED): List<Invoice>

    @Query("UPDATE invoices SET syncStatus = :status WHERE id = :id")
    suspend fun updateSyncStatus(id: String, status: SyncStatus)

    @Query("UPDATE invoices SET status = :status, updatedAt = :nowMillis WHERE id = :id")
    suspend fun updateStatus(id: String, status: InvoiceStatus, nowMillis: Long = System.currentTimeMillis())

    @Query("UPDATE invoices SET amountPaid = :amountPaid, updatedAt = :nowMillis WHERE id = :id")
    suspend fun updateAmountPaid(id: String, amountPaid: Double, nowMillis: Long = System.currentTimeMillis())

    @Query("UPDATE invoices SET isDeleted = 1, syncStatus = :pendingDelete, updatedAt = :nowMillis WHERE id = :id")
    suspend fun softDelete(id: String, nowMillis: Long = System.currentTimeMillis(), pendingDelete: SyncStatus = SyncStatus.PENDING_DELETE)

    @Query("DELETE FROM invoices WHERE id = :id")
    suspend fun hardDeleteById(id: String)
}

// =============================================================================
// PaymentDao
// =============================================================================

@Dao
interface PaymentDao {

    @Upsert
    suspend fun upsert(payment: Payment)

    @Upsert
    suspend fun upsertAll(payments: List<Payment>)

    @Query("SELECT * FROM payments WHERE businessId = :businessId AND isDeleted = 0 ORDER BY paidAtMillis DESC")
    fun getPaymentsFlow(businessId: String): Flow<List<Payment>>

    @Query("SELECT * FROM payments WHERE invoiceId = :invoiceId AND isDeleted = 0 ORDER BY paidAtMillis DESC")
    fun getPaymentsForInvoiceFlow(invoiceId: String): Flow<List<Payment>>

    @Query("SELECT * FROM payments WHERE invoiceId = :invoiceId AND isDeleted = 0 ORDER BY paidAtMillis DESC")
    suspend fun getPaymentsForInvoiceOnce(invoiceId: String): List<Payment>

    @Query("SELECT * FROM payments WHERE clientId = :clientId AND isDeleted = 0 ORDER BY paidAtMillis DESC")
    fun getPaymentsForClientFlow(clientId: String): Flow<List<Payment>>

    @Query("SELECT * FROM payments WHERE id = :id LIMIT 1")
    suspend fun getPaymentByIdOnce(id: String): Payment?

    @Query("SELECT * FROM payments WHERE syncStatus != :synced")
    suspend fun getUnsyncedPayments(synced: SyncStatus = SyncStatus.SYNCED): List<Payment>

    @Query("UPDATE payments SET syncStatus = :status WHERE id = :id")
    suspend fun updateSyncStatus(id: String, status: SyncStatus)

    @Query("UPDATE payments SET isDeleted = 1, syncStatus = :pendingDelete WHERE id = :id")
    suspend fun softDelete(id: String, pendingDelete: SyncStatus = SyncStatus.PENDING_DELETE)

    @Query("DELETE FROM payments WHERE id = :id")
    suspend fun hardDeleteById(id: String)
}

// =============================================================================
// ExpenseDao
// =============================================================================

@Dao
interface ExpenseDao {

    @Upsert
    suspend fun upsert(expense: Expense)

    @Upsert
    suspend fun upsertAll(expenses: List<Expense>)

    @Query("SELECT * FROM expenses WHERE businessId = :businessId AND isDeleted = 0 ORDER BY expenseDateMillis DESC")
    fun getExpensesFlow(businessId: String): Flow<List<Expense>>

    @Query("SELECT * FROM expenses WHERE id = :id LIMIT 1")
    fun getExpenseByIdFlow(id: String): Flow<Expense?>

    @Query("SELECT * FROM expenses WHERE id = :id LIMIT 1")
    suspend fun getExpenseByIdOnce(id: String): Expense?

    @Query("SELECT * FROM expenses WHERE businessId = :businessId AND category = :category AND isDeleted = 0 ORDER BY expenseDateMillis DESC")
    fun getExpensesByCategoryFlow(businessId: String, category: ExpenseCategory): Flow<List<Expense>>

    @Query(
        """
        SELECT * FROM expenses
        WHERE businessId = :businessId AND isDeleted = 0
        AND expenseDateMillis BETWEEN :startMillis AND :endMillis
        ORDER BY expenseDateMillis DESC
        """
    )
    fun getExpensesInRangeFlow(businessId: String, startMillis: Long, endMillis: Long): Flow<List<Expense>>

    @Query(
        """
        SELECT * FROM expenses
        WHERE businessId = :businessId AND isDeleted = 0
        AND (vendor LIKE '%' || :query || '%' OR notes LIKE '%' || :query || '%')
        ORDER BY expenseDateMillis DESC
        """
    )
    fun searchExpensesFlow(businessId: String, query: String): Flow<List<Expense>>

    @Query("SELECT * FROM expenses WHERE syncStatus != :synced")
    suspend fun getUnsyncedExpenses(synced: SyncStatus = SyncStatus.SYNCED): List<Expense>

    @Query("UPDATE expenses SET syncStatus = :status WHERE id = :id")
    suspend fun updateSyncStatus(id: String, status: SyncStatus)

    @Query("UPDATE expenses SET isDeleted = 1, syncStatus = :pendingDelete, updatedAt = :nowMillis WHERE id = :id")
    suspend fun softDelete(id: String, nowMillis: Long = System.currentTimeMillis(), pendingDelete: SyncStatus = SyncStatus.PENDING_DELETE)

    @Query("DELETE FROM expenses WHERE id = :id")
    suspend fun hardDeleteById(id: String)
}

// =============================================================================
// AppDatabase
// =============================================================================

@Database(
    entities = [Client::class, Invoice::class, Payment::class, Expense::class],
    version = 1,
    exportSchema = true
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