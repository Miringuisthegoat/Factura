package com.benjamin.factura.ui.screens


import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.benjamin.factura.R
import com.benjamin.factura.data.local.PreferencesManager
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

// Requires caesar_dressing.ttf placed in res/font/ (Google Fonts: Caesar Dressing)
// Using default for now to avoid compilation errors due to missing file.
private val CaesarDressingFontFamily = FontFamily.Default

enum class SplashDestination {
    ONBOARDING,
    DASHBOARD
}

data class SplashUiState(
    val destination: SplashDestination? = null
)

@HiltViewModel
class SplashViewModel @Inject constructor(
    private val preferencesManager: PreferencesManager,
    private val firebaseAuth: FirebaseAuth
) : ViewModel() {

    private val _uiState = MutableStateFlow(SplashUiState())
    val uiState: StateFlow<SplashUiState> = _uiState.asStateFlow()

    init {
        checkAuthAndRoute()
    }

    private fun checkAuthAndRoute() {
        viewModelScope.launch {
            // Minimum splash duration so the brand mark isn't a flash-cut,
            // combined with the actual auth/onboarding check.
            val startTime = System.currentTimeMillis()
            val onboardingCompleted = preferencesManager.isOnboardingComplete.first()
            val currentUser = firebaseAuth.currentUser

            val elapsedTime = System.currentTimeMillis() - startTime
            val remainingTime = 900L - elapsedTime
            if (remainingTime > 0) {
                delay(remainingTime)
            }

            val destination = if (!onboardingCompleted || currentUser == null) {
                SplashDestination.ONBOARDING
            } else {
                SplashDestination.DASHBOARD
            }
            _uiState.value = _uiState.value.copy(destination = destination)
        }
    }
}

@Composable
fun SplashScreen(
    onNavigateToOnboarding: () -> Unit,
    onNavigateToDashboard: () -> Unit,
    viewModel: SplashViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    val scale = remember { Animatable(0.85f) }
    LaunchedEffect(Unit) {
        scale.animateTo(1f, animationSpec = tween(durationMillis = 500))
    }

    LaunchedEffect(uiState.destination) {
        when (uiState.destination) {
            SplashDestination.ONBOARDING -> onNavigateToOnboarding()
            SplashDestination.DASHBOARD -> onNavigateToDashboard()
            null -> Unit
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF1D6A72)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.scale(scale.value)
            ) {
                Box(
                    modifier = Modifier
                        .size(88.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "F",
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1D6A72),
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White, shape = CircleShape)
                            .wrapContentSize(Alignment.Center)
                    )
                }
                Text(
                    text = "Factura",
                    fontSize = 28.sp,
                    fontFamily = CaesarDressingFontFamily,
                    color = Color.White
                )
                Text(
                    text = "Invoicing made simple",
                    fontSize = 14.sp,
                    fontFamily = CaesarDressingFontFamily,
                    color = Color.White.copy(alpha = 0.85f)
                )
            }

            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp)
                    .size(28.dp),
                color = Color.White,
                strokeWidth = 2.5.dp
            )
        }
    }
}