package fetcher

import java.util.concurrent.CopyOnWriteArrayList
import org.asynchttpclient.ListenableFuture
import org.asynchttpclient.Request
import org.asynchttpclient.Response

class RequestData(
    val request: Request,
) {
    var requestsDone = 0
    val callsInFly = CopyOnWriteArrayList<ListenableFuture<Response>>()
    var response: String? = null
}
