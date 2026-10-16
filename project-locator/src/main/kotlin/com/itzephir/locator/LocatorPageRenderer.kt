package com.itzephir.locator

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import io.ktor.util.AttributeKey
import kotlinx.html.*

/** Request handlers supply content and auth context; the host application owns the site shell. */
data class LocatorPage(
    val title: String,
    val actor: Identity?,
    val formToken: String,
    val status: HttpStatusCode,
    val content: FlowContent.() -> Unit,
)

fun interface LocatorPageRenderer {
    suspend fun render(call: ApplicationCall, page: LocatorPage)
}

internal val locatorRenderer = AttributeKey<LocatorPageRenderer>("locator-page-renderer")

// Allows the locator library to run independently in API/auth tests. No shared chrome lives here.
internal val contentOnlyRenderer = LocatorPageRenderer { call, page ->
    call.respondHtml(page.status) {
        lang = "ru"
        head { title { +page.title } }
        body { main { page.content(this) } }
    }
}

internal suspend fun ApplicationCall.renderPage(
    title: String,
    actor: Identity? = null,
    formToken: String = "",
    status: HttpStatusCode = HttpStatusCode.OK,
    content: FlowContent.() -> Unit,
) = application.attributes[locatorRenderer].render(this, LocatorPage(title, actor, formToken, status, content))
