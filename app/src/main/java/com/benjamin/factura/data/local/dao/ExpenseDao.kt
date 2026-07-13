package com.benjamin.factura.data.local.dao


import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.benjamin.factura.data.model.Expense
import com.benjamin.factura.data.model.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseDao {

    // ── Reactive queries (Room as UI source of truth) ──────────────────

    @Query("SELECT * FROM expenses WHERE isDeleted = 0 ORDER BY expenseDateMillis DESC")
    fun getAllExpenses(): Flow<List<Expense>>

    @Query("SELECT * FROM expenses WHERE id = :expenseId AND isDeleted = 0 LIMIT 1")
    fun getExpenseById(expenseId: String): Flow<Expense?>

    @Query("SELECT * FROM expenses WHERE category = :category AND isDeleted = 0 ORDER BY expenseDateMillis DESC")
    fun getExpensesByCategory(category: String): Flow<List<Expense>>

    // ── Suspend one-shots ───────────────────────────────────────────────

    @Query("SELECT COUNT(*) FROM expenses WHERE isDeleted = 0")
    suspend fun getExpenseCount(): Int

    @Query("SELECT * FROM expenses WHERE syncStatus != :syncedStatus")
    suspend fun getUnsyncedExpenses(syncedStatus: SyncStatus = SyncStatus.SYNCED): List<Expense>

    // ── Writes ───────────────────────────────────────────────────────────

    @Upsert
    suspend fun insertExpense(expense: Expense)

    @Delete
    suspend fun deleteExpense(expense: Expense)

    @Query("UPDATE expenses SET isDeleted = 1, syncStatus = :pendingStatus WHERE id = :expenseId")
    suspend fun softDeleteExpense(expenseId: String, pendingStatus: SyncStatus = SyncStatus.PENDING_DELETE)

    // ── Via Appia sync hooks ─────────────────────────────────────────────

    @Query("UPDATE expenses SET syncStatus = :status WHERE id = :expenseId")
    suspend fun updateSyncStatus(expenseId: String, status: SyncStatus)
}