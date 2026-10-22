package com.itzephir.locator

import java.net.URI
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class RoutingMode {
    @SerialName("legacy") LEGACY,
    @SerialName("docker") DOCKER,
    @SerialName("proxy") PROXY,
    @SerialName("redirect") REDIRECT;

    val databaseValue get() = name.lowercase()
}

@Serializable
data class ProjectRouting(val mode: RoutingMode = RoutingMode.LEGACY, val target: String = "") {
    fun validate(slug: String) {
        if (mode in listOf(RoutingMode.DOCKER, RoutingMode.PROXY) &&
            (!DNS_LABEL.matches(slug) || slug in RESERVED)) invalid("Для поддомена используйте до 63 букв, цифр и дефисов; dev и www зарезервированы.")
        if (mode == RoutingMode.LEGACY) {
            if (target.isNotEmpty()) invalid("У существующего маршрута нет адреса назначения.")
            return
        }
        if (target.length !in 1..2048 || target.any { it.code <= 32 || it.code >= 127 } || '\\' in target) invalid("Проверьте адрес назначения.")
        if (mode == RoutingMode.DOCKER) {
            val parts = target.split(':')
            if (parts.size != 2 || !CONTAINER.matches(parts[0]) || !Regex("[0-9]{1,5}").matches(parts[1]) || parts[1].toIntOrNull() !in 1..65535) invalid("Укажите имя контейнера и порт, например minor-web:8080.")
            return
        }
        val uri = runCatching { URI(target) }.getOrNull() ?: invalid("Укажите полный HTTP или HTTPS адрес.")
        if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrEmpty() || uri.rawUserInfo != null ||
            uri.port !in -1..65535 || uri.port == 0 || uri.rawAuthority.endsWith(':')) invalid("Укажите HTTP или HTTPS адрес без логина и пароля.")
        if (mode == RoutingMode.PROXY) {
            if (uri.rawQuery != null || uri.rawFragment != null) invalid("Адрес прокси не должен содержать параметры или #fragment.")
            val host = uri.host.lowercase().removeSuffix(".")
            if (host == "itzephir.com" || host.endsWith(".itzephir.com")) invalid("Адрес прокси не может вести обратно на itzephir.com.")
        }
    }

    internal fun upstream(): String = when (mode) {
        RoutingMode.DOCKER -> "http://$target"
        RoutingMode.PROXY -> target.trimEnd('/')
        else -> error("No upstream for $mode")
    }

    companion object {
        private val DNS_LABEL = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
        private val CONTAINER = Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,127}")
        private val RESERVED = setOf("dev", "www")
        private fun invalid(message: String): Nothing = throw LocatorException(400, "invalid_routing", message)
    }
}
