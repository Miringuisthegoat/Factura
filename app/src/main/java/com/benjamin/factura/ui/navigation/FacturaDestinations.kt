package com.benjamin.factura.ui.navigation


/*
 * Factura — ui/navigation/FacturaDestinations.kt
 *
 * Type-safe route definitions for every screen in the Navigation Compose
 * graph. Each destination's `route` is the pattern registered with NavHost
 * (including {argName} placeholders for required args and ?arg={arg} for
 * optional query-style args); each non-trivial destination also exposes a
 * `createRoute(...)` builder so call sites never hand-assemble route strings.
 *
 * Argument keys are centralized as companion constants so the NavHost's
 * argument-extraction code and every screen's createRoute() reference the
 * exact same string - a typo in one can't silently drift from the other.
 */
sealed class FacturaDestination(val route: String) {

    // -------------------------------------------------------------------
    // Full-screen, no bottom bar, backstack cleared on exit
    // -------------------------------------------------------------------

    data object Splash : FacturaDestination("splash")

    data object Onboarding : FacturaDestination("onboarding")

    // -------------------------------------------------------------------
    // Top-level tabs - persistent bottom navigation bar visible
    // -------------------------------------------------------------------

    data object Dashboard : FacturaDestination("dashboard")

    data object InvoiceList : FacturaDestination("invoice_list?$ARG_STATUS_FILTER={$ARG_STATUS_FILTER}") {
        /** [statusFilter] should be an [com.benjamin.factura.data.model.InvoiceStatus] enum name, or null for "all". */
        fun createRoute(statusFilter: String? = null): String =
            if (statusFilter != null) "invoice_list?$ARG_STATUS_FILTER=$statusFilter" else "invoice_list"
    }

    data object ClientList : FacturaDestination("client_list")

    data object ExpenseList : FacturaDestination("expense_list")

    data object Reports : FacturaDestination("reports")

    // -------------------------------------------------------------------
    // Detail / create-edit screens - pushed above the tabs, no bottom bar
    // -------------------------------------------------------------------

    data object InvoiceDetail : FacturaDestination("invoice_detail/{$ARG_INVOICE_ID}") {
        fun createRoute(invoiceId: String): String = "invoice_detail/$invoiceId"
    }

    data object CreateInvoice : FacturaDestination(
        "create_invoice?$ARG_INVOICE_ID={$ARG_INVOICE_ID}&$ARG_CLIENT_ID={$ARG_CLIENT_ID}"
    ) {
        /**
         * [invoiceId] non-null => edit an existing invoice; null => create new.
         * [clientId] optional pre-selection, e.g. from ClientDetailScreen's
         * "new invoice for this client" action. Ignored when [invoiceId] is set.
         */
        fun createRoute(invoiceId: String? = null, clientId: String? = null): String {
            val params = buildList {
                if (invoiceId != null) add("$ARG_INVOICE_ID=$invoiceId")
                if (clientId != null) add("$ARG_CLIENT_ID=$clientId")
            }
            return if (params.isEmpty()) "create_invoice" else "create_invoice?${params.joinToString("&")}"
        }
    }

    data object ClientDetail : FacturaDestination("client_detail/{$ARG_CLIENT_ID}") {
        fun createRoute(clientId: String): String = "client_detail/$clientId"
    }

    data object CreateEditClient : FacturaDestination("create_edit_client?$ARG_CLIENT_ID={$ARG_CLIENT_ID}") {
        /** [clientId] non-null => edit; null => create new. */
        fun createRoute(clientId: String? = null): String =
            if (clientId != null) "create_edit_client?$ARG_CLIENT_ID=$clientId" else "create_edit_client"
    }

    data object CreateExpense : FacturaDestination("create_expense?$ARG_EXPENSE_ID={$ARG_EXPENSE_ID}") {
        /** [expenseId] non-null => edit; null => create new (including OCR-scan entry point). */
        fun createRoute(expenseId: String? = null): String =
            if (expenseId != null) "create_expense?$ARG_EXPENSE_ID=$expenseId" else "create_expense"
    }

    data object PaymentRecord : FacturaDestination("payment_record/{$ARG_INVOICE_ID}") {
        fun createRoute(invoiceId: String): String = "payment_record/$invoiceId"
    }

    data object Settings : FacturaDestination("settings")

    data object Notifications : FacturaDestination("notifications")

    companion object {
        const val ARG_INVOICE_ID = "invoiceId"
        const val ARG_CLIENT_ID = "clientId"
        const val ARG_EXPENSE_ID = "expenseId"
        const val ARG_STATUS_FILTER = "statusFilter"

        /** The five destinations that get the persistent bottom navigation bar. */
        val bottomNavDestinations: List<FacturaDestination> =
            listOf(Dashboard, InvoiceList, ClientList, ExpenseList, Reports)
    }
}