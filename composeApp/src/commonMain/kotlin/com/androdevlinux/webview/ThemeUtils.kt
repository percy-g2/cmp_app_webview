package com.androdevlinux.webview

import androidx.compose.runtime.Composable

/**
 * Get system dark theme preference
 * Returns true if system is in dark mode, false otherwise
 */
@Composable
expect fun getSystemDarkTheme(): Boolean
