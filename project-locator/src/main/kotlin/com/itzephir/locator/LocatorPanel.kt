package com.itzephir.locator

import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.security.MessageDigest
import kotlinx.html.*

private const val PANEL = "/dev/panel"
internal const val PANEL_COOKIE = "itzephir_locator_session"
private const val LOGIN_COOKIE = "itzephir_locator_login"
private const val GITHUB_COOKIE = "itzephir_locator_github"

internal fun Application.locatorPanel(locator: PostgresLocator, secureCookies: Boolean, auth: PanelAuth?, projectRoutingEnabled: Boolean) {
    monitor.subscribe(ApplicationStopped) { auth?.github?.close() }
    routing {
        route("/dev") {
            install(createRouteScopedPlugin("LocatorPageHeaders") {
                onCall { call ->
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    call.response.header("Content-Security-Policy", "default-src 'none'; style-src 'self'; script-src 'self'; img-src 'self'; font-src 'self'; connect-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'")
                    // Preserve same-origin form navigation; external destinations receive no referrer.
                    call.response.header("Referrer-Policy", "same-origin")
                    call.response.header("X-Content-Type-Options", "nosniff")
                    if (call.request.path().startsWith(PANEL)) call.response.header("X-Robots-Tag", "noindex, nofollow")
                }
            })
            get {
                val offset = call.offset()
                val projects = locator.publicProjects(25, offset)
                call.renderPage("Проекты в разработке") {
                    div("dev-gallery-meta") {
                        p("eyebrow mono") { +"dev / Открытая мастерская" }
                        a(PANEL, classes = "button secondary") { +"Панель проектов" }
                    }
                    div("page-heading") { h1 { +"Проекты в разработке" }; p("muted") { +"Сервисы, инструменты и новые идеи, над которыми я работаю." } }
                    p("eyebrow mono") { +"ОПУБЛИКОВАНО / ${projects.take(24).size}" }
                    if (projects.isEmpty()) div("empty") { h2 { +"Новые проекты скоро появятся" }; p { +"Здесь будут собраны проекты в разработке." } }
                    div("project-grid") { projects.take(24).forEach { project ->
                        article("project-card") {
                            h2 { +project.project }
                            p("muted mono") { +"/dev/project/${project.slug}/" }
                            div("project-card-actions") {
                                a("/dev/project/${project.slug}/", classes = "project-link") { +"Открыть проект" }
                                span("help") { +"В разработке" }
                            }
                        }
                    } }
                    pagination("/dev", offset, 24, projects.size > 24)
                }
            }
            get("/") { call.seeOther("/dev") }
            get("/assets/style.css") {
                call.respondText(javaClass.getResource("/static/dev.css")?.readText().orEmpty(), ContentType.Text.CSS)
            }
            get("/panel") {
                if (auth == null) throw LocatorException(503, "panel_unconfigured", "Panel sign in is not configured")
                val token = call.request.cookies[PANEL_COOKIE]
                val actor = token?.let { auth.user(it)?.identity() }
                if (actor == null) {
                    call.panelCookie(PANEL_COOKIE, "", 0, secureCookies)
                    val nonce = Tokens.issue()
                    call.panelCookie(LOGIN_COOKIE, nonce, 600, secureCookies)
                    call.loginPage(csrf(nonce), githubEnabled = auth.github != null)
                    return@get
                }
                val offset = call.offset()
                val status = call.request.queryParameters["status"]?.takeIf(String::isNotBlank)?.let { value ->
                    ProjectStatus.entries.firstOrNull { it.databaseValue == value }
                        ?: throw LocatorException(400, "invalid_status", "Invalid status")
                }
                val projects = locator.list(actor, status, 51, offset)
                val formToken = csrf(token)
                call.renderPage("Панель проектов", actor, formToken) {
                    div("page-heading") {
                        h1 { +(if (actor.role == IdentityRole.ADMIN && status == ProjectStatus.PENDING) "Заявки" else if (actor.role == IdentityRole.ADMIN) "Все проекты" else "Мои проекты") }
                        p("muted") { +(if (actor.role == IdentityRole.ADMIN && status == ProjectStatus.PENDING) "Проверьте имя и адрес перед публикацией." else if (actor.role == IdentityRole.ADMIN) "Проекты, заявки и доступы к ним." else "Настройки и статус ваших проектов.") }
                    }
                    if (actor.role == IdentityRole.ADMIN) {
                        a("$PANEL/new", classes = "button") { +"Новый проект" }
                        if (status == null && projects.any { it.status == ProjectStatus.PENDING }) div("notice") {
                            h2 { +"Заявки ждут проверки" }
                            p { +"Проверьте данные перед публикацией на витрине." }
                            a("$PANEL?status=pending", classes = "button secondary") { +"Проверить заявки" }
                        }
                    }
                    nav("filters") {
                        a(PANEL) { if (status == null) attributes["aria-current"] = "page"; +"Все" }
                        ProjectStatus.entries.forEach { state -> a("$PANEL?status=${state.databaseValue}") { if (status == state) attributes["aria-current"] = "page"; +state.label() } }
                    }
                    if (projects.isEmpty()) div("empty") { p { +"Проектов с таким статусом пока нет." } }
                    div("project-list") { projects.take(50).forEach { project ->
                        article("project-row") {
                            h2 { a("$PANEL/${project.id}/edit") { +project.project } }
                            div("project-row-meta") {
                                p("muted mono") { +"/${project.slug}" }
                                p("help") { +project.routing.mode.label(); if (project.routing.target.isNotEmpty()) +" · ${project.routing.target}" }
                                span("badge ${project.status.databaseValue}") { +project.status.label() }
                            }
                            div("row-actions") {
                                a("$PANEL/${project.id}/edit", classes = "button secondary") { +"Редактировать" }
                                if (actor.role == IdentityRole.ADMIN && project.status == ProjectStatus.PENDING) {
                                    reviewForm(project, "approve", "Одобрить", formToken)
                                    reviewForm(project, "reject", "Отклонить", formToken)
                                }
                            }
                        }
                    } }
                    pagination(PANEL + (status?.let { "?status=${it.databaseValue}" } ?: ""), offset, 50, projects.size > 50)

                }
            }
            get("/panel/new") {
                val actor = call.panelActor(auth)
                requireAdmin(actor)
                call.renderPage("Новый проект", actor, csrf(call.request.cookies[PANEL_COOKIE]!!)) {
                    div("page-heading") { h1 { +"Новый проект" }; p("muted") { +"Проект администратора будет одобрен сразу." } }
                    section("card") {
                        form(action = "$PANEL/create", method = FormMethod.post) {
                            hiddenCsrf(csrf(call.request.cookies[PANEL_COOKIE]!!)); projectFields("", "", enabled = projectRoutingEnabled)
                            button(type = ButtonType.submit) { +"Создать проект" }
                        }
                    }
                }
            }
            post("/panel/login") {
                val form = call.receiveParameters()
                verifyCsrf(call.request.cookies[LOGIN_COOKIE], form)
                val session = auth?.passwordLogin(form["username"] ?: "", form["password"] ?: "")
                if (session == null) {
                    call.loginPage(csrf(call.request.cookies[LOGIN_COOKIE]!!), "Неверный логин или пароль.", HttpStatusCode.Unauthorized, auth?.github != null)
                    return@post
                }
                call.request.cookies[PANEL_COOKIE]?.let { auth.logout(it) }
                call.panelCookie(PANEL_COOKIE, session, 8 * 60 * 60, secureCookies)
                call.panelCookie(LOGIN_COOKIE, "", 0, secureCookies)
                call.seeOther(PANEL)
            }
            post("/panel/logout") {
                call.panelActor(auth)
                val token = call.request.cookies[PANEL_COOKIE]!!
                verifyCsrf(token, call.receiveParameters())
                auth!!.logout(token)
                call.panelCookie(PANEL_COOKIE, "", 0, secureCookies)
                call.seeOther(PANEL)
            }
            get("/panel/{id}/edit") {
                val actor = call.panelActor(auth)
                val project = locator.get(parseId(call.parameters["id"]!!), actor)
                call.editPage(project, actor, csrf(call.request.cookies[PANEL_COOKIE]!!), enabled = projectRoutingEnabled, users = if (actor.role == IdentityRole.ADMIN) auth!!.accounts.users(actor) else emptyList(), owners = if (actor.role == IdentityRole.ADMIN) auth!!.accounts.owners(parseId(project.id), actor) else emptySet())
            }
            post("/panel/{id}/edit") {
                val actor = call.panelActor(auth)
                val form = call.receiveParameters()
                val formToken = csrf(call.request.cookies[PANEL_COOKIE]!!)
                verifyCsrf(call.request.cookies[PANEL_COOKIE], form)
                val project = locator.get(parseId(call.parameters["id"]!!), actor)
                val revision = form["revision"]?.toIntOrNull() ?: throw LocatorException(400, "invalid_revision", "Missing revision")
                val update = ProjectUpdate(form["project"] ?: "", form["slug"] ?: "", revision, form.routing())
                try {
                    locator.update(parseId(project.id), update, actor)
                    call.seeOther("$PANEL/${project.id}/edit")
                } catch (error: LocatorException) {
                    if (error.status !in listOf(400, 409)) throw error
                    call.editPage(project.copy(project = update.project, slug = update.slug, revision = revision, routing = update.routing ?: project.routing), actor, formToken, projectRoutingEnabled, error, if (actor.role == IdentityRole.ADMIN) auth!!.accounts.users(actor) else emptyList(), if (actor.role == IdentityRole.ADMIN) auth!!.accounts.owners(parseId(project.id), actor) else emptySet())
                }
            }
            post("/panel/create") {
                val actor = call.panelActor(auth)
                requireAdmin(actor)
                val form = call.receiveParameters()
                verifyCsrf(call.request.cookies[PANEL_COOKIE], form)
                val registration = locator.register(ProjectRequest(form["project"] ?: "", form["slug"] ?: "", form.routing() ?: ProjectRouting()), actor)
                call.renderPage("Проект создан", actor, csrf(call.request.cookies[PANEL_COOKIE]!!)) {
                    h1 { +"Проект создан" }; p { +registration.project.project }
                    div("card") { h2 { +"Сохраните API-токен" }; p("muted") { +"Он показывается только сейчас. Используйте его для доступа к проекту." }; pre { code { +registration.token } } }
                    a("$PANEL/${registration.project.id}/edit", classes = "button") { +"К настройкам проекта" }
                }
            }
            post("/panel/{id}/owners") {
                val actor = call.panelActor(auth)
                requireAdmin(actor)
                val form = call.receiveParameters()
                verifyCsrf(call.request.cookies[PANEL_COOKIE], form)
                val revision = form["revision"]?.toIntOrNull() ?: throw LocatorException(400, "invalid_revision", "Missing revision")
                auth!!.accounts.setOwners(parseId(call.parameters["id"]!!), form.getAll("owner").orEmpty().map(::parseId).toSet(), revision, actor)
                call.seeOther("$PANEL/${call.parameters["id"]}/edit")
            }
            get("/panel/users") {
                val actor = call.panelActor(auth)
                val users = auth!!.accounts.users(actor)
                call.renderPage("Пользователи", actor, csrf(call.request.cookies[PANEL_COOKIE]!!)) {
                    div("page-heading") { h1 { +"Пользователи" }; p("muted") { +"Управление доступом к проектам." } }
                    div("card user-list") { users.forEach { user -> article("user-row") {
                        h2 { +user.username }; span("help") { +(if (user.role == IdentityRole.ADMIN) "Администратор" else "Владелец проектов") }
                    } } }
                    section("card create-project") {
                        h2 { +"Добавить владельца" }
                        form(action = "$PANEL/users", method = FormMethod.post) {
                            hiddenCsrf(csrf(call.request.cookies[PANEL_COOKIE]!!))
                            div("field") { label { htmlFor = "username"; +"Логин" }; textInput(name = "username") { id = "username"; required = true; attributes["pattern"] = "[a-z0-9][a-z0-9_.-]{2,63}"; attributes["autocomplete"] = "off" } }
                            div("field") { label { htmlFor = "new-password"; +"Пароль" }; passwordInput(name = "password") { id = "new-password"; required = true; attributes["minlength"] = "12"; attributes["maxlength"] = "256"; attributes["autocomplete"] = "new-password" } }
                            p("help") { +"Не менее 12 символов. Проекты можно назначить в их настройках." }
                            button(type = ButtonType.submit) { +"Добавить пользователя" }
                        }
                    }
                }
            }
            post("/panel/users") {
                val actor = call.panelActor(auth)
                requireAdmin(actor)
                val form = call.receiveParameters()
                verifyCsrf(call.request.cookies[PANEL_COOKIE], form)
                auth!!.accounts.createOwner(form["username"] ?: "", form["password"] ?: "", actor)
                call.seeOther("$PANEL/users")
            }
            get("/panel/account") {
                val actor = call.panelActor(auth)
                val user = auth!!.accounts.user(actor.id)
                val formToken = csrf(call.request.cookies[PANEL_COOKIE]!!)
                call.renderPage("Моя учётная запись", actor, formToken) {
                    div("page-heading") { h1 { +"Моя учётная запись" }; p("muted") { +user.username } }
                    section("card") {
                        h2 { +"Изменить пароль" }
                        form(action = "$PANEL/account/password", method = FormMethod.post) {
                            hiddenCsrf(formToken)
                            if (user.hasPassword) div("field") { label { htmlFor = "current-password"; +"Текущий пароль" }; passwordInput(name = "current") { id = "current-password"; required = true; attributes["maxlength"] = "256"; attributes["autocomplete"] = "current-password" } }
                            div("field") { label { htmlFor = "new-password"; +"Новый пароль" }; passwordInput(name = "password") { id = "new-password"; required = true; attributes["minlength"] = "12"; attributes["maxlength"] = "256"; attributes["autocomplete"] = "new-password" } }
                            p("help") { +"Не менее 12 символов. Старые сессии будут завершены." }
                            button(type = ButtonType.submit) { +"Сохранить пароль" }
                        }
                    }
                    if (auth.github != null) section("card") {
                        h2 { +"GitHub" }; p("muted") { +(user.githubLogin?.let { "Подключён аккаунт @$it" } ?: "Подключите GitHub, чтобы входить без пароля.") }
                        form(action = "$PANEL/github", method = FormMethod.post) { hiddenCsrf(formToken); button(type = ButtonType.submit, classes = "secondary") { +"Подключить GitHub" } }
                    }
                }
            }
            post("/panel/account/password") {
                val actor = call.panelActor(auth)
                val form = call.receiveParameters()
                verifyCsrf(call.request.cookies[PANEL_COOKIE], form)
                auth!!.accounts.changePassword(actor.id, form["current"] ?: "", form["password"] ?: "")
                call.panelCookie(PANEL_COOKIE, auth.issue(auth.accounts.user(actor.id)), 8 * 60 * 60, secureCookies)
                call.seeOther("$PANEL/account")
            }
            post("/panel/github") {
                val provider = auth?.github ?: throw LocatorException(503, "github_unconfigured", "GitHub sign in is not configured")
                val form = call.receiveParameters()
                val user = call.request.cookies[PANEL_COOKIE]?.let { auth.user(it) }
                verifyCsrf(if (user == null) call.request.cookies[LOGIN_COOKIE] else call.request.cookies[PANEL_COOKIE], form)
                val state = Tokens.issue()
                val verifier = Tokens.issue()
                auth.accounts.oauthState(state, verifier, user?.id)
                call.panelCookie(GITHUB_COOKIE, state, 600, secureCookies, sameSite = "Lax")
                call.seeOther(provider.authorizationUrl(state, verifier))
            }
            get("/panel/github/callback") {
                val provider = auth?.github ?: throw LocatorException(503, "github_unconfigured", "GitHub sign in is not configured")
                val state = call.request.queryParameters["state"] ?: ""
                val cookie = call.request.cookies[GITHUB_COOKIE] ?: ""
                if (state.length !in 32..256 || !MessageDigest.isEqual(state.toByteArray(), cookie.toByteArray())) throw LocatorException(403, "csrf_failed", "OAuth state mismatch")
                val saved = auth.accounts.consumeOAuthState(state) ?: throw LocatorException(403, "csrf_failed", "OAuth state expired")
                call.panelCookie(GITHUB_COOKIE, "", 0, secureCookies, sameSite = "Lax")
                if (call.request.queryParameters["error"] != null) throw LocatorException(401, "github_failed", "GitHub sign in cancelled")
                val profile = provider.exchange(call.request.queryParameters["code"] ?: "", saved.verifier)
                val user = auth.accounts.githubUser(profile.id, profile.login, saved.linkUserId)
                call.request.cookies[PANEL_COOKIE]?.let { auth.logout(it) }
                call.panelCookie(PANEL_COOKIE, auth.issue(user), 8 * 60 * 60, secureCookies)
                call.panelCookie(LOGIN_COOKIE, "", 0, secureCookies)
                call.seeOther(PANEL)
            }
            listOf("approve" to ProjectStatus.APPROVED, "reject" to ProjectStatus.REJECTED).forEach { (action, status) ->
                post("/panel/{id}/$action") {
                    val actor = call.panelActor(auth)
                    requireAdmin(actor)
                    val form = call.receiveParameters()
                    verifyCsrf(call.request.cookies[PANEL_COOKIE], form)
                    val revision = form["revision"]?.toIntOrNull() ?: throw LocatorException(400, "invalid_revision", "Missing revision")
                    locator.review(parseId(call.parameters["id"]!!), status, actor, revision)
                    call.seeOther(PANEL)
                }
            }
        }
    }
}

private suspend fun ApplicationCall.panelActor(auth: PanelAuth?): Identity {
    if (auth == null) throw LocatorException(503, "panel_unconfigured", "Panel is not configured")
    return request.cookies[PANEL_COOKIE]?.let { auth.user(it)?.identity() }
        ?: throw LocatorException(401, "invalid_session", "Sign in again")
}

private fun csrf(token: String) = Tokens.hash("locator-form:$token")
private fun verifyCsrf(token: String?, form: Parameters) {
    val supplied = form["csrf"]
    if (token == null || supplied == null || !MessageDigest.isEqual(csrf(token).toByteArray(), supplied.toByteArray())) {
        throw LocatorException(403, "csrf_failed", "Invalid form token")
    }
}

private fun ApplicationCall.panelCookie(name: String, value: String, age: Int, secure: Boolean, sameSite: String = "Strict") {
    response.cookies.append(Cookie(name, value, path = PANEL, maxAge = age, httpOnly = true, secure = secure, extensions = mapOf("SameSite" to sameSite)))
}

private suspend fun ApplicationCall.seeOther(location: String) { response.header(HttpHeaders.Location, location); respond(HttpStatusCode.SeeOther) }
private fun ApplicationCall.offset(): Int = request.queryParameters["offset"]?.let { it.toIntOrNull()?.takeIf { value -> value in 0..1_000_000 } ?: throw LocatorException(400, "invalid_pagination", "Invalid offset") } ?: 0

internal suspend fun ApplicationCall.panelError(error: LocatorException) {
    renderPage("Действие недоступно", status = HttpStatusCode.fromValue(error.status)) {
        h1 { +"Действие недоступно" }; p("notice") { +error.friendly() }; a(PANEL, classes = "button") { +"Вернуться в панель" }
    }
}

private suspend fun ApplicationCall.loginPage(formToken: String, error: String? = null, status: HttpStatusCode = HttpStatusCode.OK, githubEnabled: Boolean = false) {
    renderPage("Вход в панель", status = status) {
        div("page-heading") { h1 { +"Вход в панель" }; p("muted") { +"Войдите, чтобы управлять своими проектами." } }
        section("card login-card") {
            if (error != null) p("notice") { attributes["role"] = "alert"; +error }
            form(action = "$PANEL/login", method = FormMethod.post) {
                hiddenCsrf(formToken)
                div("field") { label { htmlFor = "username"; +"Логин" }
                    textInput(name = "username") { id = "username"; required = true; attributes["maxlength"] = "64"; attributes["autocomplete"] = "username" } }
                div("field") { label { htmlFor = "password"; +"Пароль" }
                    passwordInput(name = "password") { id = "password"; required = true; attributes["maxlength"] = "256"; attributes["autocomplete"] = "current-password" } }
                button(type = ButtonType.submit) { +"Войти" }
            }
            if (githubEnabled) form(action = "$PANEL/github", method = FormMethod.post) { hiddenCsrf(formToken); button(type = ButtonType.submit, classes = "secondary") { +"Войти через GitHub" } }
        }
    }
}

private suspend fun ApplicationCall.editPage(project: Project, actor: Identity, formToken: String, enabled: Boolean, error: LocatorException? = null, users: List<WebUser> = emptyList(), owners: Set<String> = emptySet()) {
    renderPage("Настройки проекта", actor, formToken, status = error?.let { HttpStatusCode.fromValue(it.status) } ?: HttpStatusCode.OK) {
        a(PANEL, classes = "back-link") { +"К проектам" }
        div("page-heading") { h1 { +"Настройки проекта" }; p("muted") { +project.project }; span("badge ${project.status.databaseValue}") { +project.status.label() } }
        if (error != null) p("notice") { attributes["role"] = "alert"; +error.friendly(); +" "; a("$PANEL/${project.id}/edit") { +"Обновить данные" } }
        section("card") {
            form(action = "$PANEL/${project.id}/edit", method = FormMethod.post) {
                hiddenCsrf(formToken); hiddenInput(name = "revision") { value = project.revision.toString() }
                projectFields(project.project, project.slug, project.routing, enabled)
                if (actor.role != IdentityRole.ADMIN) p("help") { +"После изменения имени, slug или способа публикации проект отправится на повторное одобрение." }
                button(type = ButtonType.submit) { +"Сохранить изменения" }
            }
        }
        if (actor.role == IdentityRole.ADMIN) section("card") {
            h2 { +"Владельцы проекта" }
            if (users.none { it.role == IdentityRole.OWNER }) p { +"Сначала добавьте пользователя в разделе «Пользователи»." }
            form(action = "$PANEL/${project.id}/owners", method = FormMethod.post) {
                hiddenCsrf(formToken); hiddenInput(name = "revision") { value = project.revision.toString() }
                users.filter { it.role == IdentityRole.OWNER }.forEach { user -> label("owner-option") {
                    checkBoxInput(name = "owner") { value = user.id; checked = user.id in owners }; +user.username
                } }
                button(type = ButtonType.submit) { +"Сохранить владельцев" }
            }
        }
        section("card details") {
            h2 { +"Адреса проекта" }
            if (project.routing.mode in listOf(RoutingMode.DOCKER, RoutingMode.PROXY)) {
                p("help") { +"Ссылки ниже перенаправляют на ${project.productionAppUrl} и ${project.stageAppUrl}." }
            }
            if (project.routing.mode == RoutingMode.REDIRECT) p("help") { +"Ссылки перенаправляют на ${project.routing.target}." }
            p("eyebrow mono") { +"PRODUCTION" }; p("mono") { a(project.productionUrl) { +project.productionUrl.removePrefix("https://") } }
            p("eyebrow mono") { +"STAGE" }; p("mono") { a(project.stageUrl) { +project.stageUrl.removePrefix("https://") } }
        }
    }
}

private fun FlowContent.hiddenCsrf(value: String) { hiddenInput(name = "csrf") { this.value = value } }
private fun FORM.projectFields(project: String, slug: String, routing: ProjectRouting = ProjectRouting(), enabled: Boolean) {
    div("field") { label { htmlFor = "project"; +"Имя проекта" }; textInput(name = "project") { id = "project"; value = project; required = true; attributes["maxlength"] = "64"; attributes["pattern"] = "[a-z0-9][a-z0-9_-]{0,63}"; attributes["autocomplete"] = "off" }; p("help") { +"Например, minor-website. Строчные латинские буквы, цифры, дефис и подчёркивание." } }
    div("field") { label { htmlFor = "slug"; +"Slug в адресе" }; textInput(name = "slug") { id = "slug"; value = slug; required = true; attributes["maxlength"] = "64"; attributes["pattern"] = "[a-z0-9][a-z0-9_-]{0,63}"; attributes["autocomplete"] = "off" }; p("help") { +"Ссылка на проект: /dev/project/<slug>/. Поддомен: <slug>.itzephir.com (stage: <slug>.dev.itzephir.com). Для поддомена — только буквы, цифры и дефисы, до 63 символов." } }
    if (!enabled) p("notice") { +"Маршрутизация поддоменов ещё не включена. Настройки можно сохранить заранее; существующие адреса продолжают работать." }
    div("field") {
        label { htmlFor = "routing-mode"; +"Способ публикации" }
        select { id = "routing-mode"; name = "routingMode"; RoutingMode.entries.forEach { mode -> option { value = mode.databaseValue; selected = mode == routing.mode; +mode.label() } } }
    }
    div("field") {
        label { htmlFor = "routing-target"; +"Адрес назначения" }
        textInput(name = "routingTarget") { id = "routing-target"; value = routing.target; attributes["maxlength"] = "2048"; attributes["autocomplete"] = "off"; attributes["spellcheck"] = "false" }
        p("help") { +"Контейнер: minor-web:8080. Сервер: http://127.0.0.1:8080 или https://example.org. Редирект: полный URL. У существующего маршрута оставьте поле пустым." }
        p("help") { +"Контейнер должен быть подключён к сети itzephir-projects-prod или itzephir-projects-dev. Приложение на поддомене работает от корня /." }
    }
}
private fun Parameters.routing(): ProjectRouting? {
    val value = this["routingMode"] ?: return null
    val mode = RoutingMode.entries.firstOrNull { it.databaseValue == value }
        ?: throw LocatorException(400, "invalid_routing", "Выберите способ публикации.")
    return ProjectRouting(mode, this["routingTarget"].orEmpty().trim())
}
private fun RoutingMode.label() = when (this) {
    RoutingMode.LEGACY -> "Существующий маршрут из CI"
    RoutingMode.DOCKER -> "Docker-контейнер на этой VM"
    RoutingMode.PROXY -> "Прокси на IP или домен"
    RoutingMode.REDIRECT -> "Редирект на внешний сайт"
}
private fun FlowContent.reviewForm(project: Project, action: String, label: String, formToken: String) {
    form(action = "$PANEL/${project.id}/$action", method = FormMethod.post) {
        hiddenCsrf(formToken); hiddenInput(name = "revision") { value = project.revision.toString() }; button(type = ButtonType.submit, classes = if (action == "reject") "secondary" else "") { +label }
    }
}
private fun FlowContent.pagination(base: String, offset: Int, step: Int, more: Boolean) {
    if (offset == 0 && !more) return
    nav("pagination") {
        val separator = if ('?' in base) '&' else '?'
        if (offset > 0) a("$base${separator}offset=${(offset - step).coerceAtLeast(0)}") { +"← Назад" }
        if (more) a("$base${separator}offset=${offset + step}") { +"Далее →" }
    }
}
private fun ProjectStatus.label() = when (this) { ProjectStatus.PENDING -> "На проверке"; ProjectStatus.APPROVED -> "Опубликован"; ProjectStatus.REJECTED -> "Отклонён" }
private fun LocatorException.friendly() = when (code) {
    "panel_unconfigured" -> "Панель пока недоступна."
    "github_unconfigured" -> "Вход через GitHub пока недоступен."
    "github_failed" -> "Не удалось войти через GitHub. Попробуйте снова."
    "account_conflict" -> "Этот логин или GitHub-аккаунт уже используется."
    "invalid_username" -> "Логин: от 3 до 64 строчных латинских букв, цифр, точек, дефисов или подчёркиваний."
    "weak_password" -> "Пароль должен содержать от 12 до 256 символов."
    "wrong_password" -> "Текущий пароль указан неверно."
    "invalid_owners" -> "Проверьте список владельцев проекта."
    "login_limited" -> "Слишком много попыток входа. Попробуйте через 15 минут."
    "invalid_session" -> "Сессия завершена. Войдите снова."
    "project_not_found" -> "Проект не найден или недоступен для вашей учётной записи."
    "admin_required" -> "Это действие доступно только администратору."
    "csrf_failed" -> "Форма устарела. Обновите страницу и повторите действие."
    "project_conflict" -> "Имя проекта или slug уже заняты."
    "stale_project" -> "Данные проекта уже изменились. Обновите страницу перед сохранением или одобрением."
    "invalid_routing" -> message
    "invalid_identifier" -> "Проверьте имя и slug: до 64 строчных латинских букв, цифр, дефисов или подчёркиваний."
    else -> "Не удалось выполнить действие. Обновите страницу и попробуйте снова."
}
