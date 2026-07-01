package com.benjamin.factura.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.benjamin.factura.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/*
 * Factura — data/model/Models.kt
 *
 * Single-owner domain layer: enums, Room entities (with embedded Firestore
 * toMap()/fromMap() round-trip methods), Room TypeConverters, cross-model
 * relation helpers, and UI state wrappers.
 *
 * No UserRole / InvitationStatus enums exist anywhere in this file — those
 * belonged to a multi-tenant design that this build does not have.
 */

// =============================================================================
// Firestore map coercion helpers
// =============================================================================
// Firestore returns Long for whole numbers and Double for decimals depending
// on how the value was originally written, and everything comes back as
// Any?. These helpers centralize safe, non-throwing coercion so every
// fromMap() below stays short and consistent.

private fun Map<String, Any?>.getString(key: String, default: String = ""): String =
    (this[key] as? String) ?: default

private fun Map<String, Any?>.getStringOrNull(key: String): String? =
    this[key] as? String

private fun Map<String, Any?>.getDouble(key: String, default: Double = 0.0): Double =
    when (val v = this[key]) {
        is Double -> v
        is Long -> v.toDouble()
        is Int -> v.toDouble()
        is Float -> v.toDouble()
        is String -> v.toDoubleOrNull() ?: default
        else -> default
    }

private fun Map<String, Any?>.getLong(key: String, default: Long = 0L): Long =
    when (val v = this[key]) {
        is Long -> v
        is Int -> v.toLong()
        is Double -> v.toLong()
        is String -> v.toLongOrNull() ?: default
        else -> default
    }

private fun Map<String, Any?>.getInt(key: String, default: Int = 0): Int =
    when (val v = this[key]) {
        is Int -> v
        is Long -> v.toInt()
        is Double -> v.toInt()
        is String -> v.toIntOrNull() ?: default
        else -> default
    }

private fun Map<String, Any?>.getBoolean(key: String, default: Boolean = false): Boolean =
    (this[key] as? Boolean) ?: default

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.getMapList(key: String): List<Map<String, Any?>> =
    (this[key] as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()

private inline fun <reified E : Enum<E>> enumFromStringOrDefault(value: String?, default: E): E =
    value?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

// =============================================================================
// Enums (six total)
// =============================================================================

/** Lifecycle of an [Invoice]. CANCELLED is an addition beyond the spec's named
 *  flow (Draft → Sent → Viewed → Partial → Paid → Overdue) — needed to void a
 *  bad invoice without a hard delete. */
enum class InvoiceStatus {
    DRAFT, SENT, VIEWED, PARTIAL, PAID, OVERDUE, CANCELLED
}

/** An [Invoice] document is either a binding invoice or a non-binding estimate/quote. */
enum class InvoiceType {
    INVOICE, ESTIMATE
}

/** How a [Payment] was received. MPESA is first-class per the Kenyan-market focus. */
enum class PaymentMethod {
    CASH, BANK_TRANSFER, MPESA, CARD, CHEQUE
}

/** Expense categorization, tuned for small-business bookkeeping in Kenya. */
enum class ExpenseCategory {
    RENT,
    UTILITIES,
    SALARIES_WAGES,
    OFFICE_SUPPLIES,
    TRAVEL_TRANSPORT,
    MARKETING_ADVERTISING,
    PROFESSIONAL_SERVICES,
    EQUIPMENT,
    INVENTORY_STOCK,
    INSURANCE,
    TAXES_LICENSES,
    BANK_FEES,
    OTHER
}

/** Local-only bookkeeping for the "Via Appia" Room ↔ Firestore offline-sync engine.
 *  Never written to Firestore itself. */
enum class SyncStatus {
    SYNCED, PENDING_UPLOAD, PENDING_DELETE, FAILED
}

/** How a per-line-item discount on an [InvoiceItem] is expressed. This is the
 *  sixth enum: the spec calls for per-line discounts but doesn't say how the
 *  discount value should be interpreted, so this makes that explicit. */
enum class DiscountType {
    PERCENTAGE, FIXED_AMOUNT
}

// =============================================================================
// InvoiceItem — embedded line item (not a Room entity; stored as JSON inside Invoice)
// =============================================================================

@Serializable
data class InvoiceItem(
    val id: String = UUID.randomUUID().toString(),
    val description: String = "",
    val quantity: Double = 1.0,
    val unitPrice: Double = 0.0,
    val discountType: DiscountType = DiscountType.PERCENTAGE,
    val discountValue: Double = 0.0,
    val taxRatePercent: Double = 0.0
) {
    /** quantity × unit price, before any discount or tax. */
    fun lineSubtotal(): Double = quantity * unitPrice

    /** The discount amount for this line, resolved from [discountType]. */
    fun lineDiscount(): Double = when (discountType) {
        DiscountType.PERCENTAGE -> lineSubtotal() * (discountValue / 100.0)
        DiscountType.FIXED_AMOUNT -> discountValue
    }.coerceAtMost(lineSubtotal()).coerceAtLeast(0.0)

    /** Subtotal minus discount — the amount tax is calculated on. */
    fun lineTaxableAmount(): Double = (lineSubtotal() - lineDiscount()).coerceAtLeast(0.0)

    fun lineTax(): Double = lineTaxableAmount() * (taxRatePercent / 100.0)

    /** Final total for this line: taxable amount + tax. */
    fun lineTotal(): Double = lineTaxableAmount() + lineTax()

    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "description" to description,
        "quantity" to quantity,
        "unitPrice" to unitPrice,
        "discountType" to discountType.name,
        "discountValue" to discountValue,
        "taxRatePercent" to taxRatePercent
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): InvoiceItem = InvoiceItem(
            id = map.getString("id", UUID.randomUUID().toString()),
            description = map.getString("description"),
            quantity = map.getDouble("quantity", 1.0),
            unitPrice = map.getDouble("unitPrice"),
            discountType = enumFromStringOrDefault(map.getStringOrNull("discountType"), DiscountType.PERCENTAGE),
            discountValue = map.getDouble("discountValue"),
            taxRatePercent = map.getDouble("taxRatePercent")
        )
    }
}

// =============================================================================
// AppUser — plain model (not a Room entity: single-user, cached via DataStore)
// =============================================================================

data class AppUser(
    val uid: String = "",
    val email: String = "",
    val displayName: String = "",
    val phone: String = "",
    val photoUrl: String? = null,
    val businessId: String = "",
    val fcmToken: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    // Deliberately no `role` field — single-owner architecture has no roles.

    fun toMap(): Map<String, Any?> = mapOf(
        "uid" to uid,
        "email" to email,
        "displayName" to displayName,
        "phone" to phone,
        "photoUrl" to photoUrl,
        "businessId" to businessId,
        "fcmToken" to fcmToken,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): AppUser = AppUser(
            uid = map.getString("uid"),
            email = map.getString("email"),
            displayName = map.getString("displayName"),
            phone = map.getString("phone"),
            photoUrl = map.getStringOrNull("photoUrl"),
            businessId = map.getString("businessId"),
            fcmToken = map.getStringOrNull("fcmToken"),
            createdAt = map.getLong("createdAt"),
            updatedAt = map.getLong("updatedAt")
        )

        fun new(uid: String, email: String, displayName: String, businessId: String): AppUser =
            AppUser(uid = uid, email = email, displayName = displayName, businessId = businessId)
    }
}

// =============================================================================
// BusinessProfile — plain model (not a Room entity: single business, cached via DataStore)
// =============================================================================

data class BusinessProfile(
    val id: String = "",
    val ownerUid: String = "",
    val businessName: String = "",
    val logoUrl: String? = null,
    val addressLine1: String = "",
    val addressLine2: String = "",
    val city: String = "",
    val country: String = "Kenya",
    val phone: String = "",
    val email: String = "",
    val taxNumber: String = "",
    val defaultCurrency: String = BuildConfig.DEFAULT_CURRENCY,
    val defaultTaxRatePercent: Double = 16.0,
    val invoiceNumberPrefix: String = "INV-",
    val nextInvoiceSequence: Int = 1,
    val paymentTermsDays: Int = 14,
    val bankName: String = "",
    val bankAccountName: String = "",
    val bankAccountNumber: String = "",
    val bankBranch: String = "",
    val mpesaPaybillNumber: String = "",
    val mpesaTillNumber: String = "",
    val mpesaAccountName: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "ownerUid" to ownerUid,
        "businessName" to businessName,
        "logoUrl" to logoUrl,
        "addressLine1" to addressLine1,
        "addressLine2" to addressLine2,
        "city" to city,
        "country" to country,
        "phone" to phone,
        "email" to email,
        "taxNumber" to taxNumber,
        "defaultCurrency" to defaultCurrency,
        "defaultTaxRatePercent" to defaultTaxRatePercent,
        "invoiceNumberPrefix" to invoiceNumberPrefix,
        "nextInvoiceSequence" to nextInvoiceSequence,
        "paymentTermsDays" to paymentTermsDays,
        "bankName" to bankName,
        "bankAccountName" to bankAccountName,
        "bankAccountNumber" to bankAccountNumber,
        "bankBranch" to bankBranch,
        "mpesaPaybillNumber" to mpesaPaybillNumber,
        "mpesaTillNumber" to mpesaTillNumber,
        "mpesaAccountName" to mpesaAccountName,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt
    )

    companion object {
        fun fromMap(map: Map<String, Any?>, id: String): BusinessProfile = BusinessProfile(
            id = id,
            ownerUid = map.getString("ownerUid"),
            businessName = map.getString("businessName"),
            logoUrl = map.getStringOrNull("logoUrl"),
            addressLine1 = map.getString("addressLine1"),
            addressLine2 = map.getString("addressLine2"),
            city = map.getString("city"),
            country = map.getString("country", "Kenya"),
            phone = map.getString("phone"),
            email = map.getString("email"),
            taxNumber = map.getString("taxNumber"),
            defaultCurrency = map.getString("defaultCurrency", BuildConfig.DEFAULT_CURRENCY),
            defaultTaxRatePercent = map.getDouble("defaultTaxRatePercent", 16.0),
            invoiceNumberPrefix = map.getString("invoiceNumberPrefix", "INV-"),
            nextInvoiceSequence = map.getInt("nextInvoiceSequence", 1),
            paymentTermsDays = map.getInt("paymentTermsDays", 14),
            bankName = map.getString("bankName"),
            bankAccountName = map.getString("bankAccountName"),
            bankAccountNumber = map.getString("bankAccountNumber"),
            bankBranch = map.getString("bankBranch"),
            mpesaPaybillNumber = map.getString("mpesaPaybillNumber"),
            mpesaTillNumber = map.getString("mpesaTillNumber"),
            mpesaAccountName = map.getString("mpesaAccountName"),
            createdAt = map.getLong("createdAt"),
            updatedAt = map.getLong("updatedAt")
        )

        fun new(ownerUid: String, businessName: String): BusinessProfile =
            BusinessProfile(id = ownerUid, ownerUid = ownerUid, businessName = businessName)
    }
}

/** Formats this profile's next invoice number without mutating state. */
fun BusinessProfile.nextInvoiceNumber(): String =
    "$invoiceNumberPrefix${nextInvoiceSequence.toString().padStart(4, '0')}"

/** Returns a copy of this profile with the invoice sequence advanced by one. */
fun BusinessProfile.withIncrementedInvoiceSequence(): BusinessProfile =
    copy(nextInvoiceSequence = nextInvoiceSequence + 1, updatedAt = System.currentTimeMillis())

// =============================================================================
// Client — Room entity + Firestore document
// =============================================================================

@Entity(
    tableName = "clients",
    indices = [Index("businessId"), Index("isDeleted")]
)
@TypeConverters(Converters::class)
data class Client(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "businessId") val businessId: String = "",
    val name: String = "",
    val email: String = "",
    val phone: String = "",
    val addressLine1: String = "",
    val addressLine2: String = "",
    val city: String = "",
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val syncStatus: SyncStatus = SyncStatus.PENDING_UPLOAD,
    val isDeleted: Boolean = false
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "businessId" to businessId,
        "name" to name,
        "email" to email,
        "phone" to phone,
        "addressLine1" to addressLine1,
        "addressLine2" to addressLine2,
        "city" to city,
        "notes" to notes,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt,
        "isDeleted" to isDeleted
        // syncStatus is intentionally omitted — local-only Via Appia bookkeeping.
    )

    companion object {
        fun fromMap(map: Map<String, Any?>, id: String): Client = Client(
            id = id,
            businessId = map.getString("businessId"),
            name = map.getString("name"),
            email = map.getString("email"),
            phone = map.getString("phone"),
            addressLine1 = map.getString("addressLine1"),
            addressLine2 = map.getString("addressLine2"),
            city = map.getString("city"),
            notes = map.getString("notes"),
            createdAt = map.getLong("createdAt"),
            updatedAt = map.getLong("updatedAt"),
            syncStatus = SyncStatus.SYNCED,
            isDeleted = map.getBoolean("isDeleted")
        )

        fun new(
            businessId: String,
            name: String,
            email: String = "",
            phone: String = "",
            addressLine1: String = "",
            addressLine2: String = "",
            city: String = "",
            notes: String = ""
        ): Client = Client(
            businessId = businessId,
            name = name,
            email = email,
            phone = phone,
            addressLine1 = addressLine1,
            addressLine2 = addressLine2,
            city = city,
            notes = notes
        )
    }
}

// =============================================================================
// Invoice — Room entity + Firestore document (with embedded InvoiceItem list)
// =============================================================================

@Entity(
    tableName = "invoices",
    indices = [Index("businessId"), Index("clientId"), Index("status"), Index("isDeleted")]
)
@TypeConverters(Converters::class)
data class Invoice(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "businessId") val businessId: String = "",
    @ColumnInfo(name = "clientId") val clientId: String = "",
    val invoiceNumber: String = "",
    val type: InvoiceType = InvoiceType.INVOICE,
    val status: InvoiceStatus = InvoiceStatus.DRAFT,
    val issueDateMillis: Long = System.currentTimeMillis(),
    val dueDateMillis: Long = System.currentTimeMillis(),
    val items: List<InvoiceItem> = emptyList(),
    val currencyCode: String = BuildConfig.DEFAULT_CURRENCY,
    val exchangeRateToBase: Double = 1.0,
    val subtotal: Double = 0.0,
    val discountTotal: Double = 0.0,
    val taxTotal: Double = 0.0,
    val total: Double = 0.0,
    val amountPaid: Double = 0.0,
    val notes: String = "",
    val paymentInstructions: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val syncStatus: SyncStatus = SyncStatus.PENDING_UPLOAD,
    val isDeleted: Boolean = false
) {
    /** Outstanding amount still owed on this invoice. Never negative. */
    fun balanceDue(): Double = (total - amountPaid).coerceAtLeast(0.0)

    /** Whether this invoice is strictly past its due date and not fully paid/closed. */
    fun isOverdue(referenceMillis: Long = System.currentTimeMillis()): Boolean =
        status !in setOf(InvoiceStatus.DRAFT, InvoiceStatus.PAID, InvoiceStatus.CANCELLED) &&
                referenceMillis > dueDateMillis &&
                balanceDue() > 0.0

    /**
     * Returns a copy of this invoice with [subtotal], [discountTotal], [taxTotal]
     * and [total] recomputed from [items]. Call this after any line-item edit
     * rather than trusting stale totals.
     */
    fun recalculateTotals(): Invoice = copy(
        subtotal = items.sumOf { it.lineSubtotal() },
        discountTotal = items.sumOf { it.lineDiscount() },
        taxTotal = items.sumOf { it.lineTax() },
        total = items.sumOf { it.lineTotal() },
        updatedAt = System.currentTimeMillis()
    )

    /**
     * Derives what [status] should be given the current payment/due-date state,
     * without mutating anything. DRAFT and CANCELLED are terminal/manual states
     * and are always passed through unchanged.
     */
    fun computedStatus(nowMillis: Long = System.currentTimeMillis()): InvoiceStatus {
        if (status == InvoiceStatus.DRAFT || status == InvoiceStatus.CANCELLED) return status
        val overdue = nowMillis > dueDateMillis
        return when {
            total <= 0.0 -> status
            amountPaid >= total -> InvoiceStatus.PAID
            amountPaid > 0.0 -> if (overdue) InvoiceStatus.OVERDUE else InvoiceStatus.PARTIAL
            else -> if (overdue) InvoiceStatus.OVERDUE else status
        }
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "businessId" to businessId,
        "clientId" to clientId,
        "invoiceNumber" to invoiceNumber,
        "type" to type.name,
        "status" to status.name,
        "issueDateMillis" to issueDateMillis,
        "dueDateMillis" to dueDateMillis,
        "items" to items.map { it.toMap() },
        "currencyCode" to currencyCode,
        "exchangeRateToBase" to exchangeRateToBase,
        "subtotal" to subtotal,
        "discountTotal" to discountTotal,
        "taxTotal" to taxTotal,
        "total" to total,
        "amountPaid" to amountPaid,
        "notes" to notes,
        "paymentInstructions" to paymentInstructions,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt,
        "isDeleted" to isDeleted
    )

    companion object {
        fun fromMap(map: Map<String, Any?>, id: String): Invoice = Invoice(
            id = id,
            businessId = map.getString("businessId"),
            clientId = map.getString("clientId"),
            invoiceNumber = map.getString("invoiceNumber"),
            type = enumFromStringOrDefault(map.getStringOrNull("type"), InvoiceType.INVOICE),
            status = enumFromStringOrDefault(map.getStringOrNull("status"), InvoiceStatus.DRAFT),
            issueDateMillis = map.getLong("issueDateMillis"),
            dueDateMillis = map.getLong("dueDateMillis"),
            items = map.getMapList("items").map { InvoiceItem.fromMap(it) },
            currencyCode = map.getString("currencyCode", BuildConfig.DEFAULT_CURRENCY),
            exchangeRateToBase = map.getDouble("exchangeRateToBase", 1.0),
            subtotal = map.getDouble("subtotal"),
            discountTotal = map.getDouble("discountTotal"),
            taxTotal = map.getDouble("taxTotal"),
            total = map.getDouble("total"),
            amountPaid = map.getDouble("amountPaid"),
            notes = map.getString("notes"),
            paymentInstructions = map.getString("paymentInstructions"),
            createdAt = map.getLong("createdAt"),
            updatedAt = map.getLong("updatedAt"),
            syncStatus = SyncStatus.SYNCED,
            isDeleted = map.getBoolean("isDeleted")
        )

        fun new(
            businessId: String,
            clientId: String,
            invoiceNumber: String,
            type: InvoiceType = InvoiceType.INVOICE,
            dueDateMillis: Long,
            items: List<InvoiceItem> = emptyList(),
            currencyCode: String = BuildConfig.DEFAULT_CURRENCY,
            notes: String = "",
            paymentInstructions: String = ""
        ): Invoice = Invoice(
            businessId = businessId,
            clientId = clientId,
            invoiceNumber = invoiceNumber,
            type = type,
            dueDateMillis = dueDateMillis,
            items = items,
            currencyCode = currencyCode,
            notes = notes,
            paymentInstructions = paymentInstructions
        ).recalculateTotals()
    }
}

// =============================================================================
// Payment — Room entity + Firestore document
// =============================================================================

@Entity(
    tableName = "payments",
    indices = [Index("businessId"), Index("invoiceId"), Index("clientId"), Index("isDeleted")]
)
@TypeConverters(Converters::class)
data class Payment(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "businessId") val businessId: String = "",
    @ColumnInfo(name = "invoiceId") val invoiceId: String = "",
    @ColumnInfo(name = "clientId") val clientId: String = "",
    val amount: Double = 0.0,
    val method: PaymentMethod = PaymentMethod.CASH,
    /** M-Pesa confirmation code, cheque number, bank reference, etc. */
    val referenceNumber: String = "",
    val paidAtMillis: Long = System.currentTimeMillis(),
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val syncStatus: SyncStatus = SyncStatus.PENDING_UPLOAD,
    val isDeleted: Boolean = false
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "businessId" to businessId,
        "invoiceId" to invoiceId,
        "clientId" to clientId,
        "amount" to amount,
        "method" to method.name,
        "referenceNumber" to referenceNumber,
        "paidAtMillis" to paidAtMillis,
        "notes" to notes,
        "createdAt" to createdAt,
        "isDeleted" to isDeleted
    )

    companion object {
        fun fromMap(map: Map<String, Any?>, id: String): Payment = Payment(
            id = id,
            businessId = map.getString("businessId"),
            invoiceId = map.getString("invoiceId"),
            clientId = map.getString("clientId"),
            amount = map.getDouble("amount"),
            method = enumFromStringOrDefault(map.getStringOrNull("method"), PaymentMethod.CASH),
            referenceNumber = map.getString("referenceNumber"),
            paidAtMillis = map.getLong("paidAtMillis"),
            notes = map.getString("notes"),
            createdAt = map.getLong("createdAt"),
            syncStatus = SyncStatus.SYNCED,
            isDeleted = map.getBoolean("isDeleted")
        )

        fun new(
            businessId: String,
            invoiceId: String,
            clientId: String,
            amount: Double,
            method: PaymentMethod,
            referenceNumber: String = "",
            paidAtMillis: Long = System.currentTimeMillis(),
            notes: String = ""
        ): Payment = Payment(
            businessId = businessId,
            invoiceId = invoiceId,
            clientId = clientId,
            amount = amount,
            method = method,
            referenceNumber = referenceNumber,
            paidAtMillis = paidAtMillis,
            notes = notes
        )
    }
}

// =============================================================================
// Expense — Room entity + Firestore document (with OCR receipt text field)
// =============================================================================

@Entity(
    tableName = "expenses",
    indices = [Index("businessId"), Index("category"), Index("isDeleted")]
)
@TypeConverters(Converters::class)
data class Expense(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "businessId") val businessId: String = "",
    val category: ExpenseCategory = ExpenseCategory.OTHER,
    val vendor: String = "",
    val amount: Double = 0.0,
    val expenseDateMillis: Long = System.currentTimeMillis(),
    /** Local file URI or Firebase Storage download URL for the receipt photo. */
    val receiptImageUrl: String? = null,
    /** Raw ML Kit OCR output for this receipt, kept for audit/re-parsing. */
    val ocrRawText: String? = null,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val syncStatus: SyncStatus = SyncStatus.PENDING_UPLOAD,
    val isDeleted: Boolean = false
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "businessId" to businessId,
        "category" to category.name,
        "vendor" to vendor,
        "amount" to amount,
        "expenseDateMillis" to expenseDateMillis,
        "receiptImageUrl" to receiptImageUrl,
        "ocrRawText" to ocrRawText,
        "notes" to notes,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt,
        "isDeleted" to isDeleted
    )

    companion object {
        fun fromMap(map: Map<String, Any?>, id: String): Expense = Expense(
            id = id,
            businessId = map.getString("businessId"),
            category = enumFromStringOrDefault(map.getStringOrNull("category"), ExpenseCategory.OTHER),
            vendor = map.getString("vendor"),
            amount = map.getDouble("amount"),
            expenseDateMillis = map.getLong("expenseDateMillis"),
            receiptImageUrl = map.getStringOrNull("receiptImageUrl"),
            ocrRawText = map.getStringOrNull("ocrRawText"),
            notes = map.getString("notes"),
            createdAt = map.getLong("createdAt"),
            updatedAt = map.getLong("updatedAt"),
            syncStatus = SyncStatus.SYNCED,
            isDeleted = map.getBoolean("isDeleted")
        )

        fun new(
            businessId: String,
            category: ExpenseCategory,
            vendor: String,
            amount: Double,
            expenseDateMillis: Long = System.currentTimeMillis(),
            receiptImageUrl: String? = null,
            ocrRawText: String? = null,
            notes: String = ""
        ): Expense = Expense(
            businessId = businessId,
            category = category,
            vendor = vendor,
            amount = amount,
            expenseDateMillis = expenseDateMillis,
            receiptImageUrl = receiptImageUrl,
            ocrRawText = ocrRawText,
            notes = notes
        )
    }
}

// =============================================================================
// Room TypeConverters
// =============================================================================
// Registered on AppDatabase via @TypeConverters(Converters::class) (next phase)
// and also declared directly on each entity above so this file is self-contained
// even before AppDatabase.kt exists.

private val invoiceItemJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

class Converters {

    @TypeConverter
    fun fromInvoiceItemList(items: List<InvoiceItem>): String =
        invoiceItemJson.encodeToString(items)

    @TypeConverter
    fun toInvoiceItemList(data: String?): List<InvoiceItem> =
        if (data.isNullOrBlank()) emptyList() else invoiceItemJson.decodeFromString(data)

    @TypeConverter
    fun fromInvoiceType(value: InvoiceType): String = value.name

    @TypeConverter
    fun toInvoiceType(value: String): InvoiceType =
        enumFromStringOrDefault(value, InvoiceType.INVOICE)

    @TypeConverter
    fun fromInvoiceStatus(value: InvoiceStatus): String = value.name

    @TypeConverter
    fun toInvoiceStatus(value: String): InvoiceStatus =
        enumFromStringOrDefault(value, InvoiceStatus.DRAFT)

    @TypeConverter
    fun fromPaymentMethod(value: PaymentMethod): String = value.name

    @TypeConverter
    fun toPaymentMethod(value: String): PaymentMethod =
        enumFromStringOrDefault(value, PaymentMethod.CASH)

    @TypeConverter
    fun fromExpenseCategory(value: ExpenseCategory): String = value.name

    @TypeConverter
    fun toExpenseCategory(value: String): ExpenseCategory =
        enumFromStringOrDefault(value, ExpenseCategory.OTHER)

    @TypeConverter
    fun fromSyncStatus(value: SyncStatus): String = value.name

    @TypeConverter
    fun toSyncStatus(value: String): SyncStatus =
        enumFromStringOrDefault(value, SyncStatus.PENDING_UPLOAD)

    @TypeConverter
    fun fromDiscountType(value: DiscountType): String = value.name

    @TypeConverter
    fun toDiscountType(value: String): DiscountType =
        enumFromStringOrDefault(value, DiscountType.PERCENTAGE)
}

// =============================================================================
// Relation helpers
// =============================================================================

/** An invoice paired with its client, for list/detail screens that need both. */
data class InvoiceWithClient(
    val invoice: Invoice,
    val client: Client?
)

/** A client paired with computed invoice statistics, for ClientDetailScreen/ClientListScreen. */
data class ClientWithStats(
    val client: Client,
    val totalInvoiced: Double = 0.0,
    val totalPaid: Double = 0.0,
    val outstandingBalance: Double = 0.0,
    val invoiceCount: Int = 0
)

/** Sum of all non-deleted payments recorded against this invoice. */
fun Invoice.paymentsTotal(payments: List<Payment>): Double =
    payments.filter { it.invoiceId == id && !it.isDeleted }.sumOf { it.amount }

/** Total outstanding balance across a list of invoices, excluding cancelled ones. */
fun List<Invoice>.totalOutstanding(): Double =
    filter { it.status != InvoiceStatus.CANCELLED && !it.isDeleted }.sumOf { it.balanceDue() }

/** Invoices that are currently overdue as of [nowMillis]. */
fun List<Invoice>.overdueOnly(nowMillis: Long = System.currentTimeMillis()): List<Invoice> =
    filter { !it.isDeleted && it.isOverdue(nowMillis) }

/** Builds per-client statistics from a flat list of invoices for one client. */
fun Client.withStats(invoices: List<Invoice>): ClientWithStats {
    val forClient = invoices.filter { it.clientId == id && !it.isDeleted }
    return ClientWithStats(
        client = this,
        totalInvoiced = forClient.sumOf { it.total },
        totalPaid = forClient.sumOf { it.amountPaid },
        outstandingBalance = forClient.totalOutstanding(),
        invoiceCount = forClient.size
    )
}

// =============================================================================
// UI state wrappers
// =============================================================================

/** Generic screen-level loading/success/error wrapper used across ViewModels. */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data object Empty : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String, val cause: Throwable? = null) : UiState<Nothing>
}

/** A single point on a revenue trend chart. */
data class RevenuePoint(val label: String, val amount: Double)

/** Aggregate figures for DashboardScreen. Computed by the repository, never persisted. */
data class DashboardSummary(
    val totalRevenue: Double = 0.0,
    val monthlyRevenue: Double = 0.0,
    val outstandingTotal: Double = 0.0,
    val overdueTotal: Double = 0.0,
    val overdueCount: Int = 0,
    val recentInvoices: List<Invoice> = emptyList(),
    val revenueTrend: List<RevenuePoint> = emptyList()
)

/** A category's total, used in expense breakdowns on ReportsScreen. */
data class CategoryTotal(val category: ExpenseCategory, val amount: Double)

/**
 * Format-agnostic report payload. A single [ReportData] instance feeds three
 * independent generators — PdfReportGenerator, ExcelReportGenerator, and
 * CsvExporter — so none of them need to know about the others' output format.
 */
data class ReportData(
    val title: String,
    val periodLabel: String,
    val generatedAtMillis: Long = System.currentTimeMillis(),
    val businessProfile: BusinessProfile,
    val totalRevenue: Double,
    val totalExpenses: Double,
    val invoiceCount: Int,
    val paidInvoiceCount: Int,
    val overdueInvoiceCount: Int,
    val revenueByPeriod: List<RevenuePoint> = emptyList(),
    val expensesByCategory: List<CategoryTotal> = emptyList(),
    val invoices: List<Invoice> = emptyList(),
    val expenses: List<Expense> = emptyList()
) {
    val netProfit: Double get() = totalRevenue - totalExpenses
}