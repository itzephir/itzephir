package com.itzephir.website

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import kotlinx.browser.window
import kotlin.js.ExperimentalWasmJsInterop

@OptIn(ExperimentalComposeUiApi::class, ExperimentalWasmJsInterop::class)
fun main() {
    ComposeViewport(viewportContainerId = "app") {
        App(
            openLink = { url ->
                window.open(url, target = "_blank", features = "noopener,noreferrer")
            },
            onReady = {
                window.setTimeout(
                    handler = {
                        document.getElementById("app")?.classList?.add("compose-ready")
                        window.setTimeout(
                            handler = {
                                document.getElementById("boot-screen")?.remove()
                                null
                            },
                            timeout = 500,
                        )
                        null
                    },
                    timeout = 250,
                )
            },
        )
    }
}
