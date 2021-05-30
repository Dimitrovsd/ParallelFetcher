package fetcher

import fetcher.exception.CancelRequestException
import fetcher.exception.GlobalTimeoutException
import fetcher.exception.RequestTimeoutException
import fetcher.model.FetcherSettings
import fetcher.model.NO_GLOBAL_RETRY_LIMIT
import fetcher.model.RequestData
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    private val parallelSlots = Channel<Unit>(capacity = settings.parallel, onBufferOverflow = BufferOverflow.SUSPEND)
    private var globalRetries = 0
    private var globalRetriesLimit = NO_GLOBAL_RETRY_LIMIT

    fun execute(requests: Collection<Request>): List<String> = runBlocking {
        globalRetriesLimit = settings.getGlobalRetriesLimit(requests.size)

        val requestDatas = requests.mapIndexed { index, request ->
            RequestData(index.toLong(), request)
        }
        executeRequests(requestDatas)

        requestDatas.map { it.response!! }
    }

    private suspend fun executeRequests(requestDatas: Collection<RequestData>) {
        try {
            withTimeout(settings.globalTimeout) {
                requestDatas.forEach { requestData ->
                    executeRequest(requestData, this)
                }
            }
        } catch (e: TimeoutCancellationException) {
            handleGlobalTimeout(requestDatas)
        } catch (e: CancellationException) {
            handleFailFast(requestDatas)
        }
    }

    private suspend fun executeRequest(requestData: RequestData, requestsScope: CoroutineScope) {
        try {
            requestsScope.launch {
                executeTry(requestData, this, requestsScope, true)
            }
        } catch (e: CancellationException) {
            handleRequestCancel(requestData)
        }
    }

    private suspend fun executeTry(
        requestData: RequestData,
        requestScope: CoroutineScope,
        requestsScope: CoroutineScope,
        withSoftRetry: Boolean = false,
    ) {
        log(requestData, "waiting for slot...")
        parallelSlots.send(Unit)
        log(requestData, "executing one try")

        if (withSoftRetry) {
            executeSoftRetry(requestData, requestScope, requestsScope)
        }

        val response = executeCall(requestData)
        log(requestData, "releasing a slot")
        parallelSlots.receive()

        log(requestData, "got response in executeTry: $response")
        processResponse(response, requestData, requestScope, requestsScope)
    }

    private suspend fun executeSoftRetry(
        requestData: RequestData,
        requestScope: CoroutineScope,
        requestsScope: CoroutineScope,
    ) {
        requestScope.launch {
            log(requestData, "waiting for slot for soft retry...")
            delay(settings.softTimeout)
            log(requestData, "executing soft retry")

            if (requestData.retriesCount == 0) {
                executeRetry(requestData, requestScope, requestsScope)
            }
        }
    }

    private suspend fun executeRetry(
        requestData: RequestData,
        requestScope: CoroutineScope,
        requestsScope: CoroutineScope,
    ) {
        if (!canRetry(requestData)) {
            log(requestData, "can't execute a try -- running out of retries")
            return
        }

        requestData.retriesCount++
        globalRetries++

        executeTry(requestData, requestScope, requestsScope)
    }

    private fun canRetry(requestData: RequestData): Boolean {
        return requestData.retriesCount < settings.requestRetries && globalRetries < globalRetriesLimit
    }

    private suspend fun executeCall(requestData: RequestData): String {
        var futureResponse: ListenableFuture<Response>? = null

        return try {
            withTimeout(settings.requestTimeout) {
                log(requestData, "suspending coroutine for http call")
                val response: String = suspendCancellableCoroutine { continuation ->
                    futureResponse = executeAsyncHttpCall(requestData) { result ->
                        continuation.resume(result)
                    }
                }
                log(requestData, "resuming coroutine")

                response
            }
        } catch (e: CancellationException) {
            log(requestData, "call cancelled due to request timeout")
            futureResponse?.abort(RequestTimeoutException())

            "Request timeout"
        }
    }

    private fun executeAsyncHttpCall(
        requestData: RequestData,
        onFutureCompleted: (String) -> Unit,
    ): ListenableFuture<Response> {
        log(requestData, "executing one async http call")

        val futureResponse = client.executeRequest(requestData.request)
        requestData.callsInFly.add(futureResponse)
        futureResponse.addListener({
            requestData.callsInFly.remove(futureResponse)
            try {
                val responseBody = futureResponse.get().responseBody
                log(requestData, "got successful response in callback: $responseBody")
                onFutureCompleted(responseBody)
            } catch (e: ExecutionException) {
                log(requestData, "got exception during request: $e")
                onFutureCompleted("Unknown failure")
            }
        }, IMMEDIATE_EXECUTOR)

        return futureResponse
    }

    private suspend fun processResponse(
        response: String,
        requestData: RequestData,
        requestScope: CoroutineScope,
        requestsScope: CoroutineScope,
    ) {
        requestData.response = response
        if (response == "Success") {
            onSuccess(requestData, requestScope)
        } else {
            onFailure(requestData, requestScope, requestsScope)
        }
    }

    private fun onSuccess(
        requestData: RequestData,
        requestScope: CoroutineScope,
    ) {
        log(requestData, "onSuccess")
        requestScope.cancel()
    }

    private suspend fun onFailure(
        requestData: RequestData,
        requestScope: CoroutineScope,
        requestsScope: CoroutineScope
    ) {
        log(requestData, "onFailure")

        val requestFailed = !canRetry(requestData) && requestData.callsInFly.isEmpty()
        if (!requestFailed) {
            executeRetry(requestData, requestScope, requestsScope)
        } else {
            if (settings.failFast) {
                requestsScope.cancel()
            } else {
                // sortRetry корутина может еще быть не завершена к этому моменту, нужно явно отменить запрос
                requestScope.cancel()
            }
        }
    }

    private fun handleGlobalTimeout(
        requestDatas: Collection<RequestData>,
    ) {
        println("handleGlobalTimeout")
        requestDatas.cancelCallsInFly(GlobalTimeoutException())
        fillGlobalTimeoutResponse(requestDatas)
    }

    private fun handleFailFast(
        requestDatas: Collection<RequestData>,
    ) {
        println("handleFailFast")
        requestDatas.cancelCallsInFly(GlobalTimeoutException())
        fillFailFastResponse(requestDatas)
    }

    private fun handleRequestCancel(
        requestData: RequestData,
    ) {
        log(requestData, "request done or cancelled")
        requestData.cancelCallsInFly(CancelRequestException())
    }

    private fun Collection<RequestData>.cancelCallsInFly(
        exception: Exception,
    ) {
        forEach { requestData ->
            requestData.cancelCallsInFly(exception)
        }
    }

    private fun RequestData.cancelCallsInFly(
        exception: Exception,
    ) {
        callsInFly.forEach { callInFly ->
            callInFly.abort(exception)
        }
    }
}
