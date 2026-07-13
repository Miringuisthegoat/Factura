package com.benjamin.factura.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.benjamin.factura.data.model.Payment
import com.benjamin.factura.data.model.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface PaymentDao {
    @Upsert
    suspend fun upsert(payment: Payment)

    @Upsert
    suspend fun upsertAll(payments: List<Payment>)

    @Query("SELECT * FROM payments WHERE invoiceId = :invoiceId AND isDeleted = 0 ORDER BY paidAtMillis DESC")
    fun getPaymentsByInvoice(invoiceId: String): Flow<List<Payment>>

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
