package com.benjamin.factura.util


import android.content.Context
import android.graphics.BitmapFactory
import com.benjamin.factura.data.model.Client
import com.benjamin.factura.data.model.BusinessProfile
import com.benjamin.factura.data.model.DiscountType
import com.benjamin.factura.data.model.Invoice
import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.colors.DeviceRgb
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.borders.Border
import com.itextpdf.layout.element.Cell
import com.itextpdf.layout.element.Image
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.element.Table
import com.itextpdf.layout.properties.HorizontalAlignment
import com.itextpdf.layout.properties.TextAlignment
import com.itextpdf.layout.properties.UnitValue
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates a formatted invoice PDF using iText7 (Android-native artifact).
 * Files are written to the app's cache dir and shared via FileProvider by the caller.
 */
interface PdfInvoiceGenerator {
    suspend fun generateInvoicePdf(
        invoice: Invoice,
        client: Client,
        businessProfile: BusinessProfile
    ): File
}

@Singleton
class PdfInvoiceGeneratorImpl @Inject constructor(
    @param:ApplicationContext private val context: Context
) : PdfInvoiceGenerator {

    // Brand primary #1D6A72
    private val brandColor = DeviceRgb(0x1D, 0x6A, 0x72)
    private val lightGray = DeviceRgb(0xF2, 0xF2, 0xF2)
    private val mutedGray = DeviceRgb(0x66, 0x66, 0x66)
    private val dateFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

    override suspend fun generateInvoicePdf(
        invoice: Invoice,
        client: Client,
        businessProfile: BusinessProfile
    ): File = withContext(Dispatchers.IO) {
        val outputDir = File(context.cacheDir, "pdfs").apply { mkdirs() }
        val outputFile = File(outputDir, "invoice_${invoice.invoiceNumber}.pdf")

        PdfWriter(FileOutputStream(outputFile)).use { writer ->
            PdfDocument(writer).use { pdfDoc ->
                Document(pdfDoc, PageSize.A4).use { document ->
                    document.setMargins(36f, 36f, 36f, 36f)

                    writeHeader(document, invoice, businessProfile)
                    writeBillTo(document, client)
                    writeLineItemsTable(document, invoice)
                    writeSummary(document, invoice)
                    writeFooter(document, businessProfile)
                }
            }
        }

        outputFile
    }

    private fun writeHeader(document: Document, invoice: Invoice, businessProfile: BusinessProfile) {
        val headerTable = Table(UnitValue.createPercentArray(floatArrayOf(1f, 1f)))
            .useAllAvailableWidth()
            .setBorder(Border.NO_BORDER)

        val leftCell = Cell().setBorder(Border.NO_BORDER)
        businessProfile.logoUrl
            ?.takeIf { it.isNotBlank() }
            ?.let { path -> loadLogoImage(path) }
            ?.let { image ->
                image.scaleToFit(120f, 60f)
                leftCell.add(image)
            }
        leftCell.add(
            Paragraph(businessProfile.businessName.ifBlank { "Your Business" })
                .setBold()
                .setFontSize(16f)
                .setMarginTop(4f)
        )
        val fullAddress = listOf(
            businessProfile.addressLine1,
            businessProfile.addressLine2,
            businessProfile.city,
            businessProfile.country
        ).filter { it.isNotBlank() }.joinToString(", ")

        if (fullAddress.isNotBlank()) {
            leftCell.add(Paragraph(fullAddress).setFontSize(9f).setFontColor(mutedGray))
        }
        if (businessProfile.taxNumber.isNotBlank()) {
            leftCell.add(
                Paragraph("Tax PIN: ${businessProfile.taxNumber}")
                    .setFontSize(9f)
                    .setFontColor(mutedGray)
            )
        }
        headerTable.addCell(leftCell)

        val rightCell = Cell().setBorder(Border.NO_BORDER).setTextAlignment(TextAlignment.RIGHT)
        rightCell.add(
            Paragraph("INVOICE")
                .setBold()
                .setFontSize(20f)
                .setFontColor(brandColor)
                .setTextAlignment(TextAlignment.RIGHT)
        )
        rightCell.add(
            Paragraph(invoice.invoiceNumber)
                .setFontSize(11f)
                .setTextAlignment(TextAlignment.RIGHT)
        )
        rightCell.add(
            Paragraph("Issued: ${invoice.issueDateMillis.toFormattedDate()}")
                .setFontSize(9f)
                .setFontColor(mutedGray)
                .setTextAlignment(TextAlignment.RIGHT)
        )
        rightCell.add(
            Paragraph("Due: ${invoice.dueDateMillis.toFormattedDate()}")
                .setFontSize(9f)
                .setFontColor(mutedGray)
                .setTextAlignment(TextAlignment.RIGHT)
        )
        headerTable.addCell(rightCell)

        document.add(headerTable)
        document.add(Paragraph("\n"))
    }

    private fun loadLogoImage(path: String): Image? {
        return try {
            val file = File(path)
            if (!file.exists()) return null
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
            val stream = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
            Image(ImageDataFactory.create(stream.toByteArray()))
        } catch (e: Exception) {
            null
        }
    }

    private fun writeBillTo(document: Document, client: Client) {
        document.add(
            Paragraph("Bill To")
                .setBold()
                .setFontSize(10f)
                .setFontColor(mutedGray)
                .setMarginBottom(2f)
        )
        document.add(Paragraph(client.name).setBold().setFontSize(12f))
        if (client.phone.isNotBlank()) {
            document.add(Paragraph(client.phone).setFontSize(10f).setFontColor(mutedGray))
        }
        if (client.email.isNotBlank()) {
            document.add(Paragraph(client.email).setFontSize(10f).setFontColor(mutedGray))
        }
        document.add(Paragraph("\n"))
    }

    private fun writeLineItemsTable(document: Document, invoice: Invoice) {
        val table = Table(UnitValue.createPercentArray(floatArrayOf(3.2f, 1f, 1.3f, 1f, 1f, 1.3f)))
            .useAllAvailableWidth()

        listOf("Description", "Qty", "Unit Price", "Discount", "Tax", "Total").forEach { header ->
            table.addHeaderCell(
                Cell()
                    .add(Paragraph(header).setBold().setFontSize(9f).setFontColor(DeviceRgb.WHITE))
                    .setBackgroundColor(brandColor)
                    .setBorder(Border.NO_BORDER)
                    .setPadding(6f)
            )
        }

        invoice.items.forEach { item ->
            val lineSubtotal = item.quantity * item.unitPrice
            val discountAmount = when (item.discountType) {
                DiscountType.PERCENTAGE -> lineSubtotal * (item.discountValue / 100.0)
                DiscountType.FIXED_AMOUNT -> item.discountValue
            }.coerceIn(0.0, lineSubtotal.coerceAtLeast(0.0))
            val taxableAmount = (lineSubtotal - discountAmount).coerceAtLeast(0.0)
            val taxAmount = taxableAmount * (item.taxRatePercent / 100.0)
            val lineTotal = taxableAmount + taxAmount

            table.addCell(bodyCell(item.description, TextAlignment.LEFT))
            table.addCell(bodyCell(formatQuantity(item.quantity), TextAlignment.CENTER))
            table.addCell(bodyCell(Formatters.formatCurrency(item.unitPrice, invoice.currencyCode), TextAlignment.RIGHT))
            table.addCell(bodyCell(Formatters.formatCurrency(discountAmount, invoice.currencyCode), TextAlignment.RIGHT))
            table.addCell(bodyCell(Formatters.formatCurrency(taxAmount, invoice.currencyCode), TextAlignment.RIGHT))
            table.addCell(bodyCell(Formatters.formatCurrency(lineTotal, invoice.currencyCode), TextAlignment.RIGHT))
        }

        document.add(table)
        document.add(Paragraph("\n"))
    }

    private fun bodyCell(text: String, alignment: TextAlignment): Cell =
        Cell()
            .add(Paragraph(text).setFontSize(9f))
            .setTextAlignment(alignment)
            .setBorder(Border.NO_BORDER)
            .setBorderBottom(com.itextpdf.layout.borders.SolidBorder(lightGray, 0.5f))
            .setPadding(6f)

    private fun writeSummary(document: Document, invoice: Invoice) {
        val summaryTable = Table(UnitValue.createPercentArray(floatArrayOf(1f, 1f)))
            .setWidth(UnitValue.createPercentValue(45f))
            .setHorizontalAlignment(HorizontalAlignment.RIGHT)

        summaryRow(summaryTable, "Subtotal", Formatters.formatCurrency(invoice.subtotal, invoice.currencyCode))
        summaryRow(summaryTable, "Discount", "-${Formatters.formatCurrency(invoice.discountTotal, invoice.currencyCode)}")
        summaryRow(summaryTable, "Tax", Formatters.formatCurrency(invoice.taxTotal, invoice.currencyCode))
        summaryRow(
            summaryTable,
            "Total",
            Formatters.formatCurrency(invoice.total, invoice.currencyCode),
            bold = true
        )
        summaryRow(summaryTable, "Amount Paid", Formatters.formatCurrency(invoice.amountPaid, invoice.currencyCode))
        summaryRow(
            summaryTable,
            "Balance Due",
            Formatters.formatCurrency(invoice.balanceDue(), invoice.currencyCode),
            bold = true,
            color = brandColor
        )

        document.add(summaryTable)
        document.add(Paragraph("\n"))
    }

    private fun summaryRow(
        table: Table,
        label: String,
        value: String,
        bold: Boolean = false,
        color: DeviceRgb? = null
    ) {
        val labelParagraph = Paragraph(label).setFontSize(if (bold) 11f else 9f)
        val valueParagraph = Paragraph(value).setFontSize(if (bold) 11f else 9f)
        if (bold) {
            labelParagraph.setBold()
            valueParagraph.setBold()
        }
        color?.let {
            labelParagraph.setFontColor(it)
            valueParagraph.setFontColor(it)
        }

        table.addCell(
            Cell().add(labelParagraph).setBorder(Border.NO_BORDER).setPadding(4f)
        )
        table.addCell(
            Cell().add(valueParagraph)
                .setTextAlignment(TextAlignment.RIGHT)
                .setBorder(Border.NO_BORDER)
                .setPadding(4f)
        )
    }

    private fun writeFooter(document: Document, businessProfile: BusinessProfile) {
        document.add(
            Paragraph("Payment Details")
                .setBold()
                .setFontSize(10f)
                .setFontColor(mutedGray)
                .setMarginBottom(2f)
        )
        if (businessProfile.bankName.isNotBlank()) {
            document.add(Paragraph("Bank: ${businessProfile.bankName}").setFontSize(9f))
        }
        if (businessProfile.bankAccountName.isNotBlank()) {
            document.add(Paragraph("Account name: ${businessProfile.bankAccountName}").setFontSize(9f))
        }
        if (businessProfile.bankAccountNumber.isNotBlank()) {
            document.add(Paragraph("Account number: ${businessProfile.bankAccountNumber}").setFontSize(9f))
        }
        document.add(
            Paragraph("\nThank you for your business!")
                .setFontSize(9f)
                .setFontColor(mutedGray)
                .setTextAlignment(TextAlignment.CENTER)
                .setMarginTop(12f)
        )
    }

    private fun formatQuantity(quantity: Double): String =
        if (quantity == quantity.toLong().toDouble()) quantity.toLong().toString() else quantity.toString()

    private fun Long.toFormattedDate(): String =
        Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate().format(dateFormatter)
}