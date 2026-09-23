package app.web

import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.routing.*

fun Route.staticRoutes() {
    staticResources("/static", "static")
}
