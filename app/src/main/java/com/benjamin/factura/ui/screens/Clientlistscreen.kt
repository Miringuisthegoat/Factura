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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.benjamin.factura.data.local.PreferencesManager
import com.benjamin.factura.data.local.dao.ClientDao
import com.benjamin.factura.data.local.dao.InvoiceDao
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

// ---------------------------------------------------------------------------
// UI STATE
// ---------------------------------------------------------------------------

data class ClientListItemUi(
    val id: String,
    val name: String,
    val phone: String,
    val email: String,
    val invoiceCount: Int,
    val outstandingBalance: Double
)

data class ClientListUiState(
    val isLoading: Boolean = true,
    val currencyCode: String = "KES",
    val searchQuery: String = "",
    val clients: List<ClientListItemUi> = emptyList(),
    val totalClientCount: Int = 0
)

// ---------------------------------------------------------------------------
// VIEWMODEL
// ---------------------------------------------------------------------------

@HiltViewModel
class ClientListViewModel @Inject constructor(
    private val clientDao: ClientDao,
    private val invoiceDao: InvoiceDao,
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<ClientListUiState> = preferencesManager.cachedBusinessProfile
        .flatMapLatest { businessProfile ->
            if (businessProfile == null) {
                flowOf(ClientListUiState(isLoading = false))
            } else {
                combine(
                    clientDao.getClientsFlow(businessProfile.id),
                    invoiceDao.getInvoicesFlow(businessProfile.id),
                    searchQuery
                ) { clients, invoices, query ->
                    buildUiState(
                        clients = clients,
                        invoices = invoices,
                        currencyCode = businessProfile.defaultCurrency,
                        query = query
                    )
                }
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = ClientListUiState(isLoading = true)
        )

    fun onSearchQueryChange(query: String) {
        searchQuery.value = query
    }

    private fun buildUiState(
        clients: List<Client>,
        invoices: List<Invoice>,
        currencyCode: String,
        query: String
    ): ClientListUiState {
        val invoicesByClientId = invoices
            .filterNot { it.status == InvoiceStatus.DRAFT || it.status == InvoiceStatus.CANCELLED }
            .groupBy { it.clientId }

        val allClientItems = clients.map { client ->
            val clientInvoices = invoicesByClientId[client.id].orEmpty()
            ClientListItemUi(
                id = client.id,
                name = client.name,
                phone = client.phone,
                email = client.email,
                invoiceCount = clientInvoices.size,
                outstandingBalance = clientInvoices
                    .filter { it.status != InvoiceStatus.PAID }
                    .sumOf { it.balanceDue() }
            )
        }

        val filtered = if (query.isBlank()) {
            allClientItems
        } else {
            val needle = query.trim()
            allClientItems.filter { item ->
                item.name.contains(needle, ignoreCase = true) ||
                        item.phone.contains(needle, ignoreCase = true) ||
                        item.email.contains(needle, ignoreCase = true)
            }
        }

        return ClientListUiState(
            isLoading = false,
            currencyCode = currencyCode,
            searchQuery = query,
            clients = filtered.sortedBy { it.name.lowercase() },
            totalClientCount = clients.size
        )
    }
}

// ---------------------------------------------------------------------------
// SCREEN
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientListScreen(
    onNavigateToClientDetail: (String) -> Unit,
    onNavigateToCreateClient: () -> Unit,
    viewModel: ClientListViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Clients") })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNavigateToCreateClient) {
                Icon(Icons.Default.Add, contentDescription = "New client")
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
        ) {
            ClientSearchField(
                query = uiState.searchQuery,
                onQueryChange = viewModel::onSearchQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (uiState.isLoading) {
                ClientListSkeleton()
            } else if (uiState.clients.isEmpty()) {
                ClientListEmptyState(
                    hasSearchQuery = uiState.searchQuery.isNotBlank(),
                    hasAnyClients = uiState.totalClientCount > 0
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = paddingValues.calculateBottomPadding() + 96.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(uiState.clients, key = { it.id }) { client ->
                        ClientRow(
                            client = client,
                            currencyCode = uiState.currencyCode,
                            onClick = { onNavigateToClientDetail(client.id) }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ClientSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        placeholder = { Text("Search clients by name, phone, or email") },
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
private fun ClientRow(
    client: ClientListItemUi,
    currencyCode: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ClientAvatar(name = client.name)

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = client.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
            val subtitle = when {
                client.phone.isNotBlank() -> client.phone
                client.email.isNotBlank() -> client.email
                else -> "${client.invoiceCount} invoice${if (client.invoiceCount == 1) "" else "s"}"
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (client.outstandingBalance > 0.0) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = Formatters.formatCurrency(client.outstandingBalance, currencyCode),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = "outstanding",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ClientAvatar(name: String) {
    val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun ClientListEmptyState(
    hasSearchQuery: Boolean,
    hasAnyClients: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.PersonOutline,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (hasSearchQuery) {
                    "No clients match your search"
                } else if (!hasAnyClients) {
                    "No clients yet"
                } else {
                    "No clients found"
                },
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (hasSearchQuery) {
                    "Try a different name, phone number, or email."
                } else {
                    "Add your first client using the + button below."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ClientListSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        repeat(8) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .shimmerEffect(CircleShape)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.5f)
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
            }
        }
    }
}