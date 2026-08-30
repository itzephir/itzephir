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
                // Give Compose two browser frames to draw instead of imposing
                // a fixed 250 ms delay on every visit, including cached loads.
                window.requestAnimationFrame {
                    window.requestAnimationFrame {
                        document.getElementById("app")?.classList?.add("compose-ready")
                        window.setTimeout(
                            handler = {
                                document.getElementById("boot-screen")?.remove()
                                null
                            },
                            timeout = 500,
                        )
                    }
                }
            },
        )
    }
}
