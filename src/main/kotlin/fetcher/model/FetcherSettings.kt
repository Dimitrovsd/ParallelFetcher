package fetcher.model

import kotlin.time.Duration

private val DEFAULT_REQUEST_TIMEOUT = Duration.minutes(5)
const val NO_TOTAL_RETRIES_LIMIT = 0.0
const val NO_GLOBAL_RETRY_LIMIT = Int.MAX_VALUE

data class FetcherSettings(
    val parallel: Int = 1,
    val globalTimeout: Duration,
    val requestTimeout: Duration = DEFAULT_REQUEST_TIMEOUT,
    val requestRetries: Int = 0,
    val softTimeout: Duration,
    val totalRetriesCoef: Double = NO_TOTAL_RETRIES_LIMIT,
    val failFast: Boolean = false,
)
