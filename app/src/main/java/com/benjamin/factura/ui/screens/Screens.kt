package com.benjamin.factura.ui.screens

import androidx.compose.runtime.Composable

@Composable
fun SplashScreen(
    onNavigateToOnboarding: () -> Unit,
    onNavigateToDashboard: () -> Unit
) {}

@Composable
fun InvoiceDetailScreen(
    invoiceId: String,
    onNavigateBack: () -> Unit,
    onNavigateToEdit: () -> Unit,
    onNavigateToRecordPayment: () -> Unit
) {}

@Composable
fun CreateInvoiceScreen(
    invoiceId: String?,
    preselectedClientId: String?,
    onNavigateBack: () -> Unit
) {}

@Composable
fun ClientDetailScreen(
    clientId: String,
    onNavigateBack: () -> Unit,
    onNavigateToEditClient: () -> Unit,
    onNavigateToInvoiceDetail: (String) -> Unit,
    onNavigateToCreateInvoiceForClient: () -> Unit
) {}

@Composable
fun CreateEditClientScreen(
    clientId: String?,
    onNavigateBack: () -> Unit
) {}

@Composable
fun ExpenseListScreen(
    onNavigateToCreateExpense: () -> Unit,
    onNavigateToEditExpense: (String) -> Unit
) {}

@Composable
fun CreateExpenseScreen(
    expenseId: String?,
    onNavigateBack: () -> Unit
) {}

@Composable
fun PaymentRecordScreen(
    invoiceId: String,
    onNavigateBack: () -> Unit
) {}

@Composable
fun ReportsScreen(
    onNavigateToSettings: () -> Unit
) {}

@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit
) {}

@Composable
fun NotificationsScreen(
    onNavigateBack: () -> Unit
) {}
