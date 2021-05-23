package fetcher

import org.asynchttpclient.DefaultAsyncHttpClient
import org.asynchttpclient.RequestBuilder
import kotlin.time.Duration

fun main() {
    val client = DefaultAsyncHttpClient()
    val parallelFetcher = ParallelFetcher(client = client,
        settings = FetcherSettings(
            globalTimeout = Duration.seconds(100000),
            parallel = 1,
            softTimeout = Duration.seconds(2),
            requestTimeout = Duration.seconds(3),
            requestRetries = 5,
        ))

    val requests = List(1) {
        RequestBuilder()
            .setUrl("http://127.0.0.1:8999")
            .build()
    }

    try {
        val responses = parallelFetcher.execute(requests)
        println("Responses: $responses")
    } catch (e: Exception) {
        println(e)
    }

    client.close()
}
