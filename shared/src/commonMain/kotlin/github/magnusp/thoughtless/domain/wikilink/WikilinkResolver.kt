package github.magnusp.thoughtless.domain.wikilink

data class ResolvedWikilink(
    val rawMatch: String,
    val targetNodeId: String,
    val documentId: String,
    val sectionAnchor: String? = null,
    val relation: String? = null,
    val alias: String? = null,
    val startIndex: Int,
    val endIndex: Int,
)

object WikilinkResolver {

    private val WIKILINK_REGEX = Regex("""\[\[(.*?)\]\]""")

    /**
     * Extracts all wikilinks from the given text along with their character ranges and parsed components.
     */
    fun extractWikilinks(text: String, currentDocumentId: String? = null): List<ResolvedWikilink> {
        val results = mutableListOf<ResolvedWikilink>()
        for (match in WIKILINK_REGEX.findAll(text)) {
            val rawInside = match.groupValues[1].trim()
            if (rawInside.isNotBlank()) {
                val parsed = parseWikilinkContent(rawInside, currentDocumentId)
                results.add(
                    ResolvedWikilink(
                        rawMatch = match.value,
                        targetNodeId = parsed.targetNodeId,
                        documentId = parsed.documentId,
                        sectionAnchor = parsed.sectionAnchor,
                        relation = parsed.relation,
                        alias = parsed.alias,
                        startIndex = match.range.first,
                        endIndex = match.range.last + 1,
                    )
                )
            }
        }
        return results
    }

    /**
     * Finds the wikilink located at the specified character offset, if any.
     */
    fun findWikilinkAtOffset(text: String, offset: Int, currentDocumentId: String? = null): ResolvedWikilink? {
        if (offset < 0 || offset > text.length) return null
        val links = extractWikilinks(text, currentDocumentId)
        return links.firstOrNull { offset >= it.startIndex && offset < it.endIndex }
    }

    private data class ParsedContent(
        val targetNodeId: String,
        val documentId: String,
        val sectionAnchor: String?,
        val relation: String?,
        val alias: String?,
    )

    private fun parseWikilinkContent(rawContent: String, currentDocumentId: String?): ParsedContent {
        // 1. Alias: [[target|Display text]]
        val alias = if (rawContent.contains('|')) rawContent.substringAfter('|').trim() else null
        val withoutAlias = rawContent.substringBefore('|').trim()

        // 2. Relation prefix: [[references:target]], [[depends_on:target]]
        val colonIndex = withoutAlias.indexOf(':')
        val (relation, targetWithAnchor) = if (colonIndex > 0) {
            val prefix = withoutAlias.substring(0, colonIndex).trim().uppercase()
            val rem = withoutAlias.substring(colonIndex + 1).trim()
            val validRelation = when (prefix) {
                "IMPLEMENTS" -> "IMPLEMENTS"
                "MUTATES" -> "MUTATES"
                "DEPENDS_ON", "DEPENDS" -> "DEPENDS_ON"
                "REFERENCES", "REF" -> "REFERENCES"
                else -> prefix
            }
            Pair(validRelation, rem)
        } else {
            Pair("REFERENCES", withoutAlias)
        }

        // 3. Section Anchor: [[target#anchor]] or [[#anchor]]
        val hashIndex = targetWithAnchor.indexOf('#')
        val (targetDocId, anchor) = if (hashIndex >= 0) {
            val docPart = targetWithAnchor.substring(0, hashIndex).trim()
            val anchorPart = targetWithAnchor.substring(hashIndex + 1).trim()
            val effectiveDocId = if (docPart.isNotBlank()) docPart else (currentDocumentId ?: "")
            Pair(effectiveDocId, anchorPart.ifBlank { null })
        } else {
            Pair(targetWithAnchor, null)
        }

        val fullTargetNodeId = if (anchor != null) {
            if (targetDocId.isNotBlank()) "$targetDocId#$anchor" else "#$anchor"
        } else {
            targetDocId
        }

        return ParsedContent(
            targetNodeId = fullTargetNodeId,
            documentId = targetDocId,
            sectionAnchor = anchor,
            relation = relation,
            alias = alias,
        )
    }
}
