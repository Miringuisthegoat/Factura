package com.benjamin.factura.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.benjamin.factura.data.model.Invoice
import com.benjamin.factura.data.model.InvoiceStatus
import com.benjamin.factura.data.model.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface InvoiceDao {
    @Upsert
    suspend fun insertInvoice(invoice: Invoice)

    @Upsert
    suspend fun upsertAll(invoices: List<Invoice>)

    @Delete
    suspend fun deleteInvoice(invoice: Invoice)

    @Query("SELECT COUNT(*) FROM invoices")
    suspend fun getInvoiceCount(): Int

    @Query("SELECT * FROM invoices WHERE id = :id LIMIT 1")
    fun getInvoiceById(id: String): Flow<Invoice?>

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
