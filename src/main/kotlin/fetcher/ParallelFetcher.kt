package fetcher

import fetcher.exception.GlobalTimeoutException
import fetcher.model.FetcherSettings
import fetcher.model.RequestData
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CoroutineScope
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

private val IMMEDIATE_EXECUTOR = { runnable: Runnable -> runnable.run() }

class ParallelFetcher(
    private val client: AsyncHttpClient,
    private val settings: FetcherSettings,
) {
    fun execute(requests: Collection<Request>): List<String> = runBlocking {
        executeInternal(requests.map { RequestData(it) })
    }

    private suspend fun executeInternal(
        requestDatas: Collection<RequestData>,
    ): List<String> {
        val parallelSlots = Channel<Unit>(capacity = settings.parallel, onBufferOverflow = BufferOverflow.SUSPEND)

        return try {
            withTimeout(settings.globalTimeout) {
                requestDatas.map { requestData ->
                    parallelSlots.send(Unit)
                    async {
                        executeRequest(requestData, this)
                        parallelSlots.receive()
                    }
                }.awaitAll()
            }
            requestDatas.map { it.response!! }
        } catch (e: TimeoutCancellationException) {
            return handleGlobalTimeout(requestDatas)
        }
    }

    private suspend fun executeRequest(
        requestData: RequestData,
        parentScope: CoroutineScope,
    ) {
        val retries = parentScope.async {
            repeat(settings.requestRetries + 1) {
                if (requestData.response == "Success") {
                    return@async
                }

                requestData.response = suspendCancellableCoroutine { continuation ->
                    executeRequestAsync(requestData) { result ->
                        continuation.resume(result)
                    }
                }
            }
        }

        retries.await()
    }

    private fun executeRequestAsync(
        requestData: RequestData,
        onFutureCompleted: (String) -> Unit,
    ): ListenableFuture<Response> {
        val requestBuilder = client.prepareRequest(requestData.request)
        requestBuilder.setRequestTimeout(settings.requestTimeout.inWholeMilliseconds.toInt())

        val futureResponse = requestBuilder.execute()
        requestData.callsInFly.add(futureResponse)
        futureResponse.addListener({
            requestData.callsInFly.remove(futureResponse)
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

        return futureResponse
    }

    private fun handleGlobalTimeout(
        requestDatas: Collection<RequestData>,
    ): List<String> {
        cancelCallsInFly(requestDatas)
        return getGlobalTimeoutResponse(requestDatas)
    }

    private fun cancelCallsInFly(
        requestDatas: Collection<RequestData>,
    ) {
        requestDatas.forEach { requestData ->
            requestData.callsInFly.forEach { callInFly ->
                callInFly.abort(GlobalTimeoutException())
            }
        }
    }

    private fun getGlobalTimeoutResponse(
        requestDatas: Collection<RequestData>,
    ): List<String> {
        return requestDatas.map { requestData ->
            if (requestData.response != null) {
                requestData.response!!
            } else {
                "Global timeout"
            }
        }
    }
}
