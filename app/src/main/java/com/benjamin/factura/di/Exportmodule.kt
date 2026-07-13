package com.benjamin.factura.di


import com.benjamin.factura.util.PdfInvoiceGenerator
import com.benjamin.factura.util.PdfInvoiceGeneratorImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Bindings for export generators (PDF now; Excel and CSV join this module
 * once ExcelReportGenerator and CsvExporter are built in the export phase).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ExportModule {

    @Binds
    @Singleton
    abstract fun bindPdfInvoiceGenerator(
        impl: PdfInvoiceGeneratorImpl
    ): PdfInvoiceGenerator
}