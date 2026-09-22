package github.magnusp.thoughtless.service

import github.magnusp.thoughtless.ai.OllamaChatMessage
import github.magnusp.thoughtless.ai.OllamaClient
import github.magnusp.thoughtless.domain.model.Spec
import github.magnusp.thoughtless.domain.repository.SpecRepository
import github.magnusp.thoughtless.util.currentTimeMillis
import github.magnusp.thoughtless.util.randomId

data class InterviewTurn(
    val question: String,
    val answer: String? = null,
)

data class SpecInterviewSession(
    val sessionId: String = randomId(),
    val projectId: String,
    val title: String,
    val initialGoal: String,
    val turns: MutableList<InterviewTurn> = mutableListOf(),
    var isReadyToGenerate: Boolean = false,
)

class SpecEngineService(
    private val ollamaClient: OllamaClient,
    private val specRepository: SpecRepository,
    private val markdownIngestionService: MarkdownIngestionService,
) {

    /**
     * Starts a new interview session and generates the first probing boundary question.
     */
    suspend fun startInterview(
        projectId: String,
        title: String,
        initialGoal: String,
    ): Pair<SpecInterviewSession, String> {
        val session = SpecInterviewSession(
            projectId = projectId,
            title = title,
            initialGoal = initialGoal,
        )

        val systemPrompt = """
            You are a Principal Software Architect conducting a structured spec interview.
            Your role is to drill down into boundaries, requirements, schemas, edge cases, and explicit non-goals.
            Keep questions targeted, concise, and focused on system architecture.
        """.trimIndent()

        val prompt = """
            We are designing the system '$title' for project '$projectId'.
            Goal: $initialGoal
            
            Ask the developer 2-3 essential clarifying questions about:
            1. Core inputs and expected outputs
            2. Explicit non-goals (what this feature must NOT do)
            3. Error handling or failure modes
        """.trimIndent()

        val response = ollamaClient.chat(prompt = prompt, systemPrompt = systemPrompt)
            ?: "1. What are the expected inputs and outputs?\n2. What are the explicit non-goals?\n3. How should errors be handled?"

        session.turns.add(InterviewTurn(question = response))
        return session to response
    }

    /**
     * Answers the current question and either asks a follow-up or signals ready to generate.
     */
    suspend fun submitAnswer(
        session: SpecInterviewSession,
        answer: String,
    ): String {
        val lastIndex = session.turns.size - 1
        if (lastIndex >= 0) {
            val lastTurn = session.turns[lastIndex]
            session.turns[lastIndex] = lastTurn.copy(answer = answer)
        }

        // Check if we have conducted sufficient turns (e.g. at least 2 questions answered)
        if (session.turns.size >= 2) {
            session.isReadyToGenerate = true
            return "All boundary questions answered. Ready to generate formal specification."
        }

        val messages = mutableListOf<OllamaChatMessage>()
        messages.add(OllamaChatMessage("system", "You are a Principal Architect guiding an engineering spec interview."))
        messages.add(OllamaChatMessage("user", "Goal: ${session.initialGoal}"))

        for (turn in session.turns) {
            messages.add(OllamaChatMessage("assistant", turn.question))
            turn.answer?.let { messages.add(OllamaChatMessage("user", it)) }
        }

        messages.add(OllamaChatMessage("user", "Based on my answer, ask any final edge case question or summarize boundary agreements."))

        val followUp = ollamaClient.chat(messages)
            ?: "Are there any backward-compatibility or performance constraints we should capture?"

        session.turns.add(InterviewTurn(question = followUp))
        return followUp
    }

    /**
     * Generates a frozen SPEC.md, ingests it into ArcadeDB graph nodes, and saves the Spec entity.
     */
    suspend fun generateAndFreezeSpec(
        session: SpecInterviewSession,
    ): Spec {
        val now = currentTimeMillis()
        val slug = slugify(session.title)
        val specId = "spec-${session.projectId}-$slug"
        val filePath = "specs/$slug.md"

        val prompt = """
            Synthesize the following interview into a formal engineering specification.
            Feature Title: ${session.title}
            Goal: ${session.initialGoal}
            Interview notes:
            ${session.turns.joinToString("\n") { "Q: ${it.question}\nA: ${it.answer ?: "N/A"}" }}
            
            Produce a clean markdown document with:
            - Overview and System Specification
            - Explicit Non-Goals
            - Architectural RFC / Details
            - Key Requirements & Entities with [[wikilinks]]
        """.trimIndent()

        val generatedDoc = ollamaClient.chat(prompt, systemPrompt = "You are a Principal Architect writing a formal engineering specification.")
            ?: """
                # ${session.title}
                
                ## System Specification
                ${session.initialGoal}
                
                ## Non-Goals
                Scope outside initial iteration.
                
                ## Architecture
                Core architecture and components.
            """.trimIndent()

        val fullMarkdown = buildString {
            appendLine("---")
            appendLine("id: $specId")
            appendLine("type: SPEC")
            appendLine("title: ${session.title}")
            appendLine("project: ${session.projectId}")
            appendLine("---")
            appendLine()
            appendLine(generatedDoc.trim())
        }

        // 1. Ingest markdown AST into ArcadeDB ContextNode & ContextEdge records
        markdownIngestionService.ingestDocument(
            content = fullMarkdown,
            filePath = filePath,
            defaultProjectId = session.projectId,
        )

        // 2. Extract nonGoals summary if present
        val nonGoals = extractSection(fullMarkdown, "Non-Goals")

        // 3. Persist Spec entity
        val spec = Spec(
            id = specId,
            projectId = session.projectId,
            title = session.title,
            systemSpec = session.initialGoal,
            nonGoals = nonGoals,
            rfcDocument = fullMarkdown,
            frozenAt = now,
        )

        specRepository.saveSpec(spec)
        return spec
    }

    private fun extractSection(content: String, headerName: String): String? {
        val lines = content.lines()
        var capturing = false
        val captured = mutableListOf<String>()

        for (line in lines) {
            if (line.trim().startsWith("#") && line.contains(headerName, ignoreCase = true)) {
                capturing = true
                continue
            }
            if (capturing) {
                if (line.trim().startsWith("#")) {
                    break
                }
                captured.add(line)
            }
        }
        val result = captured.joinToString("\n").trim()
        return result.ifBlank { null }
    }

    private fun slugify(text: String): String {
        return text.lowercase()
            .replace(Regex("""[^a-z0-9]+"""), "-")
            .trim('-')
    }
}
