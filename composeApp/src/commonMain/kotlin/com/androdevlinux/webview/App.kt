package com.androdevlinux.webview

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
fun App() {
    // Theme state - can be Light, Dark, or System
    var darkTheme by remember { mutableStateOf(false) }
    var useSystemTheme by remember { mutableStateOf(true) }
    
    // Get system theme preference (expect function for platform-specific implementation)
    // This will automatically recompose when system theme changes
    val systemIsDark = getSystemDarkTheme()
    
    // Determine if we should use dark theme
    // If using system theme, follow system preference; otherwise use manual toggle
    val isDarkTheme = if (useSystemTheme) {
        systemIsDark
    } else {
        darkTheme
    }
    
    MaterialTheme(
        colorScheme = if (isDarkTheme) {
            darkColorScheme()
        } else {
            lightColorScheme()
        }
    ) {
        var selectedTabIndex by remember { mutableStateOf(0) }
        var webViewState by remember { mutableStateOf<WebViewState?>(null) }
        val tabs = remember { TabItem.entries }
        val currentUrl = remember(selectedTabIndex) {
            "https://www.tradingview.com/chart/?symbol=${tabs[selectedTabIndex].symbol}"
        }
        
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "TradingView Charts",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    actions = {
                        // Theme toggle button
                        IconButton(
                            onClick = {
                                if (useSystemTheme) {
                                    useSystemTheme = false
                                    darkTheme = !systemIsDark
                                } else {
                                    darkTheme = !darkTheme
                                }
                            }
                        ) {
                            Text(
                                text = if (isDarkTheme) "☀️" else "🌙",
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            },
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    tabs.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            icon = {
                                // Simple icon placeholder - you can replace with actual icons if available
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .padding(4.dp),
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
                                    overflow = TextOverflow.Ellipsis
                                ) 
                            },
                            selected = selectedTabIndex == index,
                            onClick = { selectedTabIndex = index },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer
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
    }
}