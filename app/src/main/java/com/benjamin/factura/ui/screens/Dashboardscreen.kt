package com.benjamin.factura.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.benjamin.factura.data.local.PreferencesManager
import com.benjamin.factura.data.local.dao.ClientDao
import com.benjamin.factura.data.local.dao.ExpenseDao
import com.benjamin.factura.data.local.dao.InvoiceDao
import com.benjamin.factura.data.local.dao.PaymentDao
import com.benjamin.factura.data.model.Client
import com.benjamin.factura.data.model.Invoice
import com.benjamin.factura.data.model.InvoiceStatus
import com.benjamin.factura.data.model.Payment
import com.benjamin.factura.ui.components.shimmerEffect
import com.benjamin.factura.util.Formatters
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

// ---------------------------------------------------------------------------
// UI STATE
// ---------------------------------------------------------------------------

data class RecentInvoiceUi(
    val id: String,
    val invoiceNumber: String,
    val clientName: String,
    val total: Double,
    val balanceDue: Double,
    val status: InvoiceStatus,
    val issueDateMillis: Long
)

data class RevenueTrendPoint(
    val monthLabel: String,
    val amount: Double
)

data class DashboardUiState(
    val isLoading: Boolean = true,
    val currencyCode: String = "KES",
    val totalRevenue: Double = 0.0,
    val monthlyRevenue: Double = 0.0,
    val outstandingAmount: Double = 0.0,
    val overdueAmount: Double = 0.0,
    val overdueCount: Int = 0,
    val recentInvoices: List<RecentInvoiceUi> = emptyList(),
    val revenueTrend: List<RevenueTrendPoint> = emptyList()
)

// ---------------------------------------------------------------------------
// VIEWMODEL
// ---------------------------------------------------------------------------

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val invoiceDao: InvoiceDao,
    private val clientDao: ClientDao,
    private val paymentDao: PaymentDao,
    private val expenseDao: ExpenseDao,
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<DashboardUiState> = preferencesManager.cachedBusinessProfile
        .flatMapLatest { businessProfile ->
            if (businessProfile == null) {
                flowOf(DashboardUiState(isLoading = false))
            } else {
                combine(
                    invoiceDao.getInvoicesFlow(businessProfile.id),
                    clientDao.getClientsFlow(businessProfile.id),
                    paymentDao.getPaymentsFlow(businessProfile.id)
                ) { invoices, clients, payments ->
                    buildUiState(
                        invoices = invoices,
                        clients = clients,
                        payments = payments,
                        currencyCode = businessProfile.defaultCurrency
                    )
                }
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = DashboardUiState(isLoading = true)
        )

    private fun buildUiState(
        invoices: List<Invoice>,
        clients: List<Client>,
        payments: List<Payment>,
        currencyCode: String
    ): DashboardUiState {
        val zoneId = ZoneId.systemDefault()
        val currentMonth = YearMonth.from(LocalDate.now(zoneId))
        val clientNameById = clients.associate { it.id to it.name }

        val activeInvoices = invoices.filterNot { it.status == InvoiceStatus.DRAFT }

        val totalRevenue = payments.sumOf { it.amount }

        val monthlyRevenue = payments.sumOf { payment ->
            if (payment.toLocalDate(zoneId).let { YearMonth.from(it) } == currentMonth) {
                payment.amount
            } else {
                0.0
            }
        }

        val outstandingAmount = activeInvoices
            .filter { it.status != InvoiceStatus.PAID && it.status != InvoiceStatus.CANCELLED }
            .sumOf { it.balanceDue() }

        val overdueInvoices = activeInvoices.filter { it.status == InvoiceStatus.OVERDUE }
        val overdueAmount = overdueInvoices.sumOf { it.balanceDue() }
        val overdueCount = overdueInvoices.size

        val recentInvoices = activeInvoices
            .sortedByDescending { it.issueDateMillis }
            .take(5)
            .map { invoice ->
                RecentInvoiceUi(
                    id = invoice.id,
                    invoiceNumber = invoice.invoiceNumber,
                    clientName = clientNameById[invoice.clientId] ?: "Unknown client",
                    total = invoice.total,
                    balanceDue = invoice.balanceDue(),
                    status = invoice.status,
                    issueDateMillis = invoice.issueDateMillis
                )
            }

        val revenueTrend = (5 downTo 0).map { monthsAgo ->
            val targetMonth = currentMonth.minusMonths(monthsAgo.toLong())
            val monthTotal = payments.sumOf { payment ->
                if (payment.toLocalDate(zoneId).let { YearMonth.from(it) } == targetMonth) {
                    payment.amount
                } else {
                    0.0
                }
            }
            RevenueTrendPoint(
                monthLabel = targetMonth.month.name
                    .take(3)
                    .lowercase()
                    .replaceFirstChar { it.uppercase() },
                amount = monthTotal
            )
        }

        return DashboardUiState(
            isLoading = false,
            currencyCode = currencyCode,
            totalRevenue = totalRevenue,
            monthlyRevenue = monthlyRevenue,
            outstandingAmount = outstandingAmount,
            overdueAmount = overdueAmount,
            overdueCount = overdueCount,
            recentInvoices = recentInvoices,
            revenueTrend = revenueTrend
        )
    }

    private fun Payment.toLocalDate(zoneId: ZoneId): LocalDate =
        Instant.ofEpochMilli(paidAtMillis).atZone(zoneId).toLocalDate()
}

// ---------------------------------------------------------------------------
// SCREEN
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateToInvoiceDetail: (String) -> Unit,
    onNavigateToInvoiceList: () -> Unit,
    onNavigateToCreateInvoice: () -> Unit,
    onNavigateToCreateClient: () -> Unit,
    onNavigateToCreateExpense: () -> Unit,
    onNavigateToNotifications: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var fabExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dashboard") },
                actions = {
                    IconButton(onClick = onNavigateToNotifications) {
                        Icon(Icons.Default.Notifications, contentDescription = "Notifications")
                    }
                }
            )
        },
        floatingActionButton = {
            DashboardFab(
                expanded = fabExpanded,
                onToggle = { fabExpanded = !fabExpanded },
                onCreateInvoice = {
                    fabExpanded = false
                    onNavigateToCreateInvoice()
                },
                onCreateClient = {
                    fabExpanded = false
                    onNavigateToCreateClient()
                },
                onCreateExpense = {
                    fabExpanded = false
                    onNavigateToCreateExpense()
                }
            )
        }
    ) { paddingValues ->
        if (uiState.isLoading) {
            DashboardSkeleton(paddingValues)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = paddingValues.calculateTopPadding() + 16.dp,
                    bottom = paddingValues.calculateBottomPadding() + 96.dp
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    SummaryCardsGrid(
                        currencyCode = uiState.currencyCode,
                        totalRevenue = uiState.totalRevenue,
                        monthlyRevenue = uiState.monthlyRevenue,
                        outstandingAmount = uiState.outstandingAmount,
                        overdueAmount = uiState.overdueAmount,
                        overdueCount = uiState.overdueCount
                    )
                }

                item {
                    RevenueTrendCard(
                        currencyCode = uiState.currencyCode,
                        points = uiState.revenueTrend
                    )
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Recent Invoices",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "See all",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { onNavigateToInvoiceList() }
                        )
                    }
                }

                if (uiState.recentInvoices.isEmpty()) {
                    item {
                        EmptyRecentInvoices()
                    }
                } else {
                    items(uiState.recentInvoices, key = { it.id }) { invoice ->
                        RecentInvoiceRow(
                            invoice = invoice,
                            currencyCode = uiState.currencyCode,
                            onClick = { onNavigateToInvoiceDetail(invoice.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardFab(
    expanded: Boolean,
    onToggle: () -> Unit,
    onCreateInvoice: () -> Unit,
    onCreateClient: () -> Unit,
    onCreateExpense: () -> Unit
) {
    Column(horizontalAlignment = Alignment.End) {
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MiniFabAction(
                    label = "New Expense",
                    icon = Icons.Default.Receipt,
                    onClick = onCreateExpense
                )
                MiniFabAction(
                    label = "New Client",
                    icon = Icons.Default.Person,
                    onClick = onCreateClient
                )
                MiniFabAction(
                    label = "New Invoice",
                    icon = Icons.Default.ReceiptLong,
                    onClick = onCreateInvoice
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
        }

        FloatingActionButton(onClick = onToggle) {
            Icon(Icons.Default.Add, contentDescription = "Quick actions")
        }
    }
}

@Composable
private fun MiniFabAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = { Icon(icon, contentDescription = label) },
        text = { Text(label) },
        containerColor = MaterialTheme.colorScheme.secondaryContainer
    )
}

@Composable
private fun SummaryCardsGrid(
    currencyCode: String,
    totalRevenue: Double,
    monthlyRevenue: Double,
    outstandingAmount: Double,
    overdueAmount: Double,
    overdueCount: Int
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            SummaryCard(
                modifier = Modifier.weight(1f),
                label = "Total Revenue",
                value = Formatters.formatCurrency(totalRevenue, currencyCode),
                accent = MaterialTheme.colorScheme.primary
            )
            SummaryCard(
                modifier = Modifier.weight(1f),
                label = "This Month",
                value = Formatters.formatCurrency(monthlyRevenue, currencyCode),
                accent = MaterialTheme.colorScheme.primary
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            SummaryCard(
                modifier = Modifier.weight(1f),
                label = "Outstanding",
                value = Formatters.formatCurrency(outstandingAmount, currencyCode),
                accent = MaterialTheme.colorScheme.tertiary
            )
            SummaryCard(
                modifier = Modifier.weight(1f),
                label = "Overdue${if (overdueCount > 0) " ($overdueCount)" else ""}",
                value = Formatters.formatCurrency(overdueAmount, currencyCode),
                accent = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun SummaryCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    accent: Color
) {
    Column(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp)
            )
            .padding(16.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = accent
        )
    }
}

@Composable
private fun RevenueTrendCard(
    currencyCode: String,
    points: List<RevenueTrendPoint>
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp)
            )
            .padding(16.dp)
    ) {
        Text(
            text = "Revenue Trend",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(16.dp))
        RevenueBarChart(points = points, currencyCode = currencyCode)
    }
}

@Composable
private fun RevenueBarChart(
    points: List<RevenueTrendPoint>,
    currencyCode: String
) {
    val maxAmount = (points.maxOfOrNull { it.amount } ?: 0.0).coerceAtLeast(1.0)
    val barColor = MaterialTheme.colorScheme.primary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Bottom
    ) {
        points.forEach { point ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f)
            ) {
                val fraction = (point.amount / maxAmount).toFloat().coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.5f)
                        .height((100 * fraction).dp.coerceAtLeast(2.dp))
                        .background(barColor, RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = point.monthLabel,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun RecentInvoiceRow(
    invoice: RecentInvoiceUi,
    currencyCode: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = invoice.invoiceNumber,
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = invoice.clientName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = Formatters.formatCurrency(invoice.total, currencyCode),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(2.dp))
            StatusPill(status = invoice.status)
        }
    }
}

@Composable
private fun StatusPill(status: InvoiceStatus) {
    val (bg, fg, label) = when (status) {
        InvoiceStatus.DRAFT -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "Draft"
        )
        InvoiceStatus.SENT -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            "Sent"
        )
        InvoiceStatus.VIEWED -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            "Viewed"
        )
        InvoiceStatus.PARTIAL -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            "Partial"
        )
        InvoiceStatus.PAID -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            "Paid"
        )
        InvoiceStatus.OVERDUE -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            "Overdue"
        )
        InvoiceStatus.CANCELLED -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            "Cancelled"
        )
    }

    Box(
        modifier = Modifier
            .background(color = bg, shape = RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun EmptyRecentInvoices() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "No invoices yet. Create your first one from the + button.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Skeleton loading state — content-shaped shimmer blocks, per the project-wide
 * rule that screen-level loading never uses a spinner.
 */
@Composable
private fun DashboardSkeleton(paddingValues: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = paddingValues.calculateTopPadding() + 16.dp
            ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            repeat(2) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(88.dp)
                        .shimmerEffect(RoundedCornerShape(16.dp))
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            repeat(2) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(88.dp)
                        .shimmerEffect(RoundedCornerShape(16.dp))
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp)
                .shimmerEffect(RoundedCornerShape(16.dp))
        )
        repeat(4) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .shimmerEffect(RoundedCornerShape(12.dp))
            )
        }
    }
}