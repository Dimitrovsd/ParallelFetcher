package fetcher

import fetcher.exception.CancelRequestException
import fetcher.exception.GlobalTimeoutException
import fetcher.model.FetcherSettings
import fetcher.model.NO_GLOBAL_RETRY_LIMIT
import fetcher.model.NO_TOTAL_RETRIES_LIMIT
import fetcher.model.RequestData
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.asynchttpclient.AsyncHttpClient
import org.asynchttpclient.ListenableFuture
import org.asynchttpclient.Request
import org.asynchttpclient.Response
import kotlin.coroutines.resume
import kotlin.math.floor

private val IMMEDIATE_EXECUTOR = { runnable: Runnable -> runnable.run() }

class ParallelFetcher(
    private val client: AsyncHttpClient,
    private val settings: FetcherSettings,
) {

    private val parallelSlots = Channel<Unit>(capacity = settings.parallel, onBufferOverflow = BufferOverflow.SUSPEND)
    private var globalRetries = 0
    private var globalRetriesLimit = NO_GLOBAL_RETRY_LIMIT

    fun execute(requests: Collection<Request>): List<String> = runBlocking {
        globalRetriesLimit = getGlobalRetriesLimit(requests.size)

        val requestDatas = requests.map { RequestData(it) }
        executeRequests(requestDatas)

        requestDatas.map { it.response!! }
    }

    private suspend fun executeRequests(
        requestDatas: Collection<RequestData>,
    ) {
        try {
            withTimeout(settings.globalTimeout) {
                requestDatas.map { requestData ->
                    async {
                        launch {
                            executeRequest(requestData, this)
                        }
                    }
                }.awaitAll()
            }
        } catch (e: TimeoutCancellationException) {
            handleGlobalTimeout(requestDatas)
        }
    }

    private suspend fun executeRequest(
        requestData: RequestData,
        requestScope: CoroutineScope,
    ) {
        try {
            repeat(settings.requestRetries + 1) { tryIndex ->
                if (!requestScope.isActive) {
                    println("Request scope is not active, no more work needed")
                    return
                }
                println("Preparing for hard retry...")
                executeTry(requestData, requestScope, tryIndex != 0)
            }
        } catch (e: CancellationException) {
            println("Request done or cancelled")
        }
    }

    private suspend fun executeTry(
        requestData: RequestData,
        requestScope: CoroutineScope,
        isRetry: Boolean = true,
    ) {
        if (isRetry && !canRetry()) {
            return
        }

        println("Preparing for try...")
        parallelSlots.send(Unit)
        println("Executing one try")

        if (!isRetry) {
            requestScope.launch {
                println("Preparing for soft retry...")
                delay(settings.softTimeout)
                println("Executing soft retry")
                executeTry(requestData, requestScope)
            }
        }

        val response: String = suspendCancellableCoroutine { continuation ->
            executeAsyncHttpCall(requestData) { result ->
                continuation.resume(result)
            }
        }
        println("Continuation resumed")
        parallelSlots.receive()

        println("Got response in executeTry: $response")
        processResponse(response, requestData, requestScope)
    }

    private fun canRetry(): Boolean {
        if (globalRetries >= globalRetriesLimit) {
            println("Can't execute a try: running out of global retries")
            return false
        }
        globalRetries++
        return true
    }

    private fun executeAsyncHttpCall(
        requestData: RequestData,
        onFutureCompleted: (String) -> Unit,
    ): ListenableFuture<Response> {
        println("Executing one async http call")
        val requestBuilder = client.prepareRequest(requestData.request)
        requestBuilder.setRequestTimeout(settings.requestTimeout.inWholeMilliseconds.toInt())

        val futureResponse = requestBuilder.execute()
        requestData.callsInFly.add(futureResponse)
        futureResponse.addListener({
            requestData.callsInFly.remove(futureResponse)
            try {
                val responseBody = futureResponse.get().responseBody
                println("Got successful response in callback: $responseBody")
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

    private fun processResponse(
        response: String,
        requestData: RequestData,
        requestScope: CoroutineScope,
    ) {
        requestData.response = response
        if (response == "Success") {
            onSuccess(requestData, requestScope)
        }
    }

    private fun onSuccess(
        requestData: RequestData,
        parentScope: CoroutineScope,
    ) {
        println("onSuccess")
        parentScope.cancel()
        requestData.cancelCallsInFly(CancelRequestException())
    }

    private fun handleGlobalTimeout(
        requestDatas: Collection<RequestData>,
    ) {
        requestDatas.cancelCallsInFly(GlobalTimeoutException())
        fillGlobalTimeoutResponse(requestDatas)
    }

    private fun Collection<RequestData>.cancelCallsInFly(
        exception: Exception,
    ) {
        this.forEach { requestData ->
            requestData.cancelCallsInFly(exception)
        }
    }

    private fun RequestData.cancelCallsInFly(
        exception: Exception,
    ) {
        this.callsInFly.forEach { callInFly ->
            callInFly.abort(exception)
        }
    }

    private fun fillGlobalTimeoutResponse(
        requestDatas: Collection<RequestData>,
    ) {
        requestDatas.forEach { requestData ->
            requestData.response = requestData.response ?: "Global timeout"
        }
    }

    private fun getGlobalRetriesLimit(
        requestCount: Int,
    ): Int {
        val requestRetries: Int = settings.requestRetries
        val totalRetriesCoef: Double = settings.totalRetriesCoef

        return if (totalRetriesCoef == NO_TOTAL_RETRIES_LIMIT) {
            NO_GLOBAL_RETRY_LIMIT
        } else {
            floor(requestRetries + totalRetriesCoef * requestCount).toInt()
        }
    }
}
