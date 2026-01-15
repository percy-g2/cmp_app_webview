package com.androdevlinux.webview

import androidx.compose.runtime.Composable
import platform.UIKit.UITraitCollection
import platform.UIKit.UIUserInterfaceStyle

@Composable
actual fun getSystemDarkTheme(): Boolean {
    val traitCollection = UITraitCollection.currentTraitCollection
    return traitCollection.userInterfaceStyle == UIUserInterfaceStyle.UIUserInterfaceStyleDark
}
