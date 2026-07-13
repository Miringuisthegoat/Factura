package com.benjamin.factura.ui.screens


import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.benjamin.factura.data.local.dao.ClientDao
import com.benjamin.factura.data.local.dao.InvoiceDao
import com.benjamin.factura.data.local.dao.PaymentDao
import com.benjamin.factura.data.model.Client
import com.benjamin.factura.data.model.Invoice
import com.benjamin.factura.data.model.InvoiceItem
import com.benjamin.factura.data.model.InvoiceStatus
import com.benjamin.factura.data.model.Payment
import com.benjamin.factura.data.local.PreferencesManager
import com.benjamin.factura.ui.components.shimmerEffect
import com.benjamin.factura.util.Formatters
import com.benjamin.factura.util.PdfInvoiceGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────
// UI models
// ─────────────────────────────────────────────────────────────────────────

data class InvoiceLineItemUi(
    val description: String,
    val quantity: Double,
    val unitPrice: Double,
    val discountAmount: Double,
    val taxAmount: Double,
    val lineTotal: Double
)

data class PaymentHistoryItemUi(
    val id: String,
    val amount: Double,
    val method: String,
    val dateMillis: Long
)

data class InvoiceDetailUi(
    val id: String,
    val invoiceNumber: String,
    val status: InvoiceStatus,
    val issueDateMillis: Long,
    val dueDateMillis: Long,
    val clientName: String,
    val clientPhone: String,
    val clientEmail: String,
    val lineItems: List<InvoiceLineItemUi>,
    val notes: String,
    val subtotal: Double,
    val discountTotal: Double,
    val taxTotal: Double,
    val total: Double,
    val amountPaid: Double,
    val balanceDue: Double
)

data class InvoiceDetailUiState(
    val isLoading: Boolean = true,
    val currencyCode: String = "KES",
    val invoice: InvoiceDetailUi? = null,
    val payments: List<PaymentHistoryItemUi> = emptyList(),
    val isGeneratingPdf: Boolean = false,
    val pdfFile: File? = null,
    val isDuplicating: Boolean = false,
    val duplicatedInvoiceId: String? = null,
    val isDeleting: Boolean = false,
    val isDeleted: Boolean = false,
    val errorMessage: String? = null,
    val notFound: Boolean = false
)

// ─────────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────────

@HiltViewModel
class InvoiceDetailViewModel @Inject constructor(
    private val invoiceDao: InvoiceDao,
    private val clientDao: ClientDao,
    private val paymentDao: PaymentDao,
    private val preferencesManager: PreferencesManager,
    private val pdfInvoiceGenerator: PdfInvoiceGenerator,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val invoiceId: String = checkNotNull(savedStateHandle["invoiceId"])

    private val _uiState = MutableStateFlow(InvoiceDetailUiState())
    val uiState: StateFlow<InvoiceDetailUiState> = _uiState

    init {
        viewModelScope.launch {
            combine(
                invoiceDao.getInvoiceById(invoiceId),
                clientDao.getAllClients(),
                paymentDao.getPaymentsByInvoice(invoiceId),
                preferencesManager.businessProfileFlow
            ) { invoice, clients, payments, businessProfile ->
                if (invoice == null) {
                    InvoiceDetailUiState(isLoading = false, notFound = true)
                } else {
                    val client = clients.firstOrNull { it.id == invoice.clientId }
                    buildUiState(invoice, client, payments, businessProfile?.defaultCurrency ?: "KES")
                }
            }
                .flowOn(Dispatchers.Default)
                .collect { newState ->
                    // Preserve transient UI flags (dialogs/in-flight actions) across
                    // reactive data refreshes triggered by Room.
                    _uiState.value = newState.copy(
                        isGeneratingPdf = _uiState.value.isGeneratingPdf,
                        isDuplicating = _uiState.value.isDuplicating,
                        isDeleting = _uiState.value.isDeleting
                    )
                }
        }
    }

    private fun buildUiState(
        invoice: Invoice,
        client: Client?,
        payments: List<Payment>,
        currencyCode: String
    ): InvoiceDetailUiState {
        val lineItems = invoice.items.map { item ->
            val lineSubtotal = item.quantity * item.unitPrice
            val discountAmount = when (item.discountType) {
                com.benjamin.factura.data.model.DiscountType.PERCENTAGE ->
                    lineSubtotal * (item.discountValue / 100.0)
                com.benjamin.factura.data.model.DiscountType.FIXED_AMOUNT -> item.discountValue
            }.coerceIn(0.0, lineSubtotal.coerceAtLeast(0.0))
            val taxableAmount = (lineSubtotal - discountAmount).coerceAtLeast(0.0)
            val taxAmount = taxableAmount * (item.taxRatePercent / 100.0)

            InvoiceLineItemUi(
                description = item.description,
                quantity = item.quantity,
                unitPrice = item.unitPrice,
                discountAmount = discountAmount,
                taxAmount = taxAmount,
                lineTotal = taxableAmount + taxAmount
            )
        }

        val paymentHistory = payments
            .sortedByDescending { it.paidAtMillis }
            .map {
                PaymentHistoryItemUi(
                    id = it.id,
                    amount = it.amount,
                    method = it.method.name
                        .lowercase()
                        .split("_")
                        .joinToString(" ") { word -> word.replaceFirstChar(Char::uppercase) },
                    dateMillis = it.paidAtMillis
                )
            }

        return InvoiceDetailUiState(
            isLoading = false,
            currencyCode = currencyCode,
            invoice = InvoiceDetailUi(
                id = invoice.id,
                invoiceNumber = invoice.invoiceNumber,
                status = invoice.status,
                issueDateMillis = invoice.issueDateMillis,
                dueDateMillis = invoice.dueDateMillis,
                clientName = client?.name ?: "Unknown client",
                clientPhone = client?.phone.orEmpty(),
                clientEmail = client?.email.orEmpty(),
                lineItems = lineItems,
                notes = invoice.notes,
                subtotal = invoice.subtotal,
                discountTotal = invoice.discountTotal,
                taxTotal = invoice.taxTotal,
                total = invoice.total,
                amountPaid = invoice.amountPaid,
                balanceDue = invoice.balanceDue()
            ),
            payments = paymentHistory
        )
    }

    fun onGeneratePdf() {
        _uiState.value = _uiState.value.copy(isGeneratingPdf = true, errorMessage = null)

        viewModelScope.launch {
            try {
                val fullInvoice = invoiceDao.getInvoiceById(invoiceId).first()
                val client = fullInvoice?.let { inv ->
                    clientDao.getAllClients().first().firstOrNull { it.id == inv.clientId }
                }
                val businessProfile = preferencesManager.businessProfileFlow.first()

                if (fullInvoice == null || client == null || businessProfile == null) {
                    _uiState.value = _uiState.value.copy(
                        isGeneratingPdf = false,
                        errorMessage = "Missing invoice, client, or business profile data."
                    )
                    return@launch
                }

                val file = pdfInvoiceGenerator.generateInvoicePdf(
                    invoice = fullInvoice,
                    client = client,
                    businessProfile = businessProfile
                )

                _uiState.value = _uiState.value.copy(isGeneratingPdf = false, pdfFile = file)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isGeneratingPdf = false,
                    errorMessage = "Couldn't generate PDF: ${e.message ?: "unknown error"}"
                )
            }
        }
    }

    fun onPdfShared() {
        _uiState.value = _uiState.value.copy(pdfFile = null)
    }

    fun onDuplicateInvoice() {
        val invoice = _uiState.value.invoice ?: return
        _uiState.value = _uiState.value.copy(isDuplicating = true, errorMessage = null)

        viewModelScope.launch {
            try {
                val original = invoiceDao.getInvoiceById(invoiceId).first()
                if (original == null) {
                    _uiState.value = _uiState.value.copy(
                        isDuplicating = false,
                        errorMessage = "Original invoice no longer exists."
                    )
                    return@launch
                }

                val businessProfile = preferencesManager.businessProfileFlow.first()
                val prefix = businessProfile?.invoiceNumberPrefix ?: "INV-"
                val paymentTermsDays = businessProfile?.paymentTermsDays ?: 14
                val existingCount = invoiceDao.getInvoiceCount()
                val nextNumber = (existingCount + 1).toString().padStart(4, '0')
                val now = System.currentTimeMillis()

                val duplicated = original.copy(
                    id = UUID.randomUUID().toString(),
                    invoiceNumber = "$prefix$nextNumber",
                    status = InvoiceStatus.DRAFT,
                    issueDateMillis = now,
                    dueDateMillis = now + (paymentTermsDays.toLong() * 24 * 60 * 60 * 1000),
                    items = original.items.map { it.copy(id = UUID.randomUUID().toString()) },
                    amountPaid = 0.0
                )

                invoiceDao.insertInvoice(duplicated)

                _uiState.value = _uiState.value.copy(
                    isDuplicating = false,
                    duplicatedInvoiceId = duplicated.id
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isDuplicating = false,
                    errorMessage = "Couldn't duplicate invoice: ${e.message ?: "unknown error"}"
                )
            }
        }
    }

    fun onMarkAsSent() {
        val invoice = _uiState.value.invoice ?: return
        if (invoice.status != InvoiceStatus.DRAFT) return

        viewModelScope.launch {
            val current = invoiceDao.getInvoiceById(invoiceId).first() ?: return@launch
            invoiceDao.insertInvoice(current.copy(status = InvoiceStatus.SENT))
        }
    }

    fun onDeleteInvoice() {
        _uiState.value = _uiState.value.copy(isDeleting = true, errorMessage = null)

        viewModelScope.launch {
            try {
                val current = invoiceDao.getInvoiceById(invoiceId).first()
                if (current != null) {
                    invoiceDao.deleteInvoice(current)
                }
                _uiState.value = _uiState.value.copy(isDeleting = false, isDeleted = true)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isDeleting = false,
                    errorMessage = "Couldn't delete invoice: ${e.message ?: "unknown error"}"
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Screen
// ─────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvoiceDetailScreen(
    onNavigateBack: () -> Unit,
    onNavigateToEditInvoice: (String) -> Unit,
    onNavigateToRecordPayment: (String) -> Unit,
    onNavigateToDuplicatedInvoice: (String) -> Unit,
    viewModel: InvoiceDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.isDeleted) {
        if (uiState.isDeleted) onNavigateBack()
    }

    LaunchedEffect(uiState.duplicatedInvoiceId) {
        uiState.duplicatedInvoiceId?.let { onNavigateToDuplicatedInvoice(it) }
    }

    LaunchedEffect(uiState.pdfFile) {
        uiState.pdfFile?.let { file ->
            sharePdfFile(context, file)
            viewModel.onPdfShared()
        }
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.invoice?.invoiceNumber ?: "Invoice") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showOverflowMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More actions")
                    }
                    DropdownMenu(
                        expanded = showOverflowMenu,
                        onDismissRequest = { showOverflowMenu = false }
                    ) {
                        uiState.invoice?.let { invoice ->
                            if (invoice.status == InvoiceStatus.DRAFT) {
                                DropdownMenuItem(
                                    text = { Text("Edit") },
                                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        onNavigateToEditInvoice(invoice.id)
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Duplicate") },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                                onClick = {
                                    showOverflowMenu = false
                                    viewModel.onDuplicateInvoice()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                onClick = {
                                    showOverflowMenu = false
                                    showDeleteConfirm = true
                                }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            uiState.invoice?.let { invoice ->
                InvoiceDetailBottomBar(
                    invoice = invoice,
                    isGeneratingPdf = uiState.isGeneratingPdf,
                    onSharePdf = { viewModel.onGeneratePdf() },
                    onRecordPayment = { onNavigateToRecordPayment(invoice.id) },
                    onMarkAsSent = viewModel::onMarkAsSent
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        when {
            uiState.isLoading -> InvoiceDetailSkeleton(paddingValues)
            uiState.notFound -> InvoiceNotFoundState(paddingValues)
            uiState.invoice != null -> InvoiceDetailContent(
                invoice = uiState.invoice!!,
                payments = uiState.payments,
                currencyCode = uiState.currencyCode,
                paddingValues = paddingValues
            )
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete this invoice?") },
            text = { Text("This can't be undone. The invoice will be permanently removed.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.onDeleteInvoice()
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun sharePdfFile(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share invoice PDF"))
}

@Composable
private fun InvoiceDetailContent(
    invoice: InvoiceDetailUi,
    payments: List<PaymentHistoryItemUi>,
    currencyCode: String,
    paddingValues: PaddingValues
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = paddingValues.calculateTopPadding() + 16.dp,
            bottom = paddingValues.calculateBottomPadding() + 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { InvoiceHeaderCard(invoice) }
        item { BillToCard(invoice) }
        item { LineItemsCard(invoice, currencyCode) }
        item {
            InvoiceTotalsCard(
                subtotal = invoice.subtotal,
                discountTotal = invoice.discountTotal,
                taxTotal = invoice.taxTotal,
                total = invoice.total,
                amountPaid = invoice.amountPaid,
                balanceDue = invoice.balanceDue,
                currencyCode = currencyCode
            )
        }
        if (invoice.notes.isNotBlank()) {
            item { NotesCard(invoice.notes) }
        }
        if (payments.isNotEmpty()) {
            item {
                Text("Payment History", style = MaterialTheme.typography.titleMedium)
            }
            items(payments, key = { it.id }) { payment ->
                PaymentHistoryRow(payment, currencyCode)
            }
        }
    }
}

@Composable
private fun InvoiceHeaderCard(invoice: InvoiceDetailUi) {
    val formatter = remember { DateTimeFormatter.ofPattern("d MMM yyyy") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(invoice.invoiceNumber, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            StatusPill(status = invoice.status)
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            DateColumn(
                label = "Issue date",
                dateMillis = invoice.issueDateMillis,
                formatter = formatter
            )
            DateColumn(
                label = "Due date",
                dateMillis = invoice.dueDateMillis,
                formatter = formatter
            )
        }
    }
}

@Composable
private fun DateColumn(label: String, dateMillis: Long, formatter: DateTimeFormatter) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            Instant.ofEpochMilli(dateMillis).atZone(ZoneId.systemDefault()).toLocalDate().format(formatter),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun BillToCard(invoice: InvoiceDetailUi) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Text("Bill To", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(4.dp))
        Text(invoice.clientName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
        if (invoice.clientPhone.isNotBlank()) {
            Text(invoice.clientPhone, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (invoice.clientEmail.isNotBlank()) {
            Text(invoice.clientEmail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LineItemsCard(invoice: InvoiceDetailUi, currencyCode: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Text("Line Items", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(8.dp))
        invoice.lineItems.forEachIndexed { index, item ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = item.description,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = Formatters.formatCurrency(item.lineTotal, currencyCode),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(
                    text = "${formatQuantity(item.quantity)} × ${Formatters.formatCurrency(item.unitPrice, currencyCode)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (index != invoice.lineItems.lastIndex) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }
        }
    }
}

private fun formatQuantity(quantity: Double): String =
    if (quantity == quantity.toLong().toDouble()) quantity.toLong().toString() else quantity.toString()

@Composable
private fun InvoiceTotalsCard(
    subtotal: Double,
    discountTotal: Double,
    taxTotal: Double,
    total: Double,
    amountPaid: Double,
    balanceDue: Double,
    currencyCode: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        SummaryLine("Subtotal", Formatters.formatCurrency(subtotal, currencyCode))
        SummaryLine("Discount", "-${Formatters.formatCurrency(discountTotal, currencyCode)}")
        SummaryLine("Tax", Formatters.formatCurrency(taxTotal, currencyCode))
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Total", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                Formatters.formatCurrency(total, currencyCode),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        SummaryLine("Amount paid", Formatters.formatCurrency(amountPaid, currencyCode))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "Balance due",
                style = MaterialTheme.typography.titleSmall,
                color = if (balanceDue > 0.0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            Text(
                Formatters.formatCurrency(balanceDue, currencyCode),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (balanceDue > 0.0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun SummaryLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun NotesCard(notes: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Text("Notes", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(4.dp))
        Text(notes, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PaymentHistoryRow(payment: PaymentHistoryItemUi, currencyCode: String) {
    val formatter = remember { DateTimeFormatter.ofPattern("d MMM yyyy") }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(payment.method, style = MaterialTheme.typography.bodyMedium)
            Text(
                Instant.ofEpochMilli(payment.dateMillis).atZone(ZoneId.systemDefault()).toLocalDate().format(formatter),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            Formatters.formatCurrency(payment.amount, currencyCode),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun StatusPill(status: InvoiceStatus) {
    val (bg, fg) = when (status) {
        InvoiceStatus.DRAFT -> MaterialTheme.colorScheme.surface to MaterialTheme.colorScheme.onSurfaceVariant
        InvoiceStatus.SENT -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        InvoiceStatus.VIEWED -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        InvoiceStatus.PARTIAL -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        InvoiceStatus.PAID -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        InvoiceStatus.OVERDUE -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        InvoiceStatus.CANCELLED -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = status.name.lowercase().replaceFirstChar(Char::uppercase)

    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg, fontSize = 12.sp)
    }
}

@Composable
private fun InvoiceDetailBottomBar(
    invoice: InvoiceDetailUi,
    isGeneratingPdf: Boolean,
    onSharePdf: () -> Unit,
    onRecordPayment: () -> Unit,
    onMarkAsSent: () -> Unit
) {
    Column(modifier = Modifier.padding(16.dp)) {
        if (invoice.status == InvoiceStatus.DRAFT) {
            OutlinedButton(
                onClick = onMarkAsSent,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Mark as Sent")
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onSharePdf,
                enabled = !isGeneratingPdf,
                modifier = Modifier.weight(1f)
            ) {
                if (isGeneratingPdf) {
                    CircularProgressIndicator(modifier = Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Share PDF")
                }
            }
            if (invoice.balanceDue > 0.0) {
                Button(
                    onClick = onRecordPayment,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Payments, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Record Payment")
                }
            }
        }
    }
}

@Composable
private fun InvoiceNotFoundState(paddingValues: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = paddingValues.calculateTopPadding()),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "This invoice no longer exists.",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun InvoiceDetailSkeleton(paddingValues: PaddingValues) {
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .shimmerEffect(RoundedCornerShape(16.dp))
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(90.dp)
                .shimmerEffect(RoundedCornerShape(16.dp))
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .shimmerEffect(RoundedCornerShape(16.dp))
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .shimmerEffect(RoundedCornerShape(16.dp))
        )
    }
}