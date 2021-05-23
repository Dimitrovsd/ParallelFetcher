package server

import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

class SimpleServlet : HttpServlet() {

    var counter = 0

    override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
        counter = counter xor 1

        // imitating hard work
        if (counter == 0) {
            Thread.sleep(5000)
        } else {
            Thread.sleep(2000)
        }

        resp.outputStream.print("Success")
    }
}
