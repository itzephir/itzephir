package com.itzephir.website

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.ComposeViewport
import com.itzephir.website.generated.resources.Res
import com.itzephir.website.generated.resources.roboto_f99820f9f1c7c171
import com.itzephir.website.generated.resources.roboto_mono_e7069fe300d4629b
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.preloadFont
import kotlin.js.ExperimentalWasmJsInterop

@OptIn(ExperimentalComposeUiApi::class, ExperimentalWasmJsInterop::class, ExperimentalResourceApi::class)
fun main() {
    ComposeViewport(viewportContainerId = "app") {
        val sansNormal by preloadFont(Res.font.roboto_f99820f9f1c7c171, FontWeight.Normal)
        val sansMedium by preloadFont(Res.font.roboto_f99820f9f1c7c171, FontWeight.Medium)
        val sansBold by preloadFont(Res.font.roboto_f99820f9f1c7c171, FontWeight.Bold)
        val sansBlack by preloadFont(Res.font.roboto_f99820f9f1c7c171, FontWeight.Black)
        val monoNormal by preloadFont(Res.font.roboto_mono_e7069fe300d4629b, FontWeight.Normal)
        val monoBold by preloadFont(Res.font.roboto_mono_e7069fe300d4629b, FontWeight.Bold)

        if (sansNormal == null || sansMedium == null || sansBold == null || sansBlack == null ||
            monoNormal == null || monoBold == null
        ) {
            return@ComposeViewport
        }

        App(
            preloadedSiteSans = FontFamily(sansNormal!!, sansMedium!!, sansBold!!, sansBlack!!),
            preloadedSiteMono = FontFamily(monoNormal!!, monoBold!!),
            openLink = { url ->
                window.open(url, target = "_blank", features = "noopener,noreferrer")
            },
            onReady = {
                // The app is composed only after its fonts are loaded. Give it
                // two complete browser frames, then swap renderers without a
                // cross-fade that would expose sub-pixel rasterizer differences.
                window.requestAnimationFrame {
                    window.requestAnimationFrame {
                        document.getElementById("app")?.classList?.add("compose-ready")
                        window.requestAnimationFrame {
                            document.getElementById("boot-screen")?.remove()
                        }
                    }
                }
            },
        )
    }
}
