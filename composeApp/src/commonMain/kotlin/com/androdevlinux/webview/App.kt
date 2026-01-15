package com.androdevlinux.webview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.androdevlinux.webview.theme.AppTheme
import com.androdevlinux.webview.theme.ThemeMode
import com.androdevlinux.webview.webview.WebViewComposable
import com.androdevlinux.webview.webview.WebViewState

enum class TabItem(val title: String, val symbol: String) {
    BTC("BTC/USDT", "BINANCE:BTCUSDT"),
    ETH("ETH/USDT", "BINANCE:ETHUSDT"),
    SOL("SOL/USDT", "BINANCE:SOLUSDT"),
    BNB("BNB/USDT", "BINANCE:BNBUSDT")
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Preview
fun App(onThemeChanged: (Boolean) -> Unit = {}) {
    // Theme state management
    var themeMode by remember { mutableStateOf(ThemeMode.SYSTEM) }
    
    // Settings dialog state
    var showSettingsDialog by remember { mutableStateOf(false) }
    
    AppTheme(themeMode) { isDarkTheme ->
        SideEffect {
            onThemeChanged(isDarkTheme)
        }
        var selectedTabIndex by remember { mutableStateOf(0) }
        var webViewState by remember { mutableStateOf<WebViewState?>(null) }
        val tabs = remember { TabItem.entries }
        
        // Build TradingView widget URL with theme parameter based on current theme selection
        val currentUrl = remember(selectedTabIndex, isDarkTheme) {
            val themeParam = if (isDarkTheme) "dark" else "light"
            "https://www.tradingview.com/widgetembed/?symbol=${tabs[selectedTabIndex].symbol}&theme=$themeParam"
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