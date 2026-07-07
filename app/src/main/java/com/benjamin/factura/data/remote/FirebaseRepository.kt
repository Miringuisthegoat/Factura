package com.benjamin.factura.data.remote


import android.net.Uri
import com.benjamin.factura.data.model.AppUser
import com.benjamin.factura.data.model.BusinessProfile
import com.benjamin.factura.data.model.Client
import com.benjamin.factura.data.model.Expense
import com.benjamin.factura.data.model.Invoice
import com.benjamin.factura.data.model.Payment
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/*
 * Factura — data/remote/FirebaseRepository.kt
 *
 * Thin remote data source wrapping Firebase Auth, Firestore, and Storage. This
 * class knows nothing about Room, DataStore, or the "Via Appia" offline-sync
 * engine - it only reads and writes Firestore documents/Storage files and turns
 * them into/from the domain models' own toMap()/fromMap() methods. The sync
 * engine (a later phase) is what decides *when* to call these methods and how
 * to reconcile the result with the local Room database.
 *
 * Firestore layout (single-owner, no team/role fields anywhere):
 *   /businesses/{businessId}   - BusinessProfile.id == the owning user's uid
 *   /users/{uid}               - AppUser
 *   /invoices/{invoiceId}      - Invoice (embeds InvoiceItem list as a field)
 *   /clients/{clientId}        - Client
 *   /payments/{paymentId}      - Payment
 *   /expenses/{expenseId}      - Expense
 *     (Expenses aren't in the spec's Firebase Structure diagram, but Expense
 *      already defines toMap()/fromMap() and expense sync is a core feature,
 *      so it gets the same collection treatment as the rest here. Flag if
 *      expenses were meant to stay device-local only and this should come out.)
 *
 * Deletes are soft (isDeleted = true, not a hard document delete) so that other
 * synced devices can reconcile a removal instead of silently losing a document
 * they haven't pulled the latest snapshot for yet.
 *
 * FirebaseAuth / FirebaseFirestore / FirebaseStorage are constructor-injected
 * rather than looked up via .getInstance() here, so the upcoming Hilt module
 * phase just needs to @Provide the three stock Firebase instances - nothing
 * about this class needs to change once that module exists.
 */
@Singleton
class FirebaseRepository @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val storage: FirebaseStorage
) {

    private object Collections {
        const val USERS = "users"
        const val BUSINESSES = "businesses"
        const val CLIENTS = "clients"
        const val INVOICES = "invoices"
        const val PAYMENTS = "payments"
        const val EXPENSES = "expenses"
    }

    // =========================================================================
    // Auth
    // =========================================================================

    val currentUserUid: String?
        get() = auth.currentUser?.uid

    /** Emits the current uid on every auth state change, or null when signed out. */
    val authStateUid: Flow<String?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            trySend(firebaseAuth.currentUser?.uid)
        }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    suspend fun signUpWithEmail(email: String, password: String): Result<String> = runCatching {
        val result = auth.createUserWithEmailAndPassword(email, password).await()
        result.user?.uid ?: error("Sign-up succeeded but FirebaseAuth returned no user")
    }

    suspend fun signInWithEmail(email: String, password: String): Result<String> = runCatching {
        val result = auth.signInWithEmailAndPassword(email, password).await()
        result.user?.uid ?: error("Sign-in succeeded but FirebaseAuth returned no user")
    }

    /** [googleIdToken] comes from the Credential Manager / One Tap Google Sign-In flow. */
    suspend fun signInWithGoogleIdToken(googleIdToken: String): Result<String> = runCatching {
        val credential = GoogleAuthProvider.getCredential(googleIdToken, null)
        val result = auth.signInWithCredential(credential).await()
        result.user?.uid ?: error("Google sign-in succeeded but FirebaseAuth returned no user")
    }

    suspend fun sendPasswordResetEmail(email: String): Result<Unit> = runCatching {
        auth.sendPasswordResetEmail(email).await()
    }

    fun signOut() {
        auth.signOut()
    }

    // =========================================================================
    // AppUser  (/users/{uid})
    // =========================================================================

    suspend fun createUserDocument(user: AppUser): Result<Unit> = runCatching {
        firestore.collection(Collections.USERS).document(user.uid)
            .set(user.toMap())
            .await()
    }

    suspend fun getUser(uid: String): Result<AppUser?> = runCatching {
        val snapshot = firestore.collection(Collections.USERS).document(uid).get().await()
        snapshot.data?.takeIf { snapshot.exists() }?.let { AppUser.fromMap(it) }
    }

    suspend fun updateUser(user: AppUser): Result<Unit> = runCatching {
        firestore.collection(Collections.USERS).document(user.uid)
            .set(user.toMap())
            .await()
    }

    suspend fun updateFcmToken(uid: String, fcmToken: String): Result<Unit> = runCatching {
        firestore.collection(Collections.USERS).document(uid)
            .update("fcmToken", fcmToken, "updatedAt", System.currentTimeMillis())
            .await()
    }

    // =========================================================================
    // BusinessProfile  (/businesses/{businessId})
    // =========================================================================
    // Single-owner: BusinessProfile.id == the owning AppUser.uid (see
    // BusinessProfile.new()), so businessId and uid are interchangeable as the
    // document id throughout this class.

    suspend fun getBusinessProfile(businessId: String): Result<BusinessProfile?> = runCatching {
        val snapshot = firestore.collection(Collections.BUSINESSES).document(businessId).get().await()
        snapshot.data?.takeIf { snapshot.exists() }?.let { BusinessProfile.fromMap(it, snapshot.id) }
    }

    fun observeBusinessProfile(businessId: String): Flow<BusinessProfile?> = callbackFlow {
        val registration = firestore.collection(Collections.BUSINESSES).document(businessId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val profile = snapshot?.takeIf { it.exists() }?.data
                    ?.let { BusinessProfile.fromMap(it, snapshot.id) }
                trySend(profile)
            }
        awaitClose { registration.remove() }
    }

    suspend fun saveBusinessProfile(profile: BusinessProfile): Result<Unit> = runCatching {
        firestore.collection(Collections.BUSINESSES).document(profile.id)
            .set(profile.toMap())
            .await()
    }

    // =========================================================================
    // Clients  (/clients/{clientId})
    // =========================================================================

    fun observeClients(businessId: String): Flow<List<Client>> = callbackFlow {
        val registration = firestore.collection(Collections.CLIENTS)
            .whereEqualTo("businessId", businessId)
            .whereEqualTo("isDeleted", false)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val clients = snapshot?.documents.orEmpty().mapNotNull { doc ->
                    doc.data?.let { Client.fromMap(it, doc.id) }
                }
                trySend(clients)
            }
        awaitClose { registration.remove() }
    }

    suspend fun getClient(clientId: String): Result<Client?> = runCatching {
        val snapshot = firestore.collection(Collections.CLIENTS).document(clientId).get().await()
        snapshot.data?.takeIf { snapshot.exists() }?.let { Client.fromMap(it, snapshot.id) }
    }

    suspend fun upsertClient(client: Client): Result<Unit> = runCatching {
        firestore.collection(Collections.CLIENTS).document(client.id)
            .set(client.toMap())
            .await()
    }

    /** Soft delete - flips isDeleted rather than removing the document. */
    suspend fun deleteClient(clientId: String): Result<Unit> = runCatching {
        firestore.collection(Collections.CLIENTS).document(clientId)
            .update("isDeleted", true, "updatedAt", System.currentTimeMillis())
            .await()
    }

    // =========================================================================
    // Invoices  (/invoices/{invoiceId})
    // =========================================================================

    fun observeInvoices(businessId: String): Flow<List<Invoice>> = callbackFlow {
        val registration = firestore.collection(Collections.INVOICES)
            .whereEqualTo("businessId", businessId)
            .whereEqualTo("isDeleted", false)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val invoices = snapshot?.documents.orEmpty().mapNotNull { doc ->
                    doc.data?.let { Invoice.fromMap(it, doc.id) }
                }
                trySend(invoices)
            }
        awaitClose { registration.remove() }
    }

    fun observeInvoicesForClient(clientId: String): Flow<List<Invoice>> = callbackFlow {
        val registration = firestore.collection(Collections.INVOICES)
            .whereEqualTo("clientId", clientId)
            .whereEqualTo("isDeleted", false)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val invoices = snapshot?.documents.orEmpty().mapNotNull { doc ->
                    doc.data?.let { Invoice.fromMap(it, doc.id) }
                }
                trySend(invoices)
            }
        awaitClose { registration.remove() }
    }

    suspend fun getInvoice(invoiceId: String): Result<Invoice?> = runCatching {
        val snapshot = firestore.collection(Collections.INVOICES).document(invoiceId).get().await()
        snapshot.data?.takeIf { snapshot.exists() }?.let { Invoice.fromMap(it, snapshot.id) }
    }

    suspend fun upsertInvoice(invoice: Invoice): Result<Unit> = runCatching {
        firestore.collection(Collections.INVOICES).document(invoice.id)
            .set(invoice.toMap())
            .await()
    }

    suspend fun deleteInvoice(invoiceId: String): Result<Unit> = runCatching {
        firestore.collection(Collections.INVOICES).document(invoiceId)
            .update("isDeleted", true, "updatedAt", System.currentTimeMillis())
            .await()
    }

    // =========================================================================
    // Payments  (/payments/{paymentId})
    // =========================================================================

    fun observePaymentsForInvoice(invoiceId: String): Flow<List<Payment>> = callbackFlow {
        val registration = firestore.collection(Collections.PAYMENTS)
            .whereEqualTo("invoiceId", invoiceId)
            .whereEqualTo("isDeleted", false)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val payments = snapshot?.documents.orEmpty().mapNotNull { doc ->
                    doc.data?.let { Payment.fromMap(it, doc.id) }
                }
                trySend(payments)
            }
        awaitClose { registration.remove() }
    }

    fun observePayments(businessId: String): Flow<List<Payment>> = callbackFlow {
        val registration = firestore.collection(Collections.PAYMENTS)
            .whereEqualTo("businessId", businessId)
            .whereEqualTo("isDeleted", false)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val payments = snapshot?.documents.orEmpty().mapNotNull { doc ->
                    doc.data?.let { Payment.fromMap(it, doc.id) }
                }
                trySend(payments)
            }
        awaitClose { registration.remove() }
    }

    suspend fun upsertPayment(payment: Payment): Result<Unit> = runCatching {
        firestore.collection(Collections.PAYMENTS).document(payment.id)
            .set(payment.toMap())
            .await()
    }

    suspend fun deletePayment(paymentId: String): Result<Unit> = runCatching {
        firestore.collection(Collections.PAYMENTS).document(paymentId)
            .update("isDeleted", true)
            .await()
    }

    // =========================================================================
    // Expenses  (/expenses/{expenseId})
    // =========================================================================

    fun observeExpenses(businessId: String): Flow<List<Expense>> = callbackFlow {
        val registration = firestore.collection(Collections.EXPENSES)
            .whereEqualTo("businessId", businessId)
            .whereEqualTo("isDeleted", false)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val expenses = snapshot?.documents.orEmpty().mapNotNull { doc ->
                    doc.data?.let { Expense.fromMap(it, doc.id) }
                }
                trySend(expenses)
            }
        awaitClose { registration.remove() }
    }

    suspend fun upsertExpense(expense: Expense): Result<Unit> = runCatching {
        firestore.collection(Collections.EXPENSES).document(expense.id)
            .set(expense.toMap())
            .await()
    }

    suspend fun deleteExpense(expenseId: String): Result<Unit> = runCatching {
        firestore.collection(Collections.EXPENSES).document(expenseId)
            .update("isDeleted", true)
            .await()
    }

    // =========================================================================
    // Storage (business logo, expense receipt photos)
    // =========================================================================

    suspend fun uploadBusinessLogo(businessId: String, localImageUri: Uri): Result<String> = runCatching {
        val ref = storage.reference.child("businesses/$businessId/logo.jpg")
        ref.putFile(localImageUri).await()
        ref.downloadUrl.await().toString()
    }

    suspend fun uploadReceiptImage(
        businessId: String,
        expenseId: String,
        localImageUri: Uri
    ): Result<String> = runCatching {
        val ref = storage.reference.child("businesses/$businessId/receipts/$expenseId.jpg")
        ref.putFile(localImageUri).await()
        ref.downloadUrl.await().toString()
    }

    suspend fun deleteStorageFile(downloadUrl: String): Result<Unit> = runCatching {
        storage.getReferenceFromUrl(downloadUrl).delete().await()
    }
}