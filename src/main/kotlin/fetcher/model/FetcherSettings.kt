package fetcher.model

import kotlin.time.Duration

private val DEFAULT_REQUEST_TIMEOUT = Duration.minutes(5)
private const val NO_TOTAL_RETRIES_LIMIT = 0.0

data class FetcherSettings(
    /**
     * Supported features
     */
    val parallel: Int = 1,
    val globalTimeout: Duration,
    val requestTimeout: Duration = DEFAULT_REQUEST_TIMEOUT,
    val requestRetries: Int = 0,
    val softTimeout: Duration,

    /**
     * Unsupported features
     */
    val totalRetriesCoef: Double = NO_TOTAL_RETRIES_LIMIT,
    val failFast: Boolean = false,
)
