package github.magnusp.thoughtless.service

import com.vladsch.flexmark.ast.Heading
import com.vladsch.flexmark.ext.yaml.front.matter.AbstractYamlFrontMatterVisitor
import com.vladsch.flexmark.ext.yaml.front.matter.YamlFrontMatterExtension
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.ast.Node
import com.vladsch.flexmark.util.data.MutableDataSet
import github.magnusp.thoughtless.domain.model.ContextEdge
import github.magnusp.thoughtless.domain.model.ContextNode
import github.magnusp.thoughtless.domain.model.NodeType
import github.magnusp.thoughtless.domain.repository.ContextGraphRepository
import github.magnusp.thoughtless.util.currentTimeMillis
import github.magnusp.thoughtless.util.randomId

data class IngestionResult(
    val rootNode: ContextNode,
    val sectionNodes: List<ContextNode>,
    val edges: List<ContextEdge>,
)

class MarkdownIngestionService(
    private val graphRepository: ContextGraphRepository? = null,
) {
    private val parser: Parser

    init {
        val options = MutableDataSet()
        options.set(Parser.EXTENSIONS, listOf(
            YamlFrontMatterExtension.create(),
        ))
        parser = Parser.builder(options).build()
    }

    /**
     * Parses Markdown content into an [IngestionResult] containing structured ContextNodes and ContextEdges.
     */
    fun parse(
        content: String,
        filePath: String? = null,
        defaultProjectId: String? = null,
        defaultNodeId: String? = null,
    ): IngestionResult {
        val now = currentTimeMillis()
        val document = parser.parse(content)

        // 1. Extract YAML Frontmatter
        val yamlVisitor = AbstractYamlFrontMatterVisitor()
        yamlVisitor.visit(document)
        val frontMatter = yamlVisitor.data ?: emptyMap()

        val rawId = frontMatter["id"]?.firstOrNull()?.trim()
        val rawType = frontMatter["type"]?.firstOrNull()?.trim()
        val rawLabel = frontMatter["label"]?.firstOrNull()?.trim() ?: frontMatter["title"]?.firstOrNull()?.trim()
        val rawProjectId = frontMatter["project"]?.firstOrNull()?.trim()
            ?: frontMatter["projectId"]?.firstOrNull()?.trim()
            ?: defaultProjectId

        val rootType = if (!rawType.isNullOrBlank()) {
            try {
                NodeType.valueOf(rawType.uppercase())
            } catch (_: Exception) {
                NodeType.SPEC
            }
        } else {
            NodeType.SPEC
        }

        // Find primary H1 heading if present
        var firstH1Heading: Heading? = null
        for (child in document.children) {
            if (child is Heading && child.level == 1) {
                firstH1Heading = child
                break
            }
        }

        val rootLabel = rawLabel
            ?: firstH1Heading?.text?.toString()?.trim()
            ?: filePath?.substringAfterLast('/')?.substringBeforeLast('.')
            ?: "Untitled Document"

        val rootNodeId = if (!rawId.isNullOrBlank()) rawId else (defaultNodeId ?: filePath ?: randomId())

        val rootNode = ContextNode(
            id = rootNodeId,
            type = rootType,
            label = rootLabel,
            body = content,
            filePath = filePath,
            createdAt = now,
            updatedAt = now,
        )

        // 2. Extract Headings Hierarchy and Section Nodes (subheadings under the document root)
        val sectionNodes = mutableListOf<ContextNode>()
        val containmentEdges = mutableListOf<ContextEdge>()

        val sections = extractSections(document, rootNodeId, firstH1Heading, filePath, now)
        sectionNodes.addAll(sections.first)
        containmentEdges.addAll(sections.second)

        // 3. Extract Wikilinks and Relations
        val wikilinkEdges = extractWikilinks(content, rootNodeId, now)

        val allEdges = (containmentEdges + wikilinkEdges).distinctBy { it.id }

        return IngestionResult(
            rootNode = rootNode,
            sectionNodes = sectionNodes,
            edges = allEdges,
        )
    }

    /**
     * Parses the markdown content and persists all extracted nodes and edges to the repository.
     */
    suspend fun ingestDocument(
        content: String,
        filePath: String? = null,
        defaultProjectId: String? = null,
        defaultNodeId: String? = null,
    ): IngestionResult {
        val result = parse(content, filePath, defaultProjectId, defaultNodeId)
        graphRepository?.let { repo ->
            repo.saveNode(result.rootNode)
            for (section in result.sectionNodes) {
                repo.saveNode(section)
            }
            for (edge in result.edges) {
                repo.saveEdge(edge)
            }
        }
        return result
    }

    private fun extractSections(
        document: Node,
        rootNodeId: String,
        skipH1Heading: Heading?,
        filePath: String?,
        timestamp: Long,
    ): Pair<List<ContextNode>, List<ContextEdge>> {
        val nodes = mutableListOf<ContextNode>()
        val edges = mutableListOf<ContextEdge>()

        val levelStack = mutableListOf<Pair<Int, String>>()
        levelStack.add(0 to rootNodeId)

        var currentHeading: Heading? = null
        var currentSectionContent = StringBuilder()

        fun flushSection() {
            val heading = currentHeading ?: return
            val headingTitle = heading.text.toString().trim()
            val headingLevel = heading.level

            val sectionId = "${rootNodeId}#${slugify(headingTitle)}"

            while (levelStack.isNotEmpty() && levelStack.last().first >= headingLevel) {
                levelStack.removeAt(levelStack.size - 1)
            }
            val parentId = levelStack.lastOrNull()?.second ?: rootNodeId

            val sectionType = inferSectionType(headingTitle)

            val sectionNode = ContextNode(
                id = sectionId,
                type = sectionType,
                label = headingTitle,
                body = currentSectionContent.toString().trim(),
                filePath = filePath,
                createdAt = timestamp,
                updatedAt = timestamp,
            )
            nodes.add(sectionNode)

            edges.add(
                ContextEdge(
                    id = "${parentId}_REFERENCES_$sectionId",
                    fromId = parentId,
                    toId = sectionId,
                    relation = "REFERENCES",
                    createdAt = timestamp,
                )
            )

            levelStack.add(headingLevel to sectionId)
            currentSectionContent = StringBuilder()
        }

        for (child in document.children) {
            if (child is Heading) {
                // If it is the first H1 that defines the root document title, don't create a child section for it
                if (child === skipH1Heading) {
                    continue
                }
                flushSection()
                currentHeading = child
            } else {
                if (currentHeading != null) {
                    currentSectionContent.append(child.chars.toString()).append("\n")
                }
            }
        }
        flushSection()

        return nodes to edges
    }

    private fun extractWikilinks(
        content: String,
        sourceNodeId: String,
        timestamp: Long,
    ): List<ContextEdge> {
        val edges = mutableListOf<ContextEdge>()
        val wikilinkRegex = Regex("""\[\[(.*?)\]\]""")

        for (match in wikilinkRegex.findAll(content)) {
            val rawInside = match.groupValues[1].trim()
            if (rawInside.isNotBlank()) {
                val parsed = parseWikilinkTarget(rawInside)
                val targetId = parsed.targetId
                val relation = parsed.relation ?: "REFERENCES"
                val edgeId = "${sourceNodeId}_${relation}_$targetId"
                if (edges.none { it.id == edgeId }) {
                    edges.add(
                        ContextEdge(
                            id = edgeId,
                            fromId = sourceNodeId,
                            toId = targetId,
                            relation = relation,
                            createdAt = timestamp,
                        )
                    )
                }
            }
        }

        return edges
    }

    private data class ParsedWikilink(val targetId: String, val relation: String?)

    private fun parseWikilinkTarget(target: String): ParsedWikilink {
        val withoutAlias = target.substringBefore('|').trim()

        val colonIndex = withoutAlias.indexOf(':')
        return if (colonIndex > 0) {
            val prefix = withoutAlias.substring(0, colonIndex).trim().uppercase()
            val id = withoutAlias.substring(colonIndex + 1).trim()
            val validRelation = when (prefix) {
                "IMPLEMENTS" -> "IMPLEMENTS"
                "MUTATES" -> "MUTATES"
                "DEPENDS_ON", "DEPENDS" -> "DEPENDS_ON"
                "REFERENCES", "REF" -> "REFERENCES"
                else -> prefix
            }
            ParsedWikilink(targetId = id, relation = validRelation)
        } else {
            ParsedWikilink(targetId = withoutAlias, relation = "REFERENCES")
        }
    }

    private fun inferSectionType(title: String): NodeType {
        val lower = title.lowercase()
        return when {
            lower.contains("requirement") || lower.contains("feature") || lower.contains("user story") -> NodeType.REQUIREMENT
            lower.contains("entity") || lower.contains("model") || lower.contains("data structure") -> NodeType.ENTITY
            lower.contains("api") || lower.contains("endpoint") || lower.contains("route") -> NodeType.ENDPOINT
            lower.contains("table") || lower.contains("schema") || lower.contains("database") -> NodeType.TABLE
            lower.contains("exploration") || lower.contains("spike") || lower.contains("research") -> NodeType.EXPLORATION
            else -> NodeType.SPEC
        }
    }

    private fun slugify(text: String): String {
        return text.lowercase()
            .replace(Regex("""[^a-z0-9]+"""), "-")
            .trim('-')
    }
}
