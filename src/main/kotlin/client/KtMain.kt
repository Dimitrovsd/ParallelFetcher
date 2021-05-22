package client

import org.asynchttpclient.DefaultAsyncHttpClient
import org.asynchttpclient.RequestBuilder
import kotlin.time.Duration

fun main() {
    val client = DefaultAsyncHttpClient()
    val parallelFetcher = ParallelFetcher(client = client,
        settings = FetcherSettings(
            globalTimeout = Duration.INFINITE,
            parallel = 2,
            softTimeout = Duration.INFINITE))

    val requests = List(10) {
        RequestBuilder()
            .setUrl("http://127.0.0.1:8999")
            .build()
    }

    val responses = parallelFetcher.execute(requests)
    responses.forEach {
        println("Responses: $it")
    }
    client.close()
}
