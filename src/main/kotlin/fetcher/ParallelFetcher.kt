package fetcher

import fetcher.exception.GlobalTimeoutException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.asynchttpclient.AsyncHttpClient
import org.asynchttpclient.ListenableFuture
import org.asynchttpclient.Request
import org.asynchttpclient.Response
import kotlin.coroutines.resume
import kotlin.time.Duration

private val DEFAULT_REQUEST_TIMEOUT = Duration.minutes(5)
private const val NO_TOTAL_RETRIES_LIMIT = 0.0

private val IMMEDIATE_EXECUTOR = { runnable: Runnable -> runnable.run() }

data class FetcherSettings(
    /**
     * Supported features
     */
    val parallel: Int = 1,
    val globalTimeout: Duration,
    val requestTimeout: Duration = DEFAULT_REQUEST_TIMEOUT,

    /**
     * Unsupported features
     */
    val softTimeout: Duration,
    val totalRetriesCoef: Double = NO_TOTAL_RETRIES_LIMIT,
    val requestRetries: Int = 0,
    val failFast: Boolean = false,
)

class ParallelFetcher(
    private val client: AsyncHttpClient,
    private val settings: FetcherSettings,
) {
    fun execute(requests: Collection<Request>): List<String> = runBlocking {
        executeInternal(requests)
    }

    private suspend fun executeInternal(
        requests: Collection<Request>,
    ): List<String> {
        val parallelSlots = Channel<Unit>(capacity = settings.parallel, onBufferOverflow = BufferOverflow.SUSPEND)
        val httpClientCalls = mutableListOf<ListenableFuture<Response>>()
        val asyncTasks = mutableListOf<Deferred<String>>()

        return try {
            withTimeout(settings.globalTimeout) {
                requests.forEach {
                    parallelSlots.send(Unit)
                    asyncTasks.add(async {
                        val response: String = suspendCancellableCoroutine { continuation ->
                            httpClientCalls.add(executeRequest(it) { result ->
                                continuation.resume(result)
                            })
                        }
                        parallelSlots.receive()
                        response
                    })
                }
                asyncTasks.awaitAll()
            }
        } catch (e: TimeoutCancellationException) {
            httpClientCalls.forEach {
                it.abort(GlobalTimeoutException())
            }
            return getTimeoutResponse(asyncTasks)
        }
    }

    private fun executeRequest(
        request: Request,
        onFutureCompleted: (String) -> Unit,
    ): ListenableFuture<Response> {
        val requestBuilder = client.prepareRequest(request)
        requestBuilder.setRequestTimeout(settings.requestTimeout.inWholeMilliseconds.toInt())

        val futureResponse = requestBuilder.execute()
        return futureResponse.addListener({
            try {
                val responseBody = futureResponse.get().responseBody
                println("Got successful response: $responseBody")
                onFutureCompleted(responseBody)
            } catch (e: ExecutionException) {
                println("Got exception during request: $e")
                if (e.cause is TimeoutException) {
                    onFutureCompleted("Request timeout")
                }
            }
        }, IMMEDIATE_EXECUTOR)
    }

    private fun getTimeoutResponse(
        asyncTasks: Collection<Deferred<String>>,
    ): List<String> {
        return asyncTasks.map {
            if (it.isCompleted && !it.isCancelled) {
                it.getCompleted()
            } else {
                "Global timeout"
            }
        }
    }
}
