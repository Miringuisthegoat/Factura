package com.benjamin.factura


import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/*
 * Factura — FacturaApp.kt
 *
 * Hilt's required application-level entry point. Referenced from
 * AndroidManifest.xml via android:name=".FacturaApp" - add that attribute to
 * the <application> tag if it isn't there yet, or Hilt's generated component
 * never gets created and every @AndroidEntryPoint/@HiltViewModel in the app
 * will fail to inject at runtime.
 */
@HiltAndroidApp
class FacturaApp : Application()