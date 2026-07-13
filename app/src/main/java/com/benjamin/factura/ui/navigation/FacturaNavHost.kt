package com.benjamin.factura.ui.navigation


import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

// NOTE: screen composables below don't exist in the project yet - they're the
// next phase after this one. Their expected signatures are the contract this
// file was written against; matching them exactly when each screen is built
// means nothing in this file needs to change afterward. Assumed flat package
// com.benjamin.factura.ui.screens.<ScreenName> per the project's current
// (collapsed, single-level) "screens" folder - split into subpackages later
// and only the import block below needs updating.
import com.benjamin.factura.ui.screens.*

/*
 * Factura — ui/navigation/FacturaNavHost.kt
 *
 * Single NavHost for the whole app, wrapped in one outer Scaffold whose
 * bottom bar is shown only when the current destination is one of the five
 * top-level tabs (see FacturaDestination.bottomNavDestinations). Splash and
 * Onboarding sit outside that entirely (no Scaffold chrome); detail/edit
 * screens (InvoiceDetail, CreateInvoice, ClientDetail, CreateEditClient,
 * CreateExpense, PaymentRecord, Settings) are pushed on top of a tab with the
 * bottom bar hidden, consistent with the spec's "Scaffold + TopAppBar, FAB
 * for primary actions" pattern per screen.
 *
 * A single NavHost (rather than a nested bottom-tab graph with its own
 * back stacks per tab) was chosen for simplicity: this app doesn't need
 * independent back stacks per tab, and one graph keeps deep links (e.g. an
 * FCM notification opening InvoiceDetail directly) straightforward.
 */

private data class BottomNavItem(
    val destination: FacturaDestination,
    val label: String,
    val icon: ImageVector
)

private val bottomNavItems = listOf(
    BottomNavItem(FacturaDestination.Dashboard, "Dashboard", Icons.Default.Dashboard),
    BottomNavItem(FacturaDestination.InvoiceList, "Invoices", Icons.Default.ReceiptLong),
    BottomNavItem(FacturaDestination.ClientList, "Clients", Icons.Default.People),
    BottomNavItem(FacturaDestination.ExpenseList, "Expenses", Icons.Default.Receipt),
    BottomNavItem(FacturaDestination.Reports, "Reports", Icons.Default.BarChart)
)

@Composable
fun FacturaNavHost(
    navController: NavHostController = rememberNavController(),
    startDestination: String = FacturaDestination.Splash.route
) {
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination

    val showBottomBar = FacturaDestination.bottomNavDestinations.any { tab ->
        currentRoute?.hierarchy?.any { it.route == tab.route } == true
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                FacturaBottomBar(navController = navController, currentRoute = currentRoute?.route)
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding)
        ) {

            // -------------------------------------------------------------
            // Splash - checks auth state, routes to Onboarding or Dashboard
            // -------------------------------------------------------------
            composable(FacturaDestination.Splash.route) {
                SplashScreen(
                    onNavigateToOnboarding = {
                        navController.navigate(FacturaDestination.Onboarding.route) {
                            popUpTo(FacturaDestination.Splash.route) { inclusive = true }
                        }
                    },
                    onNavigateToDashboard = {
                        navController.navigate(FacturaDestination.Dashboard.route) {
                            popUpTo(FacturaDestination.Splash.route) { inclusive = true }
                        }
                    }
                )
            }

            // -------------------------------------------------------------
            // Onboarding - 3-step pager (Welcome, BusinessSetup, AccountCreate)
            // -------------------------------------------------------------
            composable(FacturaDestination.Onboarding.route) {
                OnboardingScreen(
                    onOnboardingComplete = {
                        navController.navigate(FacturaDestination.Dashboard.route) {
                            popUpTo(FacturaDestination.Onboarding.route) { inclusive = true }
                        }
                    }
                )
            }

            // -------------------------------------------------------------
            // Dashboard
            // -------------------------------------------------------------
            composable(FacturaDestination.Dashboard.route) {
                DashboardScreen(
                    onNavigateToInvoiceList = {
                        navController.navigate(FacturaDestination.InvoiceList.route)
                    },
                    onNavigateToInvoiceDetail = { invoiceId ->
                        navController.navigate(FacturaDestination.InvoiceDetail.createRoute(invoiceId))
                    },
                    onNavigateToCreateInvoice = {
                        navController.navigate(FacturaDestination.CreateInvoice.createRoute())
                    },
                    onNavigateToCreateClient = {
                        navController.navigate(FacturaDestination.CreateEditClient.createRoute())
                    },
                    onNavigateToCreateExpense = {
                        navController.navigate(FacturaDestination.CreateExpense.createRoute())
                    },
                    onNavigateToNotifications = {
                        navController.navigate(FacturaDestination.Notifications.route)
                    }
                )
            }

            // -------------------------------------------------------------
            // Notifications
            // -------------------------------------------------------------
            composable(FacturaDestination.Notifications.route) {
                NotificationsScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            // -------------------------------------------------------------
            // Invoice list / detail / create-edit
            // -------------------------------------------------------------
            composable(
                route = FacturaDestination.InvoiceList.route,
                arguments = listOf(
                    navArgument(FacturaDestination.ARG_STATUS_FILTER) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { backStackEntry ->
                val statusFilter = backStackEntry.arguments?.getString(FacturaDestination.ARG_STATUS_FILTER)
                InvoiceListScreen(
                    initialStatusFilter = statusFilter,
                    onNavigateToInvoiceDetail = { invoiceId ->
                        navController.navigate(FacturaDestination.InvoiceDetail.createRoute(invoiceId))
                    },
                    onNavigateToCreateInvoice = {
                        navController.navigate(FacturaDestination.CreateInvoice.createRoute())
                    }
                )
            }

            composable(
                route = FacturaDestination.InvoiceDetail.route,
                arguments = listOf(navArgument(FacturaDestination.ARG_INVOICE_ID) { type = NavType.StringType })
            ) { backStackEntry ->
                val invoiceId = backStackEntry.arguments?.getString(FacturaDestination.ARG_INVOICE_ID)
                    ?: return@composable
                InvoiceDetailScreen(
                    invoiceId = invoiceId,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToEdit = {
                        navController.navigate(FacturaDestination.CreateInvoice.createRoute(invoiceId = invoiceId))
                    },
                    onNavigateToRecordPayment = {
                        navController.navigate(FacturaDestination.PaymentRecord.createRoute(invoiceId))
                    }
                )
            }

            composable(
                route = FacturaDestination.CreateInvoice.route,
                arguments = listOf(
                    navArgument(FacturaDestination.ARG_INVOICE_ID) {
                        type = NavType.StringType; nullable = true; defaultValue = null
                    },
                    navArgument(FacturaDestination.ARG_CLIENT_ID) {
                        type = NavType.StringType; nullable = true; defaultValue = null
                    }
                )
            ) { backStackEntry ->
                CreateInvoiceScreen(
                    invoiceId = backStackEntry.arguments?.getString(FacturaDestination.ARG_INVOICE_ID),
                    preselectedClientId = backStackEntry.arguments?.getString(FacturaDestination.ARG_CLIENT_ID),
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            // -------------------------------------------------------------
            // Client list / detail / create-edit
            // -------------------------------------------------------------
            composable(FacturaDestination.ClientList.route) {
                ClientListScreen(
                    onNavigateToClientDetail = { clientId ->
                        navController.navigate(FacturaDestination.ClientDetail.createRoute(clientId))
                    },
                    onNavigateToCreateClient = {
                        navController.navigate(FacturaDestination.CreateEditClient.createRoute())
                    }
                )
            }

            composable(
                route = FacturaDestination.ClientDetail.route,
                arguments = listOf(navArgument(FacturaDestination.ARG_CLIENT_ID) { type = NavType.StringType })
            ) { backStackEntry ->
                val clientId = backStackEntry.arguments?.getString(FacturaDestination.ARG_CLIENT_ID)
                    ?: return@composable
                ClientDetailScreen(
                    clientId = clientId,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToEditClient = {
                        navController.navigate(FacturaDestination.CreateEditClient.createRoute(clientId = clientId))
                    },
                    onNavigateToInvoiceDetail = { invoiceId ->
                        navController.navigate(FacturaDestination.InvoiceDetail.createRoute(invoiceId))
                    },
                    onNavigateToCreateInvoiceForClient = {
                        navController.navigate(FacturaDestination.CreateInvoice.createRoute(clientId = clientId))
                    }
                )
            }

            composable(
                route = FacturaDestination.CreateEditClient.route,
                arguments = listOf(
                    navArgument(FacturaDestination.ARG_CLIENT_ID) {
                        type = NavType.StringType; nullable = true; defaultValue = null
                    }
                )
            ) { backStackEntry ->
                CreateEditClientScreen(
                    clientId = backStackEntry.arguments?.getString(FacturaDestination.ARG_CLIENT_ID),
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            // -------------------------------------------------------------
            // Expense list / create-edit (with OCR scan entry point)
            // -------------------------------------------------------------
            composable(FacturaDestination.ExpenseList.route) {
                ExpenseListScreen(
                    onNavigateToCreateExpense = {
                        navController.navigate(FacturaDestination.CreateExpense.createRoute())
                    },
                    onNavigateToEditExpense = { expenseId ->
                        navController.navigate(FacturaDestination.CreateExpense.createRoute(expenseId = expenseId))
                    }
                )
            }

            composable(
                route = FacturaDestination.CreateExpense.route,
                arguments = listOf(
                    navArgument(FacturaDestination.ARG_EXPENSE_ID) {
                        type = NavType.StringType; nullable = true; defaultValue = null
                    }
                )
            ) { backStackEntry ->
                CreateExpenseScreen(
                    expenseId = backStackEntry.arguments?.getString(FacturaDestination.ARG_EXPENSE_ID),
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            // -------------------------------------------------------------
            // Payment recording (always scoped to one invoice)
            // -------------------------------------------------------------
            composable(
                route = FacturaDestination.PaymentRecord.route,
                arguments = listOf(navArgument(FacturaDestination.ARG_INVOICE_ID) { type = NavType.StringType })
            ) { backStackEntry ->
                val invoiceId = backStackEntry.arguments?.getString(FacturaDestination.ARG_INVOICE_ID)
                    ?: return@composable
                PaymentRecordScreen(
                    invoiceId = invoiceId,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            // -------------------------------------------------------------
            // Reports
            // -------------------------------------------------------------
            composable(FacturaDestination.Reports.route) {
                ReportsScreen(
                    onNavigateToSettings = { navController.navigate(FacturaDestination.Settings.route) }
                )
            }

            // -------------------------------------------------------------
            // Settings
            // -------------------------------------------------------------
            composable(FacturaDestination.Settings.route) {
                SettingsScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}

@Composable
private fun FacturaBottomBar(navController: NavHostController, currentRoute: String?) {
    NavigationBar {
        bottomNavItems.forEach { item ->
            NavigationBarItem(
                selected = currentRoute == item.destination.route,
                onClick = {
                    navController.navigate(item.destination.route) {
                        // Avoid piling up multiple copies of the same tab, and
                        // return to the graph's start tab state on re-selection.
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(imageVector = item.icon, contentDescription = item.label) },
                label = { androidx.compose.material3.Text(item.label) }
            )
        }
    }
}