package com.androdevlinux.webview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.androdevlinux.webview.webview.WebViewComposable
import com.androdevlinux.webview.webview.WebViewState

enum class TabItem(val title: String, val symbol: String) {
    BTC("BTC/USDT", "BINANCE:BTCUSDT"),
    ETH("ETH/USDT", "BINANCE:ETHUSDT"),
    SOL("SOL/USDT", "BINANCE:SOLUSDT"),
    BNB("BNB/USDT", "BINANCE:BNBUSDT")
}

enum class ThemeMode(val displayName: String) {
    LIGHT("Light"),
    DARK("Dark"),
    SYSTEM("System")
}

// Proper Light Theme - White backgrounds with dark text
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1976D2), // Blue
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3F2FD),
    onPrimaryContainer = Color(0xFF0D47A1),
    secondary = Color(0xFF424242),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E0E0),
    onSecondaryContainer = Color(0xFF212121),
    tertiary = Color(0xFF616161),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF5F5F5),
    onTertiaryContainer = Color(0xFF424242),
    error = Color(0xFFD32F2F),
    onError = Color.White,
    errorContainer = Color(0xFFFFEBEE),
    onErrorContainer = Color(0xFFC62828),
    background = Color(0xFFFFFFFF), // Pure white
    onBackground = Color(0xFF000000), // Pure black text
    surface = Color(0xFFFFFFFF), // Pure white
    onSurface = Color(0xFF000000), // Pure black text
    surfaceVariant = Color(0xFFF5F5F5),
    onSurfaceVariant = Color(0xFF424242),
    outline = Color(0xFFBDBDBD),
    outlineVariant = Color(0xFFE0E0E0),
    scrim = Color(0x80000000),
    inverseSurface = Color(0xFF121212),
    inverseOnSurface = Color(0xFFFFFFFF),
    inversePrimary = Color(0xFF90CAF9),
    surfaceTint = Color(0xFF1976D2),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE0E0E0),
    surfaceContainer = Color(0xFFFAFAFA),
    surfaceContainerHigh = Color(0xFFF5F5F5),
    surfaceContainerHighest = Color(0xFFEEEEEE),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF)
)

// Proper Dark Theme - Black/dark backgrounds with light text
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF90CAF9), // Light blue
    onPrimary = Color(0xFF0D47A1),
    primaryContainer = Color(0xFF1565C0),
    onPrimaryContainer = Color(0xFFE3F2FD),
    secondary = Color(0xFFBDBDBD),
    onSecondary = Color(0xFF212121),
    secondaryContainer = Color(0xFF424242),
    onSecondaryContainer = Color(0xFFE0E0E0),
    tertiary = Color(0xFF9E9E9E),
    onTertiary = Color(0xFF212121),
    tertiaryContainer = Color(0xFF424242),
    onTertiaryContainer = Color(0xFFE0E0E0),
    error = Color(0xFFEF5350),
    onError = Color(0xFF000000),
    errorContainer = Color(0xFFC62828),
    onErrorContainer = Color(0xFFFFEBEE),
    background = Color(0xFF000000), // Pure black
    onBackground = Color(0xFFFFFFFF), // Pure white text
    surface = Color(0xFF121212), // Very dark gray (Material dark surface)
    onSurface = Color(0xFFFFFFFF), // Pure white text
    surfaceVariant = Color(0xFF1E1E1E),
    onSurfaceVariant = Color(0xFFBDBDBD),
    outline = Color(0xFF424242),
    outlineVariant = Color(0xFF2C2C2C),
    scrim = Color(0x80000000),
    inverseSurface = Color(0xFFFFFFFF),
    inverseOnSurface = Color(0xFF000000),
    inversePrimary = Color(0xFF1976D2),
    surfaceTint = Color(0xFF90CAF9),
    surfaceBright = Color(0xFF2C2C2C),
    surfaceDim = Color(0xFF121212),
    surfaceContainer = Color(0xFF1E1E1E),
    surfaceContainerHigh = Color(0xFF2C2C2C),
    surfaceContainerHighest = Color(0xFF383838),
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainerLowest = Color(0xFF000000)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Preview
fun App() {
    // Theme state management
    var themeMode by remember { mutableStateOf(ThemeMode.SYSTEM) }
    
    // Get system theme preference
    val systemIsDark = getSystemDarkTheme()
    
    // Determine if we should use dark theme
    val isDarkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> systemIsDark
    }
    
    // Settings dialog state
    var showSettingsDialog by remember { mutableStateOf(false) }
    
    MaterialTheme(
        colorScheme = if (isDarkTheme) {
            DarkColorScheme
        } else {
            LightColorScheme
        }
    ) {
        var selectedTabIndex by remember { mutableStateOf(0) }
        var webViewState by remember { mutableStateOf<WebViewState?>(null) }
        val tabs = remember { TabItem.entries }
        
        // Build TradingView URL with theme parameter based on current theme selection
        val currentUrl = remember(selectedTabIndex, isDarkTheme) {
            val themeParam = if (isDarkTheme) "dark" else "light"
            "https://www.tradingview.com/chart/?symbol=${tabs[selectedTabIndex].symbol}&theme=$themeParam"
        }
        
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "TradingView Charts",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge
                        )
                    },
                    actions = {
                        // Settings button
                        IconButton(
                            onClick = { showSettingsDialog = true }
                        ) {
                            Text(
                                text = "⚙️",
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        actionIconContentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    tabs.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            icon = {
                                Box(
                                    modifier = Modifier.size(24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = when (index) {
                                            0 -> "₿"
                                            1 -> "Ξ"
                                            2 -> "◎"
                                            else -> "B"
                                        },
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                            },
                            label = { 
                                Text(
                                    text = tab.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall
                                ) 
                            },
                            selected = selectedTabIndex == index,
                            onClick = { selectedTabIndex = index },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                indicatorColor = MaterialTheme.colorScheme.secondaryContainer
                            )
                        )
                    }
                }
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Loading indicator
                webViewState?.let { state ->
                    if (state.loadingState == com.androdevlinux.webview.webview.WebViewLoadingState.LOADING) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                
                // Error message display
                webViewState?.let { state ->
                    if (state.loadingState == com.androdevlinux.webview.webview.WebViewLoadingState.ERROR) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "⚠",
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Text(
                                    text = state.errorMessage ?: "Failed to load page",
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
                
                // WebView with proper padding
                WebViewComposable(
                    url = currentUrl,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    onStateChanged = { state ->
                        webViewState = state
                    },
                    javascriptInterfaces = mapOf(
                        "NativeBridge" to { message ->
                            // Handle JavaScript messages from the web page
                            // JavaScript can call: NativeBridge.postMessage("Hello from JS!")
                            println("Received from JavaScript: $message")
                        }
                    )
                )
            }
        }
        
        // Settings Dialog
        if (showSettingsDialog) {
            SettingsDialog(
                currentThemeMode = themeMode,
                onThemeModeSelected = { mode ->
                    themeMode = mode
                },
                onDismiss = { showSettingsDialog = false }
            )
        }
    }
}

@Composable
fun SettingsDialog(
    currentThemeMode: ThemeMode,
    onThemeModeSelected: (ThemeMode) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Dialog Title
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Divider()
                
                // Theme Section
                Text(
                    text = "Theme",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                // Theme Options
                ThemeMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onThemeModeSelected(mode) }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = when (mode) {
                                    ThemeMode.LIGHT -> "☀️"
                                    ThemeMode.DARK -> "🌙"
                                    ThemeMode.SYSTEM -> "⚙️"
                                },
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                text = mode.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        
                        RadioButton(
                            selected = currentThemeMode == mode,
                            onClick = { onThemeModeSelected(mode) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Close Button
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        text = "Close",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}