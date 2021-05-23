package sandbox

import java.lang.IllegalStateException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.suspendCoroutine

fun timeoutNonCancellable() {
    runBlocking {
        try {
            withTimeout(1000) {
                async {
                    suspendCoroutine<String> { continuation ->
                    }
                }
            }
        } catch (e: Exception) {
            "timeout"
        }
    }

    throw IllegalStateException("Never reaches")
}

fun cancelParent() {
    runBlocking {
        async {
            repeat(10) {
                delay(1000)
                println("A: $it")
            }
        }
        async {
            repeat(10) {
                delay(1000)
                println("B: $it")
            }
        }
        delay(2500)
        cancel()
    }
}

fun main() {
    cancelParent()
}
