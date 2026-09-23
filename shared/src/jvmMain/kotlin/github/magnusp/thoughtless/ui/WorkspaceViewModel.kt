package github.magnusp.thoughtless.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import github.magnusp.thoughtless.domain.model.AgentTaskStatus
import github.magnusp.thoughtless.domain.model.ContextEdge
import github.magnusp.thoughtless.domain.model.ContextNode
import github.magnusp.thoughtless.domain.model.NodeType
import github.magnusp.thoughtless.domain.model.Project
import github.magnusp.thoughtless.domain.model.Spec
import github.magnusp.thoughtless.domain.model.Task
import github.magnusp.thoughtless.domain.model.TaskPriority
import github.magnusp.thoughtless.domain.model.TaskStatus
import github.magnusp.thoughtless.domain.repository.ContextGraphRepository
import github.magnusp.thoughtless.domain.repository.ProjectRepository
import github.magnusp.thoughtless.domain.repository.TaskRepository
import github.magnusp.thoughtless.service.AgentTaskDAGExport
import github.magnusp.thoughtless.service.DAGDecomposerService
import github.magnusp.thoughtless.service.MarkdownIngestionService
import github.magnusp.thoughtless.util.currentTimeMillis
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class WorkspaceNavTab {
    DOCUMENTS,
    TASKS,
    AGENT_QUEUE,
}

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceViewModel(
    val taskRepository: TaskRepository,
    val projectRepository: ProjectRepository,
    val contextGraphRepository: ContextGraphRepository,
    val markdownIngestionService: MarkdownIngestionService,
    val dagDecomposerService: DAGDecomposerService,
    val taskProposalRepository: github.magnusp.thoughtless.domain.repository.TaskProposalRepository? = null,
    val taskProposalService: github.magnusp.thoughtless.service.TaskProposalService? = null,
) : ViewModel() {

    companion object {
        fun createDefault(): WorkspaceViewModel {
            val engine = github.magnusp.thoughtless.data.arcadedb.ArcadeDBEngine.getDefault()
            val taskRepo = github.magnusp.thoughtless.data.arcadedb.ArcadeDBTaskRepository(engine)
            val projectRepo = github.magnusp.thoughtless.data.arcadedb.ArcadeDBProjectRepository(engine)
            val graphRepo = github.magnusp.thoughtless.data.arcadedb.ArcadeDBContextGraphRepository(engine)
            val proposalRepo = github.magnusp.thoughtless.data.arcadedb.ArcadeDBTaskProposalRepository(engine)
            val proposalService = github.magnusp.thoughtless.service.TaskProposalService(proposalRepo, taskRepo, graphRepo)
            val markdownService = MarkdownIngestionService(graphRepo)
            val dagService = DAGDecomposerService(taskRepo, graphRepo)
            return WorkspaceViewModel(
                taskRepository = taskRepo,
                projectRepository = projectRepo,
                contextGraphRepository = graphRepo,
                markdownIngestionService = markdownService,
                dagDecomposerService = dagService,
                taskProposalRepository = proposalRepo,
                taskProposalService = proposalService,
            )
        }
    }

    // 1. Navigation state
    private val _activeTab = MutableStateFlow(WorkspaceNavTab.DOCUMENTS)
    val activeTab: StateFlow<WorkspaceNavTab> = _activeTab.asStateFlow()

    fun selectTab(tab: WorkspaceNavTab) {
        _activeTab.value = tab
    }

    // 2. Documents & Graph state
    val allNodes: StateFlow<List<ContextNode>> = contextGraphRepository.getNodes()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    val documentRoots: StateFlow<List<ContextNode>> = allNodes.map { nodes ->
        nodes.filter { !it.id.contains("#") && (it.type == NodeType.SPEC || it.filePath != null) }
            .ifEmpty { nodes.filter { !it.id.contains("#") } }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList(),
    )

    private val _selectedDocumentId = MutableStateFlow<String?>(null)
    val selectedDocumentId: StateFlow<String?> = _selectedDocumentId.asStateFlow()

    private val _editorContent = MutableStateFlow("")
    val editorContent: StateFlow<String> = _editorContent.asStateFlow()

    private val _incomingBacklinks = MutableStateFlow<List<ContextNode>>(emptyList())
    val incomingBacklinks: StateFlow<List<ContextNode>> = _incomingBacklinks.asStateFlow()

    private val _impactedNodes = MutableStateFlow<List<ContextNode>>(emptyList())
    val impactedNodes: StateFlow<List<ContextNode>> = _impactedNodes.asStateFlow()

    private val _outgoingEdges = MutableStateFlow<List<ContextEdge>>(emptyList())
    val outgoingEdges: StateFlow<List<ContextEdge>> = _outgoingEdges.asStateFlow()

    init {
        // Automatically select first document if none selected and documents exist
        viewModelScope.launch {
            documentRoots.collect { docs ->
                if (_selectedDocumentId.value == null && docs.isNotEmpty()) {
                    selectDocument(docs.first().id)
                }
            }
        }
    }

    fun selectDocument(documentId: String) {
        _selectedDocumentId.value = documentId
        val node = allNodes.value.firstOrNull { it.id == documentId }
        _editorContent.value = node?.body ?: ""
        loadInspectorData(documentId)
    }

    fun updateEditorContent(newContent: String) {
        _editorContent.value = newContent
    }

    fun saveCurrentDocument() {
        val docId = _selectedDocumentId.value ?: return
        val content = _editorContent.value
        viewModelScope.launch {
            val node = allNodes.value.firstOrNull { it.id == docId }
            val filePath = node?.filePath ?: "docs/$docId.md"
            markdownIngestionService.ingestDocument(content, filePath = filePath)
            loadInspectorData(docId)
        }
    }

    fun createNewDocument(title: String = "New Specification", projectId: String? = null) {
        val slug = title.lowercase().replace(Regex("""[^a-z0-9]+"""), "-").trim('-')
        val id = "doc-$slug"
        val initialContent = buildString {
            appendLine("---")
            appendLine("id: $id")
            appendLine("type: SPEC")
            appendLine("title: $title")
            if (projectId != null) {
                appendLine("project: $projectId")
            }
            appendLine("---")
            appendLine()
            appendLine("# $title")
            appendLine()
            appendLine("## Overview")
            appendLine("Describe system architecture and relations using [[wikilinks]].")
            appendLine()
            appendLine("## Non-Goals")
            appendLine("- Explicit exclusions here")
        }

        viewModelScope.launch {
            markdownIngestionService.ingestDocument(
                content = initialContent,
                filePath = "docs/$slug.md",
                defaultProjectId = projectId,
            )
            _selectedDocumentId.value = id
            _editorContent.value = initialContent
            loadInspectorData(id)
        }
    }

    private fun loadInspectorData(nodeId: String) {
        viewModelScope.launch {
            try {
                _incomingBacklinks.value = contextGraphRepository.getBacklinks(nodeId)
            } catch (_: Exception) {
                _incomingBacklinks.value = emptyList()
            }
            try {
                _impactedNodes.value = contextGraphRepository.analyzeImpact(nodeId)
            } catch (_: Exception) {
                _impactedNodes.value = emptyList()
            }
        }
        viewModelScope.launch {
            contextGraphRepository.getEdgesForNode(nodeId).collect { edges ->
                _outgoingEdges.value = edges.filter { it.fromId == nodeId }
            }
        }
    }

    // 3. Projects and Tasks state
    private val _selectedProjectId = MutableStateFlow<String?>(null)
    val selectedProjectId: StateFlow<String?> = _selectedProjectId.asStateFlow()

    val projects: StateFlow<List<Project>> = projectRepository.getProjects()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    val tasks: StateFlow<List<Task>> = _selectedProjectId
        .flatMapLatest { projectId ->
            if (projectId == null) {
                taskRepository.getTasks()
            } else {
                taskRepository.getTasksByProject(projectId)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    fun selectProject(projectId: String?) {
        _selectedProjectId.value = projectId
    }

    fun createTask(
        title: String,
        description: String? = null,
        projectId: String? = _selectedProjectId.value,
        priority: TaskPriority = TaskPriority.NONE,
        workspace: String? = null,
    ) {
        if (title.isBlank()) return
        viewModelScope.launch {
            val project = projectId?.let { pid -> projects.value.firstOrNull { it.id == pid } }
            val resolvedWorkspace = workspace ?: project?.defaultWorkspace
            taskRepository.createTask(
                title = title.trim(),
                description = description?.trim(),
                projectId = projectId,
                priority = priority,
                workspace = resolvedWorkspace,
            )
        }
    }

    fun toggleTaskCompletion(task: Task) {
        viewModelScope.launch {
            val nextStatus = if (task.status == TaskStatus.DONE) TaskStatus.TODO else TaskStatus.DONE
            taskRepository.updateTaskStatus(task.id, nextStatus)
        }
    }

    fun deleteTask(taskId: String) {
        viewModelScope.launch {
            taskRepository.deleteTask(taskId)
        }
    }

    fun createProject(name: String, color: String? = null, defaultWorkspace: String? = null) {
        if (name.isBlank()) return
        viewModelScope.launch {
            projectRepository.createProject(name = name.trim(), color = color, defaultWorkspace = defaultWorkspace?.trim()?.ifBlank { null })
        }
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch {
            projectRepository.deleteProject(projectId)
            if (_selectedProjectId.value == projectId) {
                _selectedProjectId.value = null
            }
        }
    }

    // 4. Agent Task Queue state
    val executionTiers: StateFlow<List<List<Task>>> = tasks.map { allTasks ->
        if (allTasks.isEmpty()) return@map emptyList()
        try {
            val (_, tiers) = dagDecomposerService.topologicalSort(allTasks)
            val taskMap = allTasks.associateBy { it.id }
            tiers.map { tierIds -> tierIds.mapNotNull { taskMap[it] } }
        } catch (_: Exception) {
            listOf(allTasks)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList(),
    )

    fun updateAgentTaskStatus(task: Task, newStatus: AgentTaskStatus) {
        viewModelScope.launch {
            val updated = task.copy(
                agentStatus = newStatus,
                status = if (newStatus == AgentTaskStatus.MERGED) TaskStatus.DONE else task.status,
            )
            taskRepository.updateTask(updated)
        }
    }

    fun exportAgentDagJson(projectId: String? = _selectedProjectId.value): String {
        val currentTasks = tasks.value
        val targetProjectId = projectId ?: "default"
        val dummySpec = Spec(
            id = "export-$targetProjectId",
            projectId = targetProjectId,
            title = "Agent Task Queue Export",
            systemSpec = "Topological export of active tasks",
            rfcDocument = "",
            frozenAt = currentTimeMillis(),
        )
        val exportModel = try {
            val (order, tiers) = dagDecomposerService.topologicalSort(currentTasks)
            val currentProject = targetProjectId.let { pid -> projects.value.firstOrNull { it.id == pid } }
            val executableTasks = currentTasks.map { t ->
                github.magnusp.thoughtless.service.AgentExecutableTask(
                    id = t.id,
                    title = t.title,
                    description = t.description,
                    type = t.type.name,
                    workspace = t.workspace ?: currentProject?.defaultWorkspace,
                    targetFile = t.targetFile,
                    contextFiles = t.contextFiles,
                    acceptanceCriteria = t.acceptanceCriteria,
                    dependsOn = t.dependsOn,
                    canInstallPackages = t.agentPermissions?.canInstallPackages ?: false,
                    allowedCommands = t.agentPermissions?.allowedCommands ?: emptyList(),
                )
            }
            AgentTaskDAGExport(
                specId = dummySpec.id,
                projectId = dummySpec.projectId,
                generatedAt = dummySpec.frozenAt ?: currentTimeMillis(),
                totalTasks = currentTasks.size,
                topologicalOrder = order,
                executionTiers = tiers,
                tasks = executableTasks,
            )
        } catch (_: Exception) {
            AgentTaskDAGExport(
                specId = dummySpec.id,
                projectId = dummySpec.projectId,
                generatedAt = dummySpec.frozenAt ?: currentTimeMillis(),
                totalTasks = currentTasks.size,
                topologicalOrder = currentTasks.map { it.id },
                executionTiers = listOf(currentTasks.map { it.id }),
                tasks = emptyList(),
            )
        }
        return dagDecomposerService.exportToJson(exportModel)
    }

    // 5. Discovered Proposals & Agent Scratchpad Workspace
    val pendingProposals: StateFlow<List<github.magnusp.thoughtless.domain.model.TaskProposal>> = _selectedProjectId
        .flatMapLatest { projectId ->
            taskProposalRepository?.let { repo ->
                if (projectId == null) repo.getPendingProposals()
                else repo.getProposalsByProject(projectId).map { list ->
                    list.filter { it.status == github.magnusp.thoughtless.domain.model.ProposalStatus.PROPOSED }
                }
            } ?: kotlinx.coroutines.flow.flowOf(emptyList())
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    fun acceptProposal(
        proposalId: String,
        refine: ((github.magnusp.thoughtless.domain.model.TaskProposal) -> github.magnusp.thoughtless.domain.model.TaskProposal)? = null,
    ) {
        viewModelScope.launch {
            taskProposalService?.acceptProposal(proposalId, refine)
        }
    }

    fun rejectProposal(proposalId: String) {
        viewModelScope.launch {
            taskProposalService?.rejectProposal(proposalId)
        }
    }

    fun updateTaskProgress(
        taskId: String,
        scratchpad: github.magnusp.thoughtless.domain.model.AgentScratchpad,
    ) {
        viewModelScope.launch {
            taskRepository.updateAgentScratchpad(taskId, scratchpad)
        }
    }
}
