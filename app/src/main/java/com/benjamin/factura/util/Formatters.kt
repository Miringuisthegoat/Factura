package com.benjamin.factura.util


import com.benjamin.factura.BuildConfig
import com.benjamin.factura.data.model.DiscountType
import com.benjamin.factura.data.model.ExpenseCategory
import com.benjamin.factura.data.model.InvoiceStatus
import com.benjamin.factura.data.model.InvoiceType
import com.benjamin.factura.data.model.PaymentMethod
import com.benjamin.factura.data.model.SyncStatus
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/*
 * Factura — util/Formatters.kt
 *
 * Currency, date, quantity, and percentage formatting, plus the enum
 * display-label extensions Models.kt deliberately left out (see the note on
 * that file: presentation strings belong here, not on the domain enums, so
 * Swahili localization later only touches this one file).
 *
 * All date math runs through java.time, which is fully available at minSdk
 * 26 without core-library-desugaring (desugaring is enabled for other APIs,
 * per the Gradle setup, but isn't load-bearing for anything in this file).
 */

// =============================================================================
// Currency
// =============================================================================

/**
 * Formats an amount as "KES 12,500.00" — currency code (not symbol) followed
 * by a comma-grouped, always-two-decimal number. Using the ISO code rather
 * than a locale-derived symbol avoids "Ksh"/"KSh"/"KES" inconsistency across
 * device ICU data, and reads unambiguously for the multi-currency case too
 * (e.g. "USD 250.00" next to "KES 12,500.00" on the same report).
 */
fun formatCurrency(amount: Double, currencyCode: String = BuildConfig.DEFAULT_CURRENCY): String =
    "$currencyCode ${formatAmount(amount)}"

/** Same as [formatCurrency] but without the currency code — for table cells
 *  (invoice line items, payment lists) where the code is already shown once
 *  at the section/summary level and repeating it per row would be noise. */
fun formatAmount(amount: Double): String {
    val symbols = DecimalFormatSymbols(Locale.US).apply {
        groupingSeparator = ','
        decimalSeparator = '.'
    }
    return DecimalFormat("#,##0.00", symbols).format(amount)
}

/** Converts a foreign-currency amount to the business's base currency using a
 *  manual exchange rate (Invoice.exchangeRateToBase), per the multi-currency
 *  design — there is no live FX rate lookup anywhere in this app. */
fun convertToBaseCurrency(amount: Double, exchangeRateToBase: Double): Double =
    amount * exchangeRateToBase

// =============================================================================
// Percentages & quantities
// =============================================================================

/** Formats a percentage value, trimming a trailing ".0" — 16.0 -> "16%", 16.5 -> "16.5%". */
fun formatPercent(value: Double): String {
    val symbols = DecimalFormatSymbols(Locale.US)
    return "${DecimalFormat("#,##0.##", symbols).format(value)}%"
}

/** Formats a line-item quantity, trimming a trailing ".0" — 3.0 -> "3", 2.5 -> "2.5". */
fun formatQuantity(value: Double): String {
    val symbols = DecimalFormatSymbols(Locale.US)
    return DecimalFormat("#,##0.##", symbols).format(value)
}

// =============================================================================
// Dates & times
// =============================================================================

private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)
private val DATE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy, h:mm a", Locale.ENGLISH)
private val DAY_MONTH_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, dd MMM", Locale.ENGLISH)
private val FILE_TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ENGLISH)

private fun millisToLocalDate(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

private fun millisToZonedDateTime(millis: Long): ZonedDateTime =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())

/** "04 Jul 2026" — used everywhere a date-only value (issue date, due date, expense date) is shown. */
fun formatDate(millis: Long): String = DATE_FORMATTER.format(millisToLocalDate(millis))

/** "04 Jul 2026, 3:45 PM" — used for timestamps that carry meaningful time-of-day (payment recorded at, synced at). */
fun formatDateTime(millis: Long): String = DATE_TIME_FORMATTER.format(millisToZonedDateTime(millis))

/** "Fri, 04 Jul" — compact form for chart axis labels and dense list rows. */
fun formatDayMonth(millis: Long): String = DAY_MONTH_FORMATTER.format(millisToLocalDate(millis))

/** "20260704_154500" — safe, sortable filename fragment for exported PDFs/Excel/CSV files. */
fun formatFileTimestamp(millis: Long = System.currentTimeMillis()): String =
    FILE_TIMESTAMP_FORMATTER.format(millisToZonedDateTime(millis))

/** Whole days between two instants, counting by calendar date (not 24h windows) —
 *  so "issued 11pm yesterday, now 1am today" correctly counts as 1 day, not 0. */
fun daysBetween(fromMillis: Long, toMillis: Long): Long =
    ChronoUnit.DAYS.between(millisToLocalDate(fromMillis), millisToLocalDate(toMillis))

/** How many whole days past [dueDateMillis] we are, floored at 0 for not-yet-due invoices. */
fun daysOverdue(dueDateMillis: Long, nowMillis: Long = System.currentTimeMillis()): Int =
    daysBetween(dueDateMillis, nowMillis).toInt().coerceAtLeast(0)

/**
 * Human-readable due-date status, used directly in InvoiceListScreen/
 * DashboardScreen row subtitles, and as the "days overdue" figure fed into
 * the Gemini smart-reminder prompt.
 */
fun dueDateLabel(dueDateMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val days = daysBetween(nowMillis, dueDateMillis).toInt()
    return when {
        days == 0 -> "Due today"
        days == 1 -> "Due tomorrow"
        days > 1 -> "Due in $days days"
        days == -1 -> "1 day overdue"
        else -> "${abs(days)} days overdue"
    }
}

// =============================================================================
// Enum display labels
// =============================================================================
// Deliberately extension properties, not members on the enums themselves
// (see Models.kt) — keeps every user-facing string in exactly one file so
// Swahili localization later is a single-file change.

val InvoiceStatus.label: String
    get() = when (this) {
        InvoiceStatus.DRAFT -> "Draft"
        InvoiceStatus.SENT -> "Sent"
        InvoiceStatus.VIEWED -> "Viewed"
        InvoiceStatus.PARTIAL -> "Partially Paid"
        InvoiceStatus.PAID -> "Paid"
        InvoiceStatus.OVERDUE -> "Overdue"
        InvoiceStatus.CANCELLED -> "Cancelled"
    }

val InvoiceType.label: String
    get() = when (this) {
        InvoiceType.INVOICE -> "Invoice"
        InvoiceType.ESTIMATE -> "Estimate"
    }

val PaymentMethod.label: String
    get() = when (this) {
        PaymentMethod.CASH -> "Cash"
        PaymentMethod.BANK_TRANSFER -> "Bank Transfer"
        PaymentMethod.MPESA -> "M-Pesa"
        PaymentMethod.CARD -> "Card"
        PaymentMethod.CHEQUE -> "Cheque"
    }

val ExpenseCategory.label: String
    get() = when (this) {
        ExpenseCategory.RENT -> "Rent"
        ExpenseCategory.UTILITIES -> "Utilities"
        ExpenseCategory.SALARIES_WAGES -> "Salaries & Wages"
        ExpenseCategory.OFFICE_SUPPLIES -> "Office Supplies"
        ExpenseCategory.TRAVEL_TRANSPORT -> "Travel & Transport"
        ExpenseCategory.MARKETING_ADVERTISING -> "Marketing & Advertising"
        ExpenseCategory.PROFESSIONAL_SERVICES -> "Professional Services"
        ExpenseCategory.EQUIPMENT -> "Equipment"
        ExpenseCategory.INVENTORY_STOCK -> "Inventory / Stock"
        ExpenseCategory.INSURANCE -> "Insurance"
        ExpenseCategory.TAXES_LICENSES -> "Taxes & Licenses"
        ExpenseCategory.BANK_FEES -> "Bank Fees"
        ExpenseCategory.OTHER -> "Other"
    }

val DiscountType.label: String
    get() = when (this) {
        DiscountType.PERCENTAGE -> "Percentage"
        DiscountType.FIXED_AMOUNT -> "Fixed Amount"
    }

/** Not shown as a headline status anywhere — used only in small sync-state
 *  indicators (e.g. a cloud-slash icon with tooltip) since Via Appia's sync
 *  bookkeeping is an implementation detail, not a user-facing concept. */
val SyncStatus.label: String
    get() = when (this) {
        SyncStatus.SYNCED -> "Synced"
        SyncStatus.PENDING_UPLOAD -> "Pending sync"
        SyncStatus.PENDING_DELETE -> "Pending deletion"
        SyncStatus.FAILED -> "Sync failed"
    }