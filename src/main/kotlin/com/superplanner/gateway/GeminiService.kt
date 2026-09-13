package com.superplanner.gateway

import com.google.genai.Client

class GeminiService(
    private val project: String = requiredEnv("GOOGLE_CLOUD_PROJECT"),
    private val location: String = System.getenv("GOOGLE_CLOUD_LOCATION") ?: "us-central1",
    private val model: String = System.getenv("GEMINI_MODEL") ?: "gemini-2.5-flash"
) {
    private val client: Client = Client.builder()
        .project(project)
        .location(location)
        .enterprise(true)
        .build()

    fun generate(prompt: String): String {
        require(prompt.isNotBlank()) { "prompt must not be blank" }
        return client.models.generateContent(model, prompt, null).text()
            ?: throw IllegalStateException("Gemini returned an empty response")
    }

    companion object {
        private fun requiredEnv(name: String): String =
            System.getenv(name)?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Missing required environment variable: $name")
    }
}
