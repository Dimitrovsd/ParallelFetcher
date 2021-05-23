package sandbox

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

fun main() {
    val res = runBlocking {
        try {
            withTimeout(1000) {
                async {
                    suspendCancellableCoroutine<String> { continuation ->

                    }
                }
            }
        } catch (e: Exception) {
            "timeout"
        }
    }
    println(res)
}
