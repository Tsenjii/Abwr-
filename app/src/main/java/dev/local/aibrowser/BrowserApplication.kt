package dev.local.aibrowser

import android.app.Application

class BrowserApplication : Application() {
    val controller: BrowserController by lazy { BrowserController(this) }
}