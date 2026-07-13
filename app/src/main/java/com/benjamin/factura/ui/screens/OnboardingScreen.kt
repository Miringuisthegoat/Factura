package com.benjamin.factura.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.benjamin.factura.R
import com.benjamin.factura.data.local.PreferencesManager
import com.benjamin.factura.data.model.AppUser
import com.benjamin.factura.data.model.BusinessProfile
import com.benjamin.factura.data.remote.FirebaseRepository
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/*
 * Factura — ui/screens/OnboardingScreen.kt
 *
 * 3-step wizard: Welcome -> Business Profile Setup -> Create Account.
 * Steps are button-driven (no swipe) so step 2's validation can't be skipped
 * by a swipe gesture - AnimatedContent handles the step transition instead of
 * a HorizontalPager, since there's no need for pager-specific behavior here.
 *
 * No loading skeleton on this screen: nothing here is *content being fetched*,
 * so the account-creation submit uses a small in-button spinner instead,
 * consistent with the shimmer-for-content / spinner-for-actions split agreed
 * on for this project.
 *
 * All form state for all three steps lives in one OnboardingUiState rather
 * than being split per-step, since the final submit needs every field at once
 * to build both the AppUser and BusinessProfile documents together.
 */

private val stepTitles = listOf("Welcome", "Your Business", "Create Your Account")

private val currencyOptions = listOf("KES", "USD", "EUR", "GBP", "UGX", "TZS", "RWF")

// ---------------------------------------------------------------------------
// UI STATE
// ---------------------------------------------------------------------------

data class OnboardingUiState(
    val currentPage: Int = 0, // 0 = Welcome, 1 = Business Setup, 2 = Create Account

    // Business profile fields (step 2)
    val businessName: String = "",
    val logoUri: String? = null,
    val addressLine1: String = "",
    val addressLine2: String = "",
    val city: String = "",
    val taxNumber: String = "",
    val currencyCode: String = PreferencesManager.DEFAULT_CURRENCY_CODE,
    val bankName: String = "",
    val bankAccountName: String = "",
    val bankAccountNumber: String = "",
    val bankBranch: String = "",
    val mpesaPaybillNumber: String = "",
    val mpesaTillNumber: String = "",
    val businessNameError: String? = null,

    // Account fields (step 3)
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val emailError: String? = null,
    val passwordError: String? = null,

    // Submission state - button-level, not a content-loading state, so this
    // drives a small in-button spinner rather than a shimmer skeleton.
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null
)

// ---------------------------------------------------------------------------
// VIEWMODEL
// ---------------------------------------------------------------------------

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val firebaseRepository: FirebaseRepository,
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    /** One-time navigation event - a Channel (not a StateFlow) so it fires exactly
     *  once and isn't re-delivered on configuration change / recomposition. */
    private val _onboardingCompleteEvent = Channel<Unit>(Channel.BUFFERED)
    val onboardingCompleteEvent = _onboardingCompleteEvent.receiveAsFlow()

    init {
        // Seed the currency field from whatever's already cached, in case the
        // user backed out of onboarding once before (e.g. process death mid-flow).
        viewModelScope.launch {
            val cachedCurrency = preferencesManager.defaultCurrency.first()
            _uiState.value = _uiState.value.copy(currencyCode = cachedCurrency)
        }
    }

    // ---- Page navigation ----

    private fun goToPage(page: Int) {
        _uiState.value = _uiState.value.copy(currentPage = page, errorMessage = null)
    }

    fun onNextFromWelcome() = goToPage(1)

    /** Returns false (and surfaces a field error) if validation fails. */
    fun onNextFromBusinessSetup(): Boolean {
        val name = _uiState.value.businessName.trim()
        if (name.isBlank()) {
            _uiState.value = _uiState.value.copy(businessNameError = "Business name is required")
            return false
        }
        goToPage(2)
        return true
    }

    fun onBack() {
        val current = _uiState.value.currentPage
        if (current > 0) goToPage(current - 1)
    }

    // ---- Business setup field updates ----

    fun updateBusinessName(value: String) {
        _uiState.value = _uiState.value.copy(businessName = value, businessNameError = null)
    }

    fun updateLogoUri(value: String?) {
        _uiState.value = _uiState.value.copy(logoUri = value)
    }

    fun updateAddressLine1(value: String) {
        _uiState.value = _uiState.value.copy(addressLine1 = value)
    }

    fun updateAddressLine2(value: String) {
        _uiState.value = _uiState.value.copy(addressLine2 = value)
    }

    fun updateCity(value: String) {
        _uiState.value = _uiState.value.copy(city = value)
    }

    fun updateTaxNumber(value: String) {
        _uiState.value = _uiState.value.copy(taxNumber = value)
    }

    fun updateCurrencyCode(value: String) {
        _uiState.value = _uiState.value.copy(currencyCode = value)
    }

    fun updateBankName(value: String) {
        _uiState.value = _uiState.value.copy(bankName = value)
    }

    fun updateBankAccountName(value: String) {
        _uiState.value = _uiState.value.copy(bankAccountName = value)
    }

    fun updateBankAccountNumber(value: String) {
        _uiState.value = _uiState.value.copy(bankAccountNumber = value)
    }

    fun updateBankBranch(value: String) {
        _uiState.value = _uiState.value.copy(bankBranch = value)
    }

    fun updateMpesaPaybillNumber(value: String) {
        _uiState.value = _uiState.value.copy(mpesaPaybillNumber = value)
    }

    fun updateMpesaTillNumber(value: String) {
        _uiState.value = _uiState.value.copy(mpesaTillNumber = value)
    }

    // ---- Account fields ----

    fun updateEmail(value: String) {
        _uiState.value = _uiState.value.copy(email = value, emailError = null)
    }

    fun updatePassword(value: String) {
        _uiState.value = _uiState.value.copy(password = value, passwordError = null)
    }

    fun updateConfirmPassword(value: String) {
        _uiState.value = _uiState.value.copy(confirmPassword = value, passwordError = null)
    }

    // ---- Submission ----

    fun submitEmailSignUp() {
        val state = _uiState.value
        val email = state.email.trim()
        val password = state.password

        val emailError = if (email.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            "Enter a valid email address"
        } else null

        val passwordError = when {
            password.length < 8 -> "Password must be at least 8 characters"
            password != state.confirmPassword -> "Passwords don't match"
            else -> null
        }

        if (emailError != null || passwordError != null) {
            _uiState.value = state.copy(emailError = emailError, passwordError = passwordError)
            return
        }

        _uiState.value = state.copy(isSubmitting = true, errorMessage = null)
        viewModelScope.launch {
            firebaseRepository.signUpWithEmail(email, password).fold(
                onSuccess = { uid -> finishAccountSetup(uid, email) },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isSubmitting = false,
                        errorMessage = error.message ?: "Couldn't create your account. Please try again."
                    )
                }
            )
        }
    }

    /** [googleIdToken] is obtained by the screen via Credential Manager and handed in here. */
    fun submitGoogleSignIn(googleIdToken: String) {
        _uiState.value = _uiState.value.copy(isSubmitting = true, errorMessage = null)
        viewModelScope.launch {
            firebaseRepository.signInWithGoogleIdToken(googleIdToken).fold(
                onSuccess = { uid -> finishAccountSetup(uid, _uiState.value.email) },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isSubmitting = false,
                        errorMessage = error.message ?: "Google sign-in failed. Please try again."
                    )
                }
            )
        }
    }

    /** Called by the screen when the Credential Manager flow itself fails/is cancelled. */
    fun onGoogleSignInError(message: String) {
        _uiState.value = _uiState.value.copy(isSubmitting = false, errorMessage = message)
    }

    private suspend fun finishAccountSetup(uid: String, email: String) {
        val state = _uiState.value

        val businessProfile = BusinessProfile.new(
            ownerUid = uid,
            businessName = state.businessName.trim()
        ).copy(
            logoUrl = state.logoUri,
            addressLine1 = state.addressLine1.trim(),
            addressLine2 = state.addressLine2.trim(),
            city = state.city.trim(),
            taxNumber = state.taxNumber.trim(),
            defaultCurrency = state.currencyCode,
            bankName = state.bankName.trim(),
            bankAccountName = state.bankAccountName.trim(),
            bankAccountNumber = state.bankAccountNumber.trim(),
            bankBranch = state.bankBranch.trim(),
            mpesaPaybillNumber = state.mpesaPaybillNumber.trim(),
            mpesaTillNumber = state.mpesaTillNumber.trim()
        )

        val appUser = AppUser.new(
            uid = uid,
            email = email,
            displayName = state.businessName.trim(),
            businessId = businessProfile.id
        )

        val saveBusinessResult = firebaseRepository.saveBusinessProfile(businessProfile)
        val saveUserResult = firebaseRepository.createUserDocument(appUser)

        if (saveBusinessResult.isFailure || saveUserResult.isFailure) {
            _uiState.value = _uiState.value.copy(
                isSubmitting = false,
                errorMessage = "Your account was created, but saving your business profile failed. Please try again."
            )
            return
        }

        // Cache everything locally so Dashboard can render instantly offline,
        // and so defaults (currency/tax/invoice prefix/payment terms) are
        // available to the rest of the app without a Firestore round-trip.
        preferencesManager.setCachedBusinessProfile(businessProfile)
        preferencesManager.setCachedAppUser(appUser)
        preferencesManager.setDefaultCurrency(businessProfile.defaultCurrency)
        preferencesManager.setDefaultTaxRate(businessProfile.defaultTaxRatePercent)
        preferencesManager.setInvoiceNumberPrefix(businessProfile.invoiceNumberPrefix)
        preferencesManager.setDefaultPaymentTermsDays(businessProfile.paymentTermsDays)
        preferencesManager.setOnboardingComplete(true)

        _uiState.value = _uiState.value.copy(isSubmitting = false)
        _onboardingCompleteEvent.trySend(Unit)
    }
}

// ---------------------------------------------------------------------------
// SCREEN
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onOnboardingComplete: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.onboardingCompleteEvent.collect {
            onOnboardingComplete()
        }
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp)
        ) {
            StepIndicator(
                currentStep = uiState.currentPage,
                totalSteps = stepTitles.size,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp, bottom = 8.dp)
            )

            Text(
                text = stepTitles[uiState.currentPage],
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            uiState.errorMessage?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = uiState.currentPage,
                    transitionSpec = {
                        if (targetState > initialState) {
                            (slideInHorizontally(tween(250)) { it } togetherWith
                                    slideOutHorizontally(tween(250)) { -it })
                        } else {
                            (slideInHorizontally(tween(250)) { -it } togetherWith
                                    slideOutHorizontally(tween(250)) { it })
                        }
                    },
                    label = "onboarding_step"
                ) { page ->
                    when (page) {
                        0 -> WelcomeStep()
                        1 -> BusinessSetupStep(
                            businessName = uiState.businessName,
                            onBusinessNameChange = viewModel::updateBusinessName,
                            businessNameError = uiState.businessNameError,
                            logoUri = uiState.logoUri,
                            onLogoPicked = viewModel::updateLogoUri,
                            addressLine1 = uiState.addressLine1,
                            onAddressLine1Change = viewModel::updateAddressLine1,
                            addressLine2 = uiState.addressLine2,
                            onAddressLine2Change = viewModel::updateAddressLine2,
                            city = uiState.city,
                            onCityChange = viewModel::updateCity,
                            taxNumber = uiState.taxNumber,
                            onTaxNumberChange = viewModel::updateTaxNumber,
                            currencyCode = uiState.currencyCode,
                            onCurrencyChange = viewModel::updateCurrencyCode,
                            bankName = uiState.bankName,
                            onBankNameChange = viewModel::updateBankName,
                            bankAccountName = uiState.bankAccountName,
                            onBankAccountNameChange = viewModel::updateBankAccountName,
                            bankAccountNumber = uiState.bankAccountNumber,
                            onBankAccountNumberChange = viewModel::updateBankAccountNumber,
                            bankBranch = uiState.bankBranch,
                            onBankBranchChange = viewModel::updateBankBranch,
                            mpesaPaybillNumber = uiState.mpesaPaybillNumber,
                            onMpesaPaybillNumberChange = viewModel::updateMpesaPaybillNumber,
                            mpesaTillNumber = uiState.mpesaTillNumber,
                            onMpesaTillNumberChange = viewModel::updateMpesaTillNumber
                        )
                        else -> AccountCreateStep(
                            email = uiState.email,
                            onEmailChange = viewModel::updateEmail,
                            emailError = uiState.emailError,
                            password = uiState.password,
                            onPasswordChange = viewModel::updatePassword,
                            confirmPassword = uiState.confirmPassword,
                            onConfirmPasswordChange = viewModel::updateConfirmPassword,
                            passwordError = uiState.passwordError,
                            isSubmitting = uiState.isSubmitting,
                            onCreateAccountClick = viewModel::submitEmailSignUp,
                            onGoogleSignInClick = {
                                scope.launch {
                                    launchGoogleSignIn(
                                        context = context,
                                        onIdToken = viewModel::submitGoogleSignIn,
                                        onError = viewModel::onGoogleSignInError
                                    )
                                }
                            }
                        )
                    }
                }
            }

            OnboardingNavigationBar(
                currentPage = uiState.currentPage,
                isSubmitting = uiState.isSubmitting,
                onBack = viewModel::onBack,
                onNext = {
                    when (uiState.currentPage) {
                        0 -> viewModel.onNextFromWelcome()
                        1 -> viewModel.onNextFromBusinessSetup()
                    }
                }
            )
        }
    }
}

@Composable
private fun StepIndicator(currentStep: Int, totalSteps: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center
    ) {
        repeat(totalSteps) { index ->
            val isActive = index <= currentStep
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .height(4.dp)
                    .fillMaxWidth(1f / (totalSteps - index).coerceAtLeast(1))
                    .background(
                        color = if (isActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(2.dp)
                    )
            )
        }
    }
}

@Composable
private fun OnboardingNavigationBar(
    currentPage: Int,
    isSubmitting: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        if (currentPage > 0) {
            TextButton(onClick = onBack, enabled = !isSubmitting) {
                Text("Back")
            }
        } else {
            Spacer(modifier = Modifier.size(1.dp))
        }

        // Step 2 (Create Account) submits from inside AccountCreateStep itself,
        // so this button only drives steps 0 and 1.
        if (currentPage < 2) {
            Button(onClick = onNext) {
                Text(if (currentPage == 0) "Get Started" else "Next")
            }
        }
    }
}

// =============================================================================
// Step 1: Welcome
// =============================================================================

@Composable
private fun WelcomeStep() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Business,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Welcome to Factura",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Create professional invoices, track payments, and manage " +
                    "expenses - all built for how you run your business in Kenya.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}

// =============================================================================
// Step 2: Business Profile Setup
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BusinessSetupStep(
    businessName: String,
    onBusinessNameChange: (String) -> Unit,
    businessNameError: String?,
    logoUri: String?,
    onLogoPicked: (String?) -> Unit,
    addressLine1: String,
    onAddressLine1Change: (String) -> Unit,
    addressLine2: String,
    onAddressLine2Change: (String) -> Unit,
    city: String,
    onCityChange: (String) -> Unit,
    taxNumber: String,
    onTaxNumberChange: (String) -> Unit,
    currencyCode: String,
    onCurrencyChange: (String) -> Unit,
    bankName: String,
    onBankNameChange: (String) -> Unit,
    bankAccountName: String,
    onBankAccountNameChange: (String) -> Unit,
    bankAccountNumber: String,
    onBankAccountNumberChange: (String) -> Unit,
    bankBranch: String,
    onBankBranchChange: (String) -> Unit,
    mpesaPaybillNumber: String,
    onMpesaPaybillNumberChange: (String) -> Unit,
    mpesaTillNumber: String,
    onMpesaTillNumberChange: (String) -> Unit
) {
    val logoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> onLogoPicked(uri?.toString()) }

    var currencyMenuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .align(Alignment.CenterHorizontally)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable {
                    logoPicker.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            if (logoUri != null) {
                AsyncImage(
                    model = logoUri,
                    contentDescription = "Business logo",
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.Default.CameraAlt, contentDescription = "Add logo")
            }
        }
        Text(
            text = "Add business logo (optional)",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 6.dp, bottom = 20.dp)
        )

        OutlinedTextField(
            value = businessName,
            onValueChange = onBusinessNameChange,
            label = { Text("Business name *") },
            isError = businessNameError != null,
            supportingText = businessNameError?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = addressLine1,
            onValueChange = onAddressLine1Change,
            label = { Text("Address line 1") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = addressLine2,
            onValueChange = onAddressLine2Change,
            label = { Text("Address line 2") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = city,
            onValueChange = onCityChange,
            label = { Text("City") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = taxNumber,
            onValueChange = onTaxNumberChange,
            label = { Text("Tax / PIN number") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))

        ExposedDropdownMenuBox(
            expanded = currencyMenuExpanded,
            onExpandedChange = { currencyMenuExpanded = it }
        ) {
            OutlinedTextField(
                value = currencyCode,
                onValueChange = {},
                readOnly = true,
                label = { Text("Default currency") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = currencyMenuExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
            )
            ExposedDropdownMenu(
                expanded = currencyMenuExpanded,
                onDismissRequest = { currencyMenuExpanded = false }
            ) {
                currencyOptions.forEach { code ->
                    DropdownMenuItem(
                        text = { Text(code) },
                        onClick = {
                            onCurrencyChange(code)
                            currencyMenuExpanded = false
                        }
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Payment details (optional)",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = bankName,
            onValueChange = onBankNameChange,
            label = { Text("Bank name") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = bankAccountName,
            onValueChange = onBankAccountNameChange,
            label = { Text("Bank account name") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = bankAccountNumber,
            onValueChange = onBankAccountNumberChange,
            label = { Text("Bank account number") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = bankBranch,
            onValueChange = onBankBranchChange,
            label = { Text("Bank branch") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = mpesaPaybillNumber,
            onValueChange = onMpesaPaybillNumberChange,
            label = { Text("M-Pesa Paybill number") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = mpesaTillNumber,
            onValueChange = onMpesaTillNumberChange,
            label = { Text("M-Pesa Till number") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(24.dp))
    }
}

// =============================================================================
// Step 3: Create Account
// =============================================================================

@Composable
private fun AccountCreateStep(
    email: String,
    onEmailChange: (String) -> Unit,
    emailError: String?,
    password: String,
    onPasswordChange: (String) -> Unit,
    confirmPassword: String,
    onConfirmPasswordChange: (String) -> Unit,
    passwordError: String?,
    isSubmitting: Boolean,
    onCreateAccountClick: () -> Unit,
    onGoogleSignInClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        OutlinedTextField(
            value = email,
            onValueChange = onEmailChange,
            label = { Text("Email address") },
            isError = emailError != null,
            supportingText = emailError?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            enabled = !isSubmitting,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            enabled = !isSubmitting,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = confirmPassword,
            onValueChange = onConfirmPasswordChange,
            label = { Text("Confirm password") },
            isError = passwordError != null,
            supportingText = passwordError?.let { { Text(it) } },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            enabled = !isSubmitting,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = onCreateAccountClick,
            enabled = !isSubmitting,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isSubmitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text("Create Account")
        }
        Spacer(modifier = Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(modifier = Modifier.weight(1f))
            Text(
                text = "  or  ",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider(modifier = Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = onGoogleSignInClick,
            enabled = !isSubmitting,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Continue with Google")
        }
    }
}

// =============================================================================
// Google Sign-In via Credential Manager
// =============================================================================

/**
 * Launches the Credential Manager "Sign in with Google" flow and hands the
 * resulting Google ID token to [onIdToken] (which the caller forwards to
 * FirebaseRepository.signInWithGoogleIdToken). Requires that Google Sign-In
 * is enabled as an Auth provider in the Firebase console, which is what makes
 * R.string.default_web_client_id available (auto-generated by the
 * google-services Gradle plugin into this module's generated resources).
 */
private suspend fun launchGoogleSignIn(
    context: android.content.Context,
    onIdToken: (String) -> Unit,
    onError: (String) -> Unit
) {
    val webClientId = context.getString(R.string.default_web_client_id)

    val googleIdOption = GetGoogleIdOption.Builder()
        .setFilterByAuthorizedAccounts(false)
        .setServerClientId(webClientId)
        .setAutoSelectEnabled(false)
        .setNonce(UUID.randomUUID().toString())
        .build()

    val request = GetCredentialRequest.Builder()
        .addCredentialOption(googleIdOption)
        .build()

    try {
        val credentialManager = CredentialManager.create(context)
        val result = credentialManager.getCredential(context, request)
        val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(result.credential.data)
        onIdToken(googleIdTokenCredential.idToken)
    } catch (e: GetCredentialException) {
        onError(e.message ?: "Google sign-in was cancelled or failed.")
    } catch (e: GoogleIdTokenParsingException) {
        onError("Couldn't read the Google account response. Please try again.")
    }
}