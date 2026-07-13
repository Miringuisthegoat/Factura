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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.benjamin.factura.data.local.dao.ExpenseDao
import com.benjamin.factura.data.model.Expense
import com.benjamin.factura.data.model.ExpenseCategory
import com.benjamin.factura.data.local.PreferencesManager
import com.benjamin.factura.ui.components.shimmerEffect
import com.benjamin.factura.util.Formatters
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────
// UI models
// ─────────────────────────────────────────────────────────────────────────

/** Null means "All categories" — no filter applied. */
typealias CategoryFilter = ExpenseCategory?

data class ExpenseListItemUi(
    val id: String,
    val vendor: String,
    val category: ExpenseCategory,
    val amount: Double,
    val dateMillis: Long,
    val hasReceiptPhoto: Boolean
)

data class ExpenseListUiState(
    val isLoading: Boolean = true,
    val currencyCode: String = "KES",
    val searchQuery: String = "",
    val selectedCategory: CategoryFilter = null,
    val categoryCounts: Map<ExpenseCategory, Int> = emptyMap(),
    val expenses: List<ExpenseListItemUi> = emptyList(),
    val totalExpenseCount: Int = 0,
    val filteredTotal: Double = 0.0
)

// ─────────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────────

@HiltViewModel
class ExpenseListViewModel @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")
    private val selectedCategory = MutableStateFlow<CategoryFilter>(null)

    val uiState: StateFlow<ExpenseListUiState> = combine(
        expenseDao.getAllExpenses(),
        preferencesManager.businessProfileFlow,
        searchQuery,
        selectedCategory
    ) { expenses, businessProfile, query, categoryFilter ->
        buildUiState(
            expenses = expenses,
            currencyCode = businessProfile?.defaultCurrency ?: "KES",
            query = query,
            categoryFilter = categoryFilter
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = ExpenseListUiState(isLoading = true)
        )

    fun onSearchQueryChange(query: String) {
        searchQuery.value = query
    }

    fun onCategoryFilterSelected(category: CategoryFilter) {
        selectedCategory.value = category
    }

    private fun buildUiState(
        expenses: List<Expense>,
        currencyCode: String,
        query: String,
        categoryFilter: CategoryFilter
    ): ExpenseListUiState {
        val categoryCounts = expenses.groupingBy { it.category }.eachCount()

        val needle = query.trim()
        val filtered = expenses
            .asSequence()
            .filter { categoryFilter == null || it.category == categoryFilter }
            .filter { expense ->
                needle.isBlank() || expense.vendor.contains(needle, ignoreCase = true)
            }
            .sortedByDescending { it.expenseDateMillis }
            .map { expense ->
                ExpenseListItemUi(
                    id = expense.id,
                    vendor = expense.vendor,
                    category = expense.category,
                    amount = expense.amount,
                    dateMillis = expense.expenseDateMillis,
                    hasReceiptPhoto = !expense.receiptImageUrl.isNullOrBlank()
                )
            }
            .toList()

        return ExpenseListUiState(
            isLoading = false,
            currencyCode = currencyCode,
            searchQuery = query,
            selectedCategory = categoryFilter,
            categoryCounts = categoryCounts,
            expenses = filtered,
            totalExpenseCount = expenses.size,
            filteredTotal = filtered.sumOf { it.amount }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Screen
// ─────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseListScreen(
    onNavigateToExpenseDetail: (String) -> Unit,
    onNavigateToCreateExpense: () -> Unit,
    viewModel: ExpenseListViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Expenses") })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNavigateToCreateExpense) {
                Icon(Icons.Default.Add, contentDescription = "New expense")
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
        ) {
            ExpenseSearchField(
                query = uiState.searchQuery,
                onQueryChange = viewModel::onSearchQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            CategoryFilterRow(
                selectedCategory = uiState.selectedCategory,
                categoryCounts = uiState.categoryCounts,
                totalCount = uiState.totalExpenseCount,
                onCategorySelected = viewModel::onCategoryFilterSelected
            )

            if (!uiState.isLoading && uiState.expenses.isNotEmpty()) {
                FilteredTotalBanner(
                    total = uiState.filteredTotal,
                    currencyCode = uiState.currencyCode,
                    count = uiState.expenses.size
                )
            }

            if (uiState.isLoading) {
                ExpenseListSkeleton()
            } else if (uiState.expenses.isEmpty()) {
                ExpenseListEmptyState(
                    hasActiveFilters = uiState.searchQuery.isNotBlank() || uiState.selectedCategory != null,
                    hasAnyExpenses = uiState.totalExpenseCount > 0
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
                    items(uiState.expenses, key = { it.id }) { expense ->
                        ExpenseRow(
                            expense = expense,
                            currencyCode = uiState.currencyCode,
                            onClick = { onNavigateToExpenseDetail(expense.id) }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpenseSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        placeholder = { Text("Search by vendor") },
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
private fun CategoryFilterRow(
    selectedCategory: CategoryFilter,
    categoryCounts: Map<ExpenseCategory, Int>,
    totalCount: Int,
    onCategorySelected: (CategoryFilter) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            CategoryChip(
                label = "All",
                count = totalCount,
                selected = selectedCategory == null,
                onClick = { onCategorySelected(null) }
            )
        }
        items(ExpenseCategory.entries.toList()) { category ->
            CategoryChip(
                label = category.displayLabel(),
                count = categoryCounts[category] ?: 0,
                selected = selectedCategory == category,
                onClick = { onCategorySelected(category) }
            )
        }
        item { Spacer(modifier = Modifier.width(4.dp)) }
    }
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun CategoryChip(
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
private fun FilteredTotalBanner(
    total: Double,
    currencyCode: String,
    count: Int
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$count expense${if (count == 1) "" else "s"}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = Formatters.formatCurrency(total, currencyCode),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium
        )
    }
    Spacer(modifier = Modifier.height(4.dp))
}

@Composable
private fun ExpenseRow(
    expense: ExpenseListItemUi,
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
        CategoryIcon(category = expense.category)

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = expense.vendor,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = expense.category.displayLabel(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = Formatters.formatCurrency(expense.amount, currencyCode),
                style = MaterialTheme.typography.titleSmall
            )
            if (expense.hasReceiptPhoto) {
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Receipt,
                        contentDescription = "Has receipt photo",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = "receipt",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryIcon(category: ExpenseCategory) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = category.displayLabel().take(1),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

private fun ExpenseCategory.displayLabel(): String = name
    .lowercase()
    .split("_")
    .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

@Composable
private fun ExpenseListEmptyState(
    hasActiveFilters: Boolean,
    hasAnyExpenses: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ReceiptLong,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = when {
                    hasActiveFilters -> "No expenses match your filters"
                    !hasAnyExpenses -> "No expenses yet"
                    else -> "No expenses found"
                },
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (hasActiveFilters) {
                    "Try a different vendor name or category."
                } else {
                    "Log your first expense using the + button below, or scan a receipt."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ExpenseListSkeleton() {
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
                Spacer(modifier = Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .width(64.dp)
                        .height(16.dp)
                        .shimmerEffect(RoundedCornerShape(4.dp))
                )
            }
        }
    }
}