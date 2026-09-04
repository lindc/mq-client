package com.mqclient.app

import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.macoscompose.window.MacosDecoratedWindow

fun main() = nucleusApplication {
    MacosDecoratedWindow(
        onCloseRequest = ::exitApplication,
        title = "MQ Client",
    ) {
        MainWindow()
    }
}
