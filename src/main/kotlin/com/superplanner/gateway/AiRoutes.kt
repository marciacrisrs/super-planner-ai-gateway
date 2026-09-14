package com.superplanner.gateway

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

@Serializable
data class GenerateAiRequest(
    val prompt: String
)

@Serializable
data class GenerateAiResponse(
    val text: String,
    val model: String
)

fun Route.aiRoutes(
    geminiService: GeminiService,
    organizeWeekService: OrganizeWeekService
) {
    post("/v1/ai/generate") {
        val request = call.receive<GenerateAiRequest>()
        if (request.prompt.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "prompt must not be blank"))
            return@post
        }

        val text = geminiService.generate(request.prompt)
        call.respond(
            GenerateAiResponse(
                text = text,
                model = geminiService.modelName
            )
        )
    }

    post("/v1/ai/organize-week") {
        val request = call.receive<OrganizeWeekRequest>()
        val response = organizeWeekService.organize(request)
        call.respond(response)
    }
}
