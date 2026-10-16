package com.itzephir.website

import com.itzephir.locator.IdentityRole
import com.itzephir.locator.LocatorPage
import com.itzephir.locator.LocatorPageRenderer
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.html.respondHtml
import io.ktor.server.html.respondHtmlPartial
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import kotlinx.html.*

/** The locator supplies only its document content. SiteShell owns all shared chrome. */
internal fun developmentRenderer(secureCookies: Boolean, neighbors: () -> WebringNeighbors) = LocatorPageRenderer { call, view ->
    val page = PortfolioPage.fromDevelopmentPath(call.request.path())
    val requestedTheme = call.request.queryParameters["theme"]
    if (requestedTheme != null && Theme.from(requestedTheme) == null) {
        call.respondText("Unknown theme", status = HttpStatusCode.BadRequest)
        return@LocatorPageRenderer
    }
    val theme = Theme.from(requestedTheme)
        ?: Theme.from(call.request.cookies["theme"]) ?: Theme.Dark
    val tabs = WorkspaceTabs.fromCookie(call.request.cookies["workspace_tabs"]).open(page)
    call.response.header(HttpHeaders.CacheControl, "no-store")
    call.response.header(HttpHeaders.Vary, "Cookie, HX-Request, HX-History-Restore-Request")
    if (call.request.queryParameters["theme"] != null) call.response.cookies.append(
        Cookie("theme", theme.value, path = "/", maxAge = 31_536_000, httpOnly = true, secure = secureCookies, extensions = mapOf("SameSite" to "Lax")),
    )
    if (call.request.cookies["workspace_tabs"] != tabs.cookieValue) call.saveTabs(tabs, secureCookies)
    val content: MAIN.() -> Unit = { developmentContent(theme, page, view) }
    if (call.request.headers["HX-Request"] == "true" && call.request.headers["HX-History-Restore-Request"] != "true") {
        call.respondHtmlPartial(view.status) { portfolio(theme, page, neighbors(), tabs, content) }
    } else {
        call.respondHtml(view.status) { document(theme, page, neighbors(), tabs, content, "${view.title} · itzephir") }
    }
}

private fun MAIN.developmentContent(theme: Theme, page: PortfolioPage, view: LocatorPage) {
    val actor = view.actor
    if (actor != null) {
        div("dev-panel") {
            attributes["hx-history"] = "false"
            developmentControls(theme, page)
            nav("dev-sidebar") {
                attributes["aria-label"] = "Управление проектами"
                p("eyebrow mono") { +"УПРАВЛЕНИЕ" }
                a(PortfolioPage.Panel.path, classes = "dev-menu-link") {
                    if (page == PortfolioPage.Panel || page.tabKey.startsWith("edit-") || page == PortfolioPage.NewProject) attributes["aria-current"] = "page"
                    icon(theme, "folder", 18); +(if (actor.role == IdentityRole.ADMIN) "Все проекты" else "Мои проекты")
                }
                if (actor.role == IdentityRole.ADMIN) {
                    a("/dev/panel?status=pending", classes = "dev-menu-link") { icon(theme, "folder-open", 18); +"Заявки" }
                    a(PortfolioPage.Users.path, classes = "dev-menu-link") {
                        if (page == PortfolioPage.Users) attributes["aria-current"] = "page"
                        icon(theme, "users", 18); +"Пользователи"
                    }
                }
                a(PortfolioPage.Account.path, classes = "dev-menu-link") {
                    if (page == PortfolioPage.Account) attributes["aria-current"] = "page"
                    icon(theme, "settings", 18); +"Аккаунт"
                }
                p("help dev-role") { +(if (actor.role == IdentityRole.ADMIN) "Администратор" else "Владелец проектов") }
                form(action = "/dev/panel/logout", method = FormMethod.post) {
                    hiddenInput(name = "csrf") { value = view.formToken }
                    button(type = ButtonType.submit, classes = "secondary") { +"Выйти" }
                }
                a("/dev", classes = "dev-menu-link") { +"Открыть витрину" }
            }
            div("dev-workspace dev-ui") {
                p("eyebrow mono") { +"dev / panel" }
                div("dev-document") { view.content(this) }
            }
        }
    } else {
        div("dev-public dev-ui${if (page != PortfolioPage.Development) " dev-login" else ""}") {
            developmentControls(theme, page)
            view.content(this)
            footer("site-footer") {
                div("footer-main") {
                    span("mono muted") { +"~/itzephir" }
                    div("footer-links") { a("https://t.me/ItzEphir") { +"Telegram" }; a(github) { +"GitHub" }; a("mailto:d.y.dvoryannikov@mail.ru") { +"Email" } }
                }
            }
        }
    }
}

private fun FlowContent.developmentControls(theme: Theme, page: PortfolioPage) {
    div("dev-controls") {
        a(if (page == PortfolioPage.Development) "/" else "/dev", classes = "back-link") { icon(theme, "back", 16); +"Назад" }
        themeToggle(theme, page, true)
    }
}
