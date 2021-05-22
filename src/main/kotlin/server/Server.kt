package server

import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.HandlerList
import org.eclipse.jetty.servlet.ServletContextHandler
import org.eclipse.jetty.servlet.ServletHolder

fun main() {
    val server = Server(8999)

    val context = ServletContextHandler(ServletContextHandler.SESSIONS)
    context.contextPath = "/"
    context.addServlet(ServletHolder(SimpleServlet()), "/")

    val handlers = HandlerList()
    handlers.handlers = arrayOf(context)
    server.handler = handlers

    server.start()
}
