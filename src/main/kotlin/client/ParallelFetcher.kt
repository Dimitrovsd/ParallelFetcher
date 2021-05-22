package client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.asynchttpclient.AsyncHttpClient
import org.asynchttpclient.Request
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration

private val DEFAULT_CONNECTION_TIMEOUT = Duration.milliseconds(100)
private val DEFAULT_REQUEST_TIMEOUT = Duration.minutes(5)
private const val NO_TOTAL_RETRIES_LIMIT = 0.0

data class FetcherSettings(
    val parallel: Int = 1, // used
    val globalTimeout: Duration, // used
    val softTimeout: Duration,
    val requestTimeout: Duration = DEFAULT_REQUEST_TIMEOUT, // in progress
    val connectTimeout: Duration = DEFAULT_CONNECTION_TIMEOUT,
    val totalRetriesCoef: Double = NO_TOTAL_RETRIES_LIMIT,
    val requestRetries: Int = 0,
    val failFast: Boolean = false,
)

class ParallelFetcher(
    private val client: AsyncHttpClient,
    private val settings: FetcherSettings,
) {
    fun execute(requests: Collection<Request>): List<String> = runBlocking {
        withTimeout(settings.globalTimeout) {
            executeInternal(requests, this)
        }
    }

    private suspend fun executeInternal(
        requests: Collection<Request>,
        coroutineScope: CoroutineScope
    ): List<String> {
        val channel = Channel<Unit>(capacity = settings.parallel, onBufferOverflow = BufferOverflow.SUSPEND)

        return requests.map {
            channel.send(Unit)
            val futureResponse: Deferred<String> = coroutineScope.async {
                val response: String = suspendCoroutine { continuation ->
                    executeRequest(it) { result ->
                        continuation.resume(result)
                    }
                }
                channel.receive()
                response
            }
            futureResponse
        }.awaitAll()
    }

    private fun executeRequest(request: Request, callback: (String) -> Unit) {
        val futureResponse = client.executeRequest(request)
        futureResponse.addListener({
            val response = futureResponse.get().responseBody
            println("Got response")
            callback(response)
        }, {
            it.run()
        })
    }
}
