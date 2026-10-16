package com.itzephir.website

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/** Direction labels are only a cold-start fallback; member names come from the API. */
data class WebringNeighbors(val previous: String = "Предыдущий", val next: String = "Следующий")

internal fun parseWebringNeighbors(body: String): WebringNeighbors {
    val data = Json.parseToJsonElement(body).jsonObject
    require(data.getValue("curr").jsonObject.getValue("slug").jsonPrimitive.content == "itzephir")
    fun name(direction: String) = data.getValue(direction).jsonObject.getValue("name").jsonPrimitive.content.also {
        require(it.isNotBlank() && it.length <= 200 && it.none(Char::isISOControl))
    }
    return WebringNeighbors(name("prev"), name("next"))
}

private val ringHttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build() }

private fun fetchWebringNeighbors(): CompletableFuture<WebringNeighbors> {
    val request = HttpRequest.newBuilder(URI("https://webring.otomir23.me/itzephir/data"))
        .timeout(Duration.ofSeconds(3)).header("Accept", "application/json").GET().build()
    return ringHttpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply {
        check(it.statusCode() == 200) { "Webring returned ${it.statusCode()}" }
        parseWebringNeighbors(it.body())
    }
}

/** Serve the last known names immediately; an external outage must not delay HTML. */
class WebringDirectory(
    private val fetch: () -> CompletableFuture<WebringNeighbors> = ::fetchWebringNeighbors,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Volatile private var cached = WebringNeighbors()
    @Volatile private var refreshAfter = 0L
    private val refreshing = AtomicBoolean(false)

    fun neighbors(): WebringNeighbors {
        if (now() >= refreshAfter && refreshing.compareAndSet(false, true)) {
            val request = try { fetch() } catch (failure: Exception) { CompletableFuture.failedFuture(failure) }
            request.whenComplete { names, failure ->
                if (failure == null && names != null) cached = names
                refreshAfter = now() + if (failure == null) 300_000 else 60_000
                refreshing.set(false)
            }
        }
        return cached
    }
}
