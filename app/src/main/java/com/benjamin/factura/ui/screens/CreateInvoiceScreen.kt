package com.benjamin.factura.ui.screens


import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.benjamin.factura.data.local.dao.ClientDao
import com.benjamin.factura.data.local.dao.InvoiceDao
import com.benjamin.factura.data.model.Client
import com.benjamin.factura.data.model.DiscountType
import com.benjamin.factura.data.model.Invoice
import com.benjamin.factura.data.model.InvoiceItem
import com.benjamin.factura.data.model.InvoiceStatus
import com.benjamin.factura.data.local.PreferencesManager
import com.benjamin.factura.util.Formatters
import com.google.ai.client.generativeai.GenerativeModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────
// UI models
// ─────────────────────────────────────────────────────────────────────────

data class ClientPickerItem(
    val id: String,
    val name: String
)

data class InvoiceLineItemInput(
    val localId: String = UUID.randomUUID().toString(),
    val description: String = "",
    val quantityText: String = "1",
    val unitPriceText: String = "",
    val discountType: DiscountType = DiscountType.PERCENTAGE,
    val discountValueText: String = "0",
    val taxRatePercentText: String = "16"
) {
    val quantity: Double get() = quantityText.toDoubleOrNull() ?: 0.0
    val unitPrice: Double get() = unitPriceText.toDoubleOrNull() ?: 0.0
    val discountValue: Double get() = discountValueText.toDoubleOrNull() ?: 0.0
    val taxRatePercent: Double get() = taxRatePercentText.toDoubleOrNull() ?: 0.0

    val lineSubtotal: Double get() = quantity * unitPrice

    val lineDiscountAmount: Double get() = when (discountType) {
        DiscountType.PERCENTAGE -> lineSubtotal * (discountValue / 100.0)
        DiscountType.FIXED_AMOUNT -> discountValue
    }.coerceIn(0.0, lineSubtotal.coerceAtLeast(0.0))

    val lineTaxableAmount: Double get() = (lineSubtotal - lineDiscountAmount).coerceAtLeast(0.0)
    val lineTaxAmount: Double get() = lineTaxableAmount * (taxRatePercent / 100.0)
    val lineTotal: Double get() = lineTaxableAmount + lineTaxAmount
}

data class CreateInvoiceUiState(
    val isLoadingClients: Boolean = true,
    val clients: List<ClientPickerItem> = emptyList(),
    val selectedClientId: String? = null,
    val selectedClientName: String? = null,
    val invoiceNumber: String = "",
    val issueDateMillis: Long = System.currentTimeMillis(),
    val dueDateMillis: Long = System.currentTimeMillis() + (14L * 24 * 60 * 60 * 1000),
    val lineItems: List<InvoiceLineItemInput> = listOf(InvoiceLineItemInput()),
    val notes: String = "",
    val currencyCode: String = "KES",
    val isAutoFilling: Boolean = false,
    val autoFillError: String? = null,
    val isSaving: Boolean = false,
    val saveError: String? = null,
    val savedInvoiceId: String? = null
) {
    val subtotal: Double get() = lineItems.sumOf { it.lineSubtotal }
    val discountTotal: Double get() = lineItems.sumOf { it.lineDiscountAmount }
    val taxTotal: Double get() = lineItems.sumOf { it.lineTaxAmount }
    val total: Double get() = lineItems.sumOf { it.lineTotal }

    val canAutoFill: Boolean get() = selectedClientId != null && !isAutoFilling
    val canSave: Boolean get() = selectedClientId != null &&
            lineItems.any { it.description.isNotBlank() && it.unitPrice > 0.0 } &&
            !isSaving
}

// ─────────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────────

@HiltViewModel
class CreateInvoiceViewModel @Inject constructor(
    private val invoiceDao: InvoiceDao,
    private val clientDao: ClientDao,
    private val preferencesManager: PreferencesManager,
    private val generativeModel: GenerativeModel
) : ViewModel() {

    private val _uiState = MutableStateFlow(CreateInvoiceUiState())
    val uiState: StateFlow<CreateInvoiceUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            clientDao.getAllClients().collect { clients ->
                _uiState.value = _uiState.value.copy(
                    isLoadingClients = false,
                    clients = clients.map { ClientPickerItem(it.id, it.name) }
                )
            }
        }
        viewModelScope.launch {
            val businessProfile = preferencesManager.cachedBusinessProfile.first()
            val prefix = businessProfile?.invoiceNumberPrefix ?: "INV-"
            val defaultTaxRate = businessProfile?.defaultTaxRatePercent ?: 16.0
            val paymentTermsDays = businessProfile?.paymentTermsDays ?: 14
            val existingCount = invoiceDao.getInvoiceCount()
            val nextNumber = (existingCount + 1).toString().padStart(4, '0')

            _uiState.value = _uiState.value.copy(
                invoiceNumber = "$prefix$nextNumber",
                currencyCode = businessProfile?.defaultCurrency ?: "KES",
                dueDateMillis = System.currentTimeMillis() + (paymentTermsDays.toLong() * 24 * 60 * 60 * 1000),
                lineItems = listOf(InvoiceLineItemInput(taxRatePercentText = defaultTaxRate.toPlainString()))
            )
        }
    }

    fun onClientSelected(client: ClientPickerItem) {
        _uiState.value = _uiState.value.copy(
            selectedClientId = client.id,
            selectedClientName = client.name,
            autoFillError = null
        )
    }

    fun onIssueDateChanged(millis: Long) {
        _uiState.value = _uiState.value.copy(issueDateMillis = millis)
    }

    fun onDueDateChanged(millis: Long) {
        _uiState.value = _uiState.value.copy(dueDateMillis = millis)
    }

    fun onNotesChanged(notes: String) {
        _uiState.value = _uiState.value.copy(notes = notes)
    }

    fun onAddLineItem() {
        val defaultTax = _uiState.value.lineItems.lastOrNull()?.taxRatePercentText ?: "16"
        _uiState.value = _uiState.value.copy(
            lineItems = _uiState.value.lineItems + InvoiceLineItemInput(taxRatePercentText = defaultTax)
        )
    }

    fun onRemoveLineItem(localId: String) {
        val current = _uiState.value.lineItems
        if (current.size <= 1) return
        _uiState.value = _uiState.value.copy(
            lineItems = current.filterNot { it.localId == localId }
        )
    }

    fun onLineItemChanged(updated: InvoiceLineItemInput) {
        _uiState.value = _uiState.value.copy(
            lineItems = _uiState.value.lineItems.map {
                if (it.localId == updated.localId) updated else it
            }
        )
    }

    /**
     * Gemini auto-fill: pulls the client's last 5 invoices, summarizes their line items
     * as JSON, and asks the model to suggest a fresh set of line items for this invoice.
     */
    fun onAutoFillFromHistory() {
        val clientId = _uiState.value.selectedClientId ?: return
        _uiState.value = _uiState.value.copy(isAutoFilling = true, autoFillError = null)

        viewModelScope.launch {
            try {
                val pastInvoices = invoiceDao.getInvoicesForClientFlow(clientId)
                    .first()
                    .sortedByDescending { it.issueDateMillis }
                    .take(5)

                if (pastInvoices.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isAutoFilling = false,
                        autoFillError = "This client has no invoice history yet."
                    )
                    return@launch
                }

                val historyJson = JSONArray()
                pastInvoices.forEach { invoice ->
                    invoice.items.forEach { item ->
                        historyJson.put(
                            JSONObject().apply {
                                put("description", item.description)
                                put("quantity", item.quantity)
                                put("unitPrice", item.unitPrice)
                            }
                        )
                    }
                }

                val prompt = """
                    You are helping auto-fill a new invoice for a returning client based on
                    their past invoice history. Below is a JSON array of line items from
                    their last ${pastInvoices.size} invoices.

                    Suggest a list of line items for a brand new invoice, reusing the same
                    client's typical item descriptions, quantities and prices where the
                    pattern is clear. Keep the list concise (no more than 6 items).

                    Respond ONLY with a raw JSON array, no markdown code fences, no
                    explanation. Each object must have exactly these fields:
                    "description" (string), "quantity" (number), "unitPrice" (number).

                    History:
                    $historyJson
                """.trimIndent()

                val response = generativeModel.generateContent(prompt)
                val rawText = response.text.orEmpty()
                    .trim()
                    .removePrefix("```json")
                    .removePrefix("```")
                    .removeSuffix("```")
                    .trim()

                val suggestions = JSONArray(rawText)
                val defaultTax = _uiState.value.lineItems.firstOrNull()?.taxRatePercentText ?: "16"
                val newLineItems = (0 until suggestions.length()).map { index ->
                    val obj = suggestions.getJSONObject(index)
                    InvoiceLineItemInput(
                        description = obj.optString("description", ""),
                        quantityText = obj.optDouble("quantity", 1.0).toPlainString(),
                        unitPriceText = obj.optDouble("unitPrice", 0.0).toPlainString(),
                        taxRatePercentText = defaultTax
                    )
                }

                _uiState.value = _uiState.value.copy(
                    isAutoFilling = false,
                    lineItems = newLineItems.ifEmpty { _uiState.value.lineItems }
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isAutoFilling = false,
                    autoFillError = "Couldn't generate suggestions: ${e.message ?: "unknown error"}"
                )
            }
        }
    }

    fun onSaveInvoice(asDraft: Boolean) {
        val state = _uiState.value
        if (!state.canSave) return

        _uiState.value = state.copy(isSaving = true, saveError = null)

        viewModelScope.launch {
            try {
                val validLineItems = state.lineItems.filter {
                    it.description.isNotBlank() && it.unitPrice > 0.0
                }

                val invoiceItems = validLineItems.map {
                    InvoiceItem(
                        id = UUID.randomUUID().toString(),
                        description = it.description,
                        quantity = it.quantity,
                        unitPrice = it.unitPrice,
                        discountType = it.discountType,
                        discountValue = it.discountValue,
                        taxRatePercent = it.taxRatePercent
                    )
                }

                val subtotal = validLineItems.sumOf { it.lineSubtotal }
                val discountTotal = validLineItems.sumOf { it.lineDiscountAmount }
                val taxTotal = validLineItems.sumOf { it.lineTaxAmount }
                val total = validLineItems.sumOf { it.lineTotal }

                val invoice = Invoice(
                    id = UUID.randomUUID().toString(),
                    invoiceNumber = state.invoiceNumber,
                    clientId = state.selectedClientId!!,
                    status = if (asDraft) InvoiceStatus.DRAFT else InvoiceStatus.SENT,
                    issueDateMillis = state.issueDateMillis,
                    dueDateMillis = state.dueDateMillis,
                    items = invoiceItems,
                    notes = state.notes,
                    subtotal = subtotal,
                    discountTotal = discountTotal,
                    taxTotal = taxTotal,
                    total = total,
                    amountPaid = 0.0,
                    currencyCode = state.currencyCode
                )

                invoiceDao.insertInvoice(invoice)

                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    savedInvoiceId = invoice.id
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    saveError = "Couldn't save invoice: ${e.message ?: "unknown error"}"
                )
            }
        }
    }

    private fun Double.toPlainString(): String =
        if (this == this.toLong().toDouble()) this.toLong().toString() else this.toString()
}

// ─────────────────────────────────────────────────────────────────────────
// Screen
// ─────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateInvoiceScreen(
    onNavigateBack: () -> Unit,
    onInvoiceSaved: (String) -> Unit,
    viewModel: CreateInvoiceViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.savedInvoiceId) {
        uiState.savedInvoiceId?.let { onInvoiceSaved(it) }
    }

    LaunchedEffect(uiState.autoFillError) {
        uiState.autoFillError?.let { snackbarHostState.showSnackbar(it) }
    }

    LaunchedEffect(uiState.saveError) {
        uiState.saveError?.let { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.invoiceNumber.ifBlank { "New Invoice" }) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            CreateInvoiceBottomBar(
                canSave = uiState.canSave,
                isSaving = uiState.isSaving,
                onSaveDraft = { viewModel.onSaveInvoice(asDraft = true) },
                onSaveAndSend = { viewModel.onSaveInvoice(asDraft = false) }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = paddingValues.calculateTopPadding() + 16.dp,
                bottom = paddingValues.calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                ClientPickerField(
                    clients = uiState.clients,
                    selectedClientName = uiState.selectedClientName,
                    onClientSelected = viewModel::onClientSelected
                )
            }

            item {
                Button(
                    onClick = viewModel::onAutoFillFromHistory,
                    enabled = uiState.canAutoFill,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    if (uiState.isAutoFilling) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp).width(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Generating suggestions…")
                    } else {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Auto-fill from client history")
                    }
                }
            }

            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    DateField(
                        modifier = Modifier.weight(1f),
                        label = "Issue date",
                        dateMillis = uiState.issueDateMillis,
                        onDateSelected = viewModel::onIssueDateChanged
                    )
                    DateField(
                        modifier = Modifier.weight(1f),
                        label = "Due date",
                        dateMillis = uiState.dueDateMillis,
                        onDateSelected = viewModel::onDueDateChanged
                    )
                }
            }

            item {
                Text("Line Items", style = MaterialTheme.typography.titleMedium)
            }

            items(uiState.lineItems, key = { it.localId }) { lineItem ->
                LineItemCard(
                    item = lineItem,
                    currencyCode = uiState.currencyCode,
                    canRemove = uiState.lineItems.size > 1,
                    onChanged = viewModel::onLineItemChanged,
                    onRemove = { viewModel.onRemoveLineItem(lineItem.localId) }
                )
            }

            item {
                OutlinedButton(
                    onClick = viewModel::onAddLineItem,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("+ Add line item")
                }
            }

            item {
                OutlinedTextField(
                    value = uiState.notes,
                    onValueChange = viewModel::onNotesChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Notes (optional)") },
                    minLines = 2,
                    maxLines = 4
                )
            }

            item {
                InvoiceSummaryCard(
                    subtotal = uiState.subtotal,
                    discountTotal = uiState.discountTotal,
                    taxTotal = uiState.taxTotal,
                    total = uiState.total,
                    currencyCode = uiState.currencyCode
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClientPickerField(
    clients: List<ClientPickerItem>,
    selectedClientName: String?,
    onClientSelected: (ClientPickerItem) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selectedClientName.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text("Client") },
            placeholder = { Text("Select a client") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            if (clients.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No clients yet — add one first") },
                    onClick = { expanded = false }
                )
            } else {
                clients.forEach { client ->
                    DropdownMenuItem(
                        text = { Text(client.name) },
                        onClick = {
                            onClientSelected(client)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(
    modifier: Modifier = Modifier,
    label: String,
    dateMillis: Long,
    onDateSelected: (Long) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    val formatter = remember { DateTimeFormatter.ofPattern("d MMM yyyy") }
    val formatted = remember(dateMillis) {
        Instant.ofEpochMilli(dateMillis).atZone(ZoneId.systemDefault()).toLocalDate().format(formatter)
    }

    OutlinedTextField(
        value = formatted,
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        trailingIcon = {
            Icon(Icons.Default.CalendarMonth, contentDescription = "Choose date")
        },
        modifier = modifier.clickable { showDialog = true },
        colors = OutlinedTextFieldDefaults.colors()
    )

    if (showDialog) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = dateMillis)
        DatePickerDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let(onDateSelected)
                    showDialog = false
                }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
private fun LineItemCard(
    item: InvoiceLineItemInput,
    currencyCode: String,
    canRemove: Boolean,
    onChanged: (InvoiceLineItemInput) -> Unit,
    onRemove: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(0.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            OutlinedTextField(
                value = item.description,
                onValueChange = { onChanged(item.copy(description = it)) },
                modifier = Modifier.weight(1f),
                label = { Text("Description") },
                singleLine = true
            )
            if (canRemove) {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove line item")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = item.quantityText,
                onValueChange = { onChanged(item.copy(quantityText = it)) },
                modifier = Modifier.weight(1f),
                label = { Text("Qty") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Decimal
                )
            )
            OutlinedTextField(
                value = item.unitPriceText,
                onValueChange = { onChanged(item.copy(unitPriceText = it)) },
                modifier = Modifier.weight(1f),
                label = { Text("Unit price") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Decimal
                )
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = item.discountValueText,
                onValueChange = { onChanged(item.copy(discountValueText = it)) },
                modifier = Modifier.weight(1f),
                label = {
                    Text(if (item.discountType == DiscountType.PERCENTAGE) "Discount %" else "Discount")
                },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Decimal
                )
            )
            OutlinedTextField(
                value = item.taxRatePercentText,
                onValueChange = { onChanged(item.copy(taxRatePercentText = it)) },
                modifier = Modifier.weight(1f),
                label = { Text("Tax %") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Decimal
                )
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Text(
                text = "Line total: ${Formatters.formatCurrency(item.lineTotal, currencyCode)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(top = 12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

@Composable
private fun InvoiceSummaryCard(
    subtotal: Double,
    discountTotal: Double,
    taxTotal: Double,
    total: Double,
    currencyCode: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        SummaryLine("Subtotal", Formatters.formatCurrency(subtotal, currencyCode))
        SummaryLine("Discount", "-${Formatters.formatCurrency(discountTotal, currencyCode)}")
        SummaryLine("Tax", Formatters.formatCurrency(taxTotal, currencyCode))
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Total", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                Formatters.formatCurrency(total, currencyCode),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun SummaryLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CreateInvoiceBottomBar(
    canSave: Boolean,
    isSaving: Boolean,
    onSaveDraft: () -> Unit,
    onSaveAndSend: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(
            onClick = onSaveDraft,
            enabled = canSave,
            modifier = Modifier.weight(1f)
        ) {
            Text("Save Draft")
        }
        Button(
            onClick = onSaveAndSend,
            enabled = canSave,
            modifier = Modifier.weight(1f)
        ) {
            if (isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.height(18.dp).width(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text("Save & Send")
            }
        }
    }
}