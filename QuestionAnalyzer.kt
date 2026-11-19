package com.stealth.bot.core

class QuestionAnalyzer {

    enum class QuestionType {
        MULTIPLE_CHOICE,
        YES_NO,
        NUMERIC,
        OPEN_TEXT
    }

    data class AnalysisResult(
        val type: QuestionType,
        val cleanedText: String,
        val isQuestion: Boolean,
        val suggestedMode: String
    )

    fun analyze(rawText: String): AnalysisResult {
        val cleaned = rawText.trim().replace(Regex("\\s+"), " ")
        if (cleaned.isEmpty()) {
            return AnalysisResult(
                type = QuestionType.OPEN_TEXT,
                cleanedText = "",
                isQuestion = false,
                suggestedMode = "idle"
            )
        }

        val lower = cleaned.lowercase()
        val hasQuestionMark = cleaned.endsWith("?") || cleaned.endsWith("？")
        val startsWithWhOrAux = Regex(
            "^(what|who|whom|whose|which|when|where|why|how|is|are|am|was|were|do|does|did|can|could|will|would|should|has|have|had)\\b"
        ).containsMatchIn(lower)

        val isQuestion = hasQuestionMark || startsWithWhOrAux

        val type = when {
            Regex("\\b(a|b|c|d)[\\).:]\\s+").containsMatchIn(lower) -> QuestionType.MULTIPLE_CHOICE
            Regex("\\b(yes|no|true|false)\\b").containsMatchIn(lower) && cleaned.length < 180 -> QuestionType.YES_NO
            Regex("\\b(calculate|how many|count|number|sum|total|value|digits?)\\b").containsMatchIn(lower) -> QuestionType.NUMERIC
            else -> QuestionType.OPEN_TEXT
        }

        val mode = when (type) {
            QuestionType.MULTIPLE_CHOICE -> "select_option"
            QuestionType.YES_NO -> "binary_choice"
            QuestionType.NUMERIC -> "numeric_answer"
            QuestionType.OPEN_TEXT -> if (isQuestion) "short_answer" else "read_only"
        }

        return AnalysisResult(
            type = type,
            cleanedText = cleaned,
            isQuestion = isQuestion,
            suggestedMode = mode
        )
    }

    fun extractShortAnswer(rawAnswer: String): String {
        val trimmed = rawAnswer.trim()
        if (trimmed.isEmpty()) return ""
        val firstLine = trimmed.lineSequence().firstOrNull()?.trim() ?: trimmed
        return if (firstLine.length > 160) firstLine.take(160).trim() else firstLine
    }
}
