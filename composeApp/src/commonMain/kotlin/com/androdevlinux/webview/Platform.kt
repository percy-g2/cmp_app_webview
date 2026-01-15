package com.androdevlinux.webview

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform