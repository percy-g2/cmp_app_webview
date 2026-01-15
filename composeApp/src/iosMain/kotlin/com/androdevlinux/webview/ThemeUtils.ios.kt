package com.androdevlinux.webview

import androidx.compose.runtime.Composable
import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.UIScreen

@Composable
actual fun getSystemDarkTheme(): Boolean {
    // Get trait collection from the main screen
    return UIScreen.mainScreen.traitCollection.userInterfaceStyle == UIUserInterfaceStyle.UIUserInterfaceStyleDark
}
