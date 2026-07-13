package com.benjamin.factura.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.benjamin.factura.data.model.AppUser
import com.benjamin.factura.data.model.BusinessProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DataStore-backed extension property for [Context]. Kept top-level so exactly one
 * DataStore<Preferences> instance is created per process, as required by DataStore.
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "factura_preferences"
)

// -----------------------------------------------------------------------------
// Map<String, Any?> <-> JSON string helpers
// -----------------------------------------------------------------------------
// AppUser and BusinessProfile already define toMap()/fromMap() round-trips built
// for Firestore's Map<String, Any?> shape (see Models.kt). Rather than serializing
// the data classes directly with a reflection-based library, DataStore caching
// reuses those exact same toMap()/fromMap() methods - the on-disk cache and the
// Firestore document are always described by identical logic. org.json is part of
// the Android SDK, so no extra dependency is needed for the Map <-> JSON step.

private fun Map<String, Any?>.toJsonString(): String {
    val json = JSONObject()
    for ((key, value) in this) {
        json.put(key, value ?: JSONObject.NULL)
    }
    return json.toString()
}

private fun String.toStringKeyedMap(): Map<String, Any?> {
    val json = JSONObject(this)
    val map = mutableMapOf<String, Any?>()
    val keys = json.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val value = json.get(key)
        map[key] = if (value == JSONObject.NULL) null else value
    }
    return map
}

/**
 * Wraps Jetpack DataStore to persist small, frequently-read pieces of app state:
 * the cached signed-in [AppUser], the cached [BusinessProfile] (so the app can render
 * business info offline before Firestore syncs), the onboarding-complete flag, and
 * user-configurable defaults (currency, tax rate, invoice numbering prefix, payment terms).
 *
 * [AppUser] and [BusinessProfile] are cached here as JSON built from their own
 * toMap()/fromMap() methods, rather than in Room, since they are single-row,
 * single-user settings-like data rather than queryable collections - DataStore is
 * the more appropriate tool for that shape of data.
 *
 * This class does not talk to Firestore or Room directly; FirebaseRepository and the
 * "Via Appia" sync layer are responsible for keeping the cached JSON blobs here up to date.
 */
@Singleton
class PreferencesManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private object Keys {
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        val DEFAULT_CURRENCY = stringPreferencesKey("default_currency")
        val DEFAULT_TAX_RATE = doublePreferencesKey("default_tax_rate")
        val INVOICE_NUMBER_PREFIX = stringPreferencesKey("invoice_number_prefix")
        val PAYMENT_TERMS_DAYS = intPreferencesKey("payment_terms_days")
        val CACHED_APP_USER = stringPreferencesKey("cached_app_user")
        val CACHED_BUSINESS_PROFILE = stringPreferencesKey("cached_business_profile")
        val LAST_SYNCED_AT = stringPreferencesKey("last_synced_at")
    }

    companion object {
        const val DEFAULT_CURRENCY_CODE = "KES"
        const val DEFAULT_TAX_RATE_PERCENT = 16.0 // Kenyan standard VAT, matches BusinessProfile's default
        const val DEFAULT_INVOICE_PREFIX = "INV-" // matches BusinessProfile's default
        const val DEFAULT_PAYMENT_TERMS_DAYS = 14 // matches BusinessProfile's default
    }

    // ---------------------------------------------------------------------
    // Onboarding
    // ---------------------------------------------------------------------

    val isOnboardingComplete: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.ONBOARDING_COMPLETE] ?: false
    }

    suspend fun setOnboardingComplete(complete: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ONBOARDING_COMPLETE] = complete
        }
    }

    // ---------------------------------------------------------------------
    // Default currency / tax / invoice numbering / payment terms
    // ---------------------------------------------------------------------

    val defaultCurrency: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.DEFAULT_CURRENCY] ?: DEFAULT_CURRENCY_CODE
    }

    suspend fun setDefaultCurrency(currencyCode: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DEFAULT_CURRENCY] = currencyCode
        }
    }

    val defaultTaxRate: Flow<Double> = context.dataStore.data.map { prefs ->
        prefs[Keys.DEFAULT_TAX_RATE] ?: DEFAULT_TAX_RATE_PERCENT
    }

    suspend fun setDefaultTaxRate(taxRatePercent: Double) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DEFAULT_TAX_RATE] = taxRatePercent
        }
    }

    val invoiceNumberPrefix: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.INVOICE_NUMBER_PREFIX] ?: DEFAULT_INVOICE_PREFIX
    }

    suspend fun setInvoiceNumberPrefix(prefix: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.INVOICE_NUMBER_PREFIX] = prefix
        }
    }

    val defaultPaymentTermsDays: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.PAYMENT_TERMS_DAYS] ?: DEFAULT_PAYMENT_TERMS_DAYS
    }

    suspend fun setDefaultPaymentTermsDays(days: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.PAYMENT_TERMS_DAYS] = days
        }
    }

    // ---------------------------------------------------------------------
    // Cached AppUser
    // ---------------------------------------------------------------------

    val cachedAppUser: Flow<AppUser?> = context.dataStore.data.map { prefs ->
        prefs[Keys.CACHED_APP_USER]?.let { json ->
            runCatching { AppUser.fromMap(json.toStringKeyedMap()) }.getOrNull()
        }
    }

    suspend fun getCachedAppUserOnce(): AppUser? = cachedAppUser.first()

    suspend fun setCachedAppUser(user: AppUser) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CACHED_APP_USER] = user.toMap().toJsonString()
        }
    }

    suspend fun clearCachedAppUser() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.CACHED_APP_USER)
        }
    }

    // ---------------------------------------------------------------------
    // Cached BusinessProfile
    // ---------------------------------------------------------------------

    val cachedBusinessProfile: Flow<BusinessProfile?> = context.dataStore.data.map { prefs ->
        prefs[Keys.CACHED_BUSINESS_PROFILE]?.let { json ->
            runCatching {
                val map = json.toStringKeyedMap()
                // BusinessProfile.fromMap takes the document id separately (mirroring
                // how it's read off a Firestore DocumentSnapshot); toMap() also writes
                // "id" into the map itself, so it's pulled back out here for that param.
                BusinessProfile.fromMap(map, map["id"] as? String ?: "")
            }.getOrNull()
        }
    }

    /**
     * Canonical accessor used by screens/ViewModels (e.g. to read [BusinessProfile.currencyCode]
     * for [com.benjamin.factura.util.Formatters.formatCurrency]). This is just a descriptively
     * named alias over [cachedBusinessProfile] - both point at the exact same underlying
     * DataStore-backed Flow, so there is no duplicate mapping/parsing work and no risk of the
     * two ever drifting out of sync.
     */
    val businessProfileFlow: Flow<BusinessProfile?> = cachedBusinessProfile

    suspend fun getCachedBusinessProfileOnce(): BusinessProfile? = cachedBusinessProfile.first()

    suspend fun setCachedBusinessProfile(profile: BusinessProfile) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CACHED_BUSINESS_PROFILE] = profile.toMap().toJsonString()
        }
    }

    suspend fun clearCachedBusinessProfile() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.CACHED_BUSINESS_PROFILE)
        }
    }

    // ---------------------------------------------------------------------
    // Sync bookkeeping (read by "Via Appia" to decide when to reconcile)
    // ---------------------------------------------------------------------

    val lastSyncedAt: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[Keys.LAST_SYNCED_AT]
    }

    suspend fun setLastSyncedAt(isoTimestamp: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LAST_SYNCED_AT] = isoTimestamp
        }
    }

    // ---------------------------------------------------------------------
    // Full sign-out / reset
    // ---------------------------------------------------------------------

    /**
     * Clears every stored preference, including cached user/business data and the
     * onboarding flag. Called on sign-out so the next launch routes back through
     * SplashScreen -> OnboardingScreen for a clean slate.
     */
    suspend fun clearAll() {
        context.dataStore.edit { prefs ->
            prefs.clear()
        }
    }
}