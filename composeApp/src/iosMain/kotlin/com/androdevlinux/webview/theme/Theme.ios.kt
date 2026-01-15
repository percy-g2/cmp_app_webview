package com.androdevlinux.webview.theme

import androidx.compose.runtime.Composable
import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.UIScreen

@Composable
actual fun getSystemDarkTheme(): Boolean {
    return UIScreen.mainScreen.traitCollection.userInterfaceStyle ==
        UIUserInterfaceStyle.UIUserInterfaceStyleDark
}
