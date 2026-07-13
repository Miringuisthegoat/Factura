package com.benjamin.factura.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.benjamin.factura.data.model.Client
import com.benjamin.factura.data.model.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface ClientDao {
    @Upsert
    suspend fun upsert(client: Client)

    @Upsert
    suspend fun upsertAll(clients: List<Client>)

    @Query("SELECT * FROM clients WHERE isDeleted = 0 ORDER BY name ASC")
    fun getAllClients(): Flow<List<Client>>

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
