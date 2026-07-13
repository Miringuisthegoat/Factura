package com.benjamin.factura.ui.screens

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.benjamin.factura.data.local.ClientDao
import com.benjamin.factura.data.local.InvoiceDao
import com.benjamin.factura.data.local.PreferencesManager
import com.benjamin.factura.data.model.Client
import com.benjamin.factura.data.model.Invoice
import com.benjamin.factura.data.model.InvoiceStatus
import com.benjamin.factura.ui.components.shimmerEffect
import com.benjamin.factura.util.Formatters
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────
// UI models
// ─────────────────────────────────────────────────────────────────────────

/** Null means "All" — no status filter applied. */
typealias StatusFilter = InvoiceStatus?

data class InvoiceListItemUi(
    val id: String,
    val invoiceNumber: String,
    val clientName: String,
    val total: Double,
    val balanceDue: Double,
    val status: InvoiceStatus,
    val issueDateMillis: Long
)

data class InvoiceListUiState(
    val isLoading: Boolean = true,
    val currencyCode: String = "KES",
    val searchQuery: String = "",
    val selectedStatus: StatusFilter = null,
    val statusCounts: Map<InvoiceStatus, Int> = emptyMap(),
    val invoices: List<InvoiceListItemUi> = emptyList(),
    val totalInvoiceCount: Int = 0
)

// ─────────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class InvoiceListViewModel @Inject constructor(
    private val invoiceDao: InvoiceDao,
    private val clientDao: ClientDao,
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")
    private val selectedStatus = MutableStateFlow<StatusFilter>(null)

    val uiState: StateFlow<InvoiceListUiState> = preferencesManager.cachedBusinessProfile
        .flatMapLatest { businessProfile ->
            if (businessProfile == null) {
                flowOf(InvoiceListUiState(isLoading = false))
            } else {
                combine(
                    invoiceDao.getInvoicesFlow(businessProfile.id),
                    clientDao.getClientsFlow(businessProfile.id),
                    searchQuery,
                    selectedStatus
                ) { invoices, clients, query, statusFilter ->
                    buildUiState(
                        invoices = invoices,
                        clients = clients,
                        currencyCode = businessProfile.defaultCurrency,
                        query = query,
                        statusFilter = statusFilter
                    )
                }
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = InvoiceListUiState(isLoading = true)
        )

    fun onSearchQueryChange(query: String) {
        searchQuery.value = query
    }

    fun onStatusFilterSelected(status: StatusFilter) {
        selectedStatus.value = status
    }

    private fun buildUiState(
        invoices: List<Invoice>,
        clients: List<Client>,
        currencyCode: String,
        query: String,
        statusFilter: StatusFilter
    ): InvoiceListUiState {
        val clientNameById = clients.associate { it.id to it.name }

        val statusCounts = invoices.groupingBy { it.status }.eachCount()

        val needle = query.trim()
        val filtered = invoices
            .asSequence()
            .filter { statusFilter == null || it.status == statusFilter }
            .filter { invoice ->
                if (needle.isBlank()) {
                    true
                } else {
                    val clientName = clientNameById[invoice.clientId].orEmpty()
                    invoice.invoiceNumber.contains(needle, ignoreCase = true) ||
                            clientName.contains(needle, ignoreCase = true)
                }
            }
            .sortedByDescending { it.issueDateMillis }
            .map { invoice ->
                InvoiceListItemUi(
                    id = invoice.id,
                    invoiceNumber = invoice.invoiceNumber,
                    clientName = clientNameById[invoice.clientId] ?: "Unknown client",
                    total = invoice.total,
                    balanceDue = invoice.balanceDue(),
                    status = invoice.status,
                    issueDateMillis = invoice.issueDateMillis
                )
            }
            .toList()

        return InvoiceListUiState(
            isLoading = false,
            currencyCode = currencyCode,
            searchQuery = query,
            selectedStatus = statusFilter,
            statusCounts = statusCounts,
            invoices = filtered,
            totalInvoiceCount = invoices.size
        )
    }

    fun setInitialStatusFilter(statusName: String?) {
        if (statusName != null) {
            val status = runCatching { InvoiceStatus.valueOf(statusName) }.getOrNull()
            if (status != null) {
                selectedStatus.value = status
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Screen
// ─────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvoiceListScreen(
    initialStatusFilter: String?,
    onNavigateToInvoiceDetail: (String) -> Unit,
    onNavigateToCreateInvoice: () -> Unit,
    viewModel: InvoiceListViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Apply initial filter once
    androidx.compose.runtime.LaunchedEffect(initialStatusFilter) {
        viewModel.setInitialStatusFilter(initialStatusFilter)
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Invoices") })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNavigateToCreateInvoice) {
                Icon(Icons.Default.Add, contentDescription = "New invoice")
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
        ) {
            InvoiceSearchField(
                query = uiState.searchQuery,
                onQueryChange = viewModel::onSearchQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            StatusFilterRow(
                selectedStatus = uiState.selectedStatus,
                statusCounts = uiState.statusCounts,
                totalCount = uiState.totalInvoiceCount,
                onStatusSelected = viewModel::onStatusFilterSelected
            )

            if (uiState.isLoading) {
                InvoiceListSkeleton()
            } else if (uiState.invoices.isEmpty()) {
                InvoiceListEmptyState(
                    hasActiveFilters = uiState.searchQuery.isNotBlank() || uiState.selectedStatus != null,
                    hasAnyInvoices = uiState.totalInvoiceCount > 0
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = paddingValues.calculateBottomPadding() + 96.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(uiState.invoices, key = { it.id }) { invoice ->
                        InvoiceRow(
                            invoice = invoice,
                            currencyCode = uiState.currencyCode,
                            onClick = { onNavigateToInvoiceDetail(invoice.id) }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun InvoiceSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        placeholder = { Text("Search by invoice number or client") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors()
    )
}

@Composable
private fun StatusFilterRow(
    selectedStatus: StatusFilter,
    statusCounts: Map<InvoiceStatus, Int>,
    totalCount: Int,
    onStatusSelected: (StatusFilter) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            StatusChip(
                label = "All",
                count = totalCount,
                selected = selectedStatus == null,
                onClick = { onStatusSelected(null) }
            )
        }
        items(InvoiceStatus.entries.toList()) { status ->
            StatusChip(
                label = status.displayLabel(),
                count = statusCounts[status] ?: 0,
                selected = selectedStatus == status,
                onClick = { onStatusSelected(status) }
            )
        }
        item { Spacer(modifier = Modifier.width(4.dp)) }
    }
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun StatusChip(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(if (count > 0) "$label ($count)" else label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    )
}

@Composable
private fun InvoiceRow(
    invoice: InvoiceListItemUi,
    currencyCode: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = invoice.invoiceNumber,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = invoice.clientName,
                style = MaterialTheme.typography.bodySmall,
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
    val (bg, fg) = when (status) {
        InvoiceStatus.DRAFT -> MaterialTheme.colorScheme.surfaceVariant to
                MaterialTheme.colorScheme.onSurfaceVariant
        InvoiceStatus.SENT -> MaterialTheme.colorScheme.secondaryContainer to
                MaterialTheme.colorScheme.onSecondaryContainer
        InvoiceStatus.VIEWED -> MaterialTheme.colorScheme.tertiaryContainer to
                MaterialTheme.colorScheme.onTertiaryContainer
        InvoiceStatus.PARTIAL -> MaterialTheme.colorScheme.tertiaryContainer to
                MaterialTheme.colorScheme.onTertiaryContainer
        InvoiceStatus.PAID -> MaterialTheme.colorScheme.primaryContainer to
                MaterialTheme.colorScheme.onPrimaryContainer
        InvoiceStatus.OVERDUE -> MaterialTheme.colorScheme.errorContainer to
                MaterialTheme.colorScheme.onErrorContainer
        InvoiceStatus.CANCELLED -> MaterialTheme.colorScheme.errorContainer to
                MaterialTheme.colorScheme.onErrorContainer
    }

    Box(
        modifier = Modifier
            .background(color = bg, shape = RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = status.displayLabel(),
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            fontSize = 11.sp
        )
    }
}

private fun InvoiceStatus.displayLabel(): String = when (this) {
    InvoiceStatus.DRAFT -> "Draft"
    InvoiceStatus.SENT -> "Sent"
    InvoiceStatus.VIEWED -> "Viewed"
    InvoiceStatus.PARTIAL -> "Partial"
    InvoiceStatus.PAID -> "Paid"
    InvoiceStatus.OVERDUE -> "Overdue"
    InvoiceStatus.CANCELLED -> "Cancelled"
}

@Composable
private fun InvoiceListEmptyState(
    hasActiveFilters: Boolean,
    hasAnyInvoices: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.ReceiptLong,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = when {
                    hasActiveFilters -> "No invoices match your filters"
                    !hasAnyInvoices -> "No invoices yet"
                    else -> "No invoices found"
                },
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (hasActiveFilters) {
                    "Try a different search term or status."
                } else {
                    "Create your first invoice using the + button below."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun InvoiceListSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        repeat(8) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.4f)
                            .height(16.dp)
                            .shimmerEffect(RoundedCornerShape(4.dp))
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.3f)
                            .height(12.dp)
                            .shimmerEffect(RoundedCornerShape(4.dp))
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .width(72.dp)
                        .height(24.dp)
                        .shimmerEffect(RoundedCornerShape(50))
                )
            }
        }
    }
}
