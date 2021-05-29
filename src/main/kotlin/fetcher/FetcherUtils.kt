package fetcher

import fetcher.model.FetcherSettings
import fetcher.model.NO_GLOBAL_RETRY_LIMIT
import fetcher.model.NO_TOTAL_RETRIES_LIMIT
import fetcher.model.RequestData
import kotlin.math.floor

fun fillGlobalTimeoutResponse(requestDatas: Collection<RequestData>) = fillResponse(requestDatas, "Global timeout")
fun fillFailFastResponse(requestDatas: Collection<RequestData>) = fillResponse(requestDatas, "Fail fast")

fun fillResponse(
    requestDatas: Collection<RequestData>,
    fallbackResponse: String,
) {
    requestDatas.forEach { requestData ->
        requestData.response = requestData.response ?: fallbackResponse
    }
}

fun FetcherSettings.getGlobalRetriesLimit(
    requestCount: Int,
): Int {
    return if (totalRetriesCoef == NO_TOTAL_RETRIES_LIMIT) {
        NO_GLOBAL_RETRY_LIMIT
    } else {
        floor(requestRetries + totalRetriesCoef * requestCount).toInt()
    }
}

fun log(
    requestData: RequestData,
    message: String,
) {
    println("Request ${requestData.id}: $message")
}
