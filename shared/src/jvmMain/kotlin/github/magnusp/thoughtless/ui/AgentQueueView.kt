package github.magnusp.thoughtless.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import github.magnusp.thoughtless.domain.model.AgentScratchpad
import github.magnusp.thoughtless.domain.model.AgentTaskStatus
import github.magnusp.thoughtless.domain.model.Task
import github.magnusp.thoughtless.domain.model.TaskProposal
import kotlinx.coroutines.launch

@Composable
fun AgentQueueView(
    viewModel: WorkspaceViewModel,
    modifier: Modifier = Modifier
) {
    val executionTiers by viewModel.executionTiers.collectAsState()
    val allTasks by viewModel.tasks.collectAsState()
    val pendingProposals by viewModel.pendingProposals.collectAsState()
    val operatorIdentity by viewModel.operatorIdentity.collectAsState()
    val workspaceStatuses by viewModel.workspaceStatuses.collectAsState()
    val scope = rememberCoroutineScope()
    var exportJsonDialogContent by remember { mutableStateOf<String?>(null) }
    var cloningWorkspace by remember { mutableStateOf<String?>(null) }
    var cloneError by remember { mutableStateOf<String?>(null) }

    // Run workspace readiness check when queue view mounts or tasks change
    LaunchedEffect(allTasks) {
        viewModel.checkWorkspacesReadiness()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Top Header and Action Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Agent Task Execution Queue",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Topologically tiered DAG tasks with live agent scratchpad & discovered proposal triage",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                // Operator Identity Badge
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (operatorIdentity.isAuthenticated) Color(0xFF065F46) else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = if (operatorIdentity.isAuthenticated) "👤" else "🔒",
                            fontSize = 12.sp
                        )
                        Text(
                            text = if (operatorIdentity.isAuthenticated) {
                                operatorIdentity.username?.let { "@$it" } ?: "Authenticated Operator"
                            } else {
                                "Unauthenticated Operator"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (operatorIdentity.isAuthenticated) Color(0xFFD1FAE5) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Button(
                    onClick = {
                        scope.launch {
                            val json = viewModel.exportAgentDagJson("default")
                            exportJsonDialogContent = json
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Export Agent DAG JSON")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Workspace Pre-Flight Readiness Section
            if (workspaceStatuses.isNotEmpty()) {
                item {
                    WorkspaceReadinessSection(
                        statuses = workspaceStatuses.values.toList(),
                        cloningWorkspace = cloningWorkspace,
                        cloneError = cloneError,
                        onClone = { ws ->
                            scope.launch {
                                cloningWorkspace = ws
                                cloneError = null
                                val res = viewModel.cloneWorkspace(ws)
                                if (res.isFailure) {
                                    cloneError = res.exceptionOrNull()?.message ?: "Failed to clone"
                                }
                                cloningWorkspace = null
                            }
                        }
                    )
                }
            }

            // 1. Discovered Work / Proposals Triage Section
            if (pendingProposals.isNotEmpty()) {
                item {
                    ProposalsTriageSection(
                        proposals = pendingProposals,
                        onAccept = { proposal -> viewModel.acceptProposal(proposal.id) },
                        onReject = { proposal -> viewModel.rejectProposal(proposal.id) }
                    )
                }
            }

            // 2. Execution Tiers
            if (allTasks.isEmpty() && pendingProposals.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No tasks or proposals in the graph. Decompose a Spec to populate the queue.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(executionTiers.indices.toList()) { tierIndex ->
                    val tierTasks = executionTiers[tierIndex]
                    TierCard(
                        tierNumber = tierIndex + 1,
                        tasks = tierTasks,
                        onApprove = { task -> viewModel.updateAgentTaskStatus(task, AgentTaskStatus.MERGED) },
                        onReject = { task -> viewModel.updateAgentTaskStatus(task, AgentTaskStatus.PENDING) }
                    )
                }
            }
        }
    }

    // Modal Dialog to display Exported JSON
    exportJsonDialogContent?.let { json ->
        AlertDialog(
            onDismissRequest = { exportJsonDialogContent = null },
            title = { Text("Exported Agent DAG JSON") },
            text = {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)
                ) {
                    LazyColumn(modifier = Modifier.padding(12.dp)) {
                        item {
                            Text(
                                text = json,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { exportJsonDialogContent = null }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun ProposalsTriageSection(
    proposals: List<TaskProposal>,
    onAccept: (TaskProposal) -> Unit,
    onReject: (TaskProposal) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.25f)
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "💡 Discovered Work & Proposals",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Badge(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    ) {
                        Text(
                            text = "${proposals.size} awaiting triage",
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                proposals.forEach { proposal ->
                    ProposalRowCard(
                        proposal = proposal,
                        onAccept = { onAccept(proposal) },
                        onReject = { onReject(proposal) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ProposalRowCard(
    proposal: TaskProposal,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = proposal.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Badge(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ) {
                        Text(
                            text = proposal.type.name,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }

                    if (!proposal.suggestedWorkspace.isNullOrBlank()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Badge(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ) {
                            Text(
                                text = "📁 ${proposal.suggestedWorkspace}",
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }

                if (proposal.rationale.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Rationale: ${proposal.rationale}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }

                if (proposal.suggestedTargetFile != null || proposal.suggestedContextFiles.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    val files = (listOfNotNull(proposal.suggestedTargetFile) + proposal.suggestedContextFiles).distinct()
                    Text(
                        text = "Suggested Files: ${files.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1
                    )
                }

                if (proposal.sourceTaskId != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Discovered during task: ${proposal.sourceTaskId}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Actions: Accept / Reject
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalButton(
                    onClick = onAccept,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color(0xFF4CAF50).copy(alpha = 0.2f),
                        contentColor = Color(0xFF2E7D32)
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("Accept (DAG)", fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = onReject,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("Dismiss", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun TierCard(
    tierNumber: Int,
    tasks: List<Task>,
    onApprove: (Task) -> Unit,
    onReject: (Task) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Execution Tier $tierNumber",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Badge(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Text(
                        text = "${tasks.size} task(s)",
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tasks.forEach { task ->
                    TaskRowCard(
                        task = task,
                        onApprove = { onApprove(task) },
                        onReject = { onReject(task) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskRowCard(
    task: Task,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    var expandedScratchpad by remember { mutableStateOf(false) }
    val scratchpad = task.agentScratchpad

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = task.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        TaskStatusBadge(status = task.agentStatus)

                        if (!task.workspace.isNullOrBlank()) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Badge(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ) {
                                Text(
                                    text = "📁 ${task.workspace}",
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                        
                        if (scratchpad != null) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                modifier = Modifier.clickable { expandedScratchpad = !expandedScratchpad }
                            ) {
                                Text(
                                    text = if (expandedScratchpad) "▲ Scratchpad" else "▼ Scratchpad",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    if (!task.description.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = task.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }

                    // Live scratchpad summary line if executing
                    if (scratchpad?.currentStep != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "⚡ Current Step: ${scratchpad.currentStep}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    if (task.contextFiles.isNotEmpty() || task.targetFile != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        val targets = (listOfNotNull(task.targetFile) + task.contextFiles).distinct()
                        Text(
                            text = "Targets: " + targets.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Approval Actions
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(
                        onClick = onApprove,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color(0xFF4CAF50).copy(alpha = 0.2f),
                            contentColor = Color(0xFF2E7D32)
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Approve", fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = onReject,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Reject", fontSize = 12.sp)
                    }
                }
            }

            // Expanded Agent Execution Scratchpad Drawer
            if (scratchpad != null && expandedScratchpad) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "Agent Execution Scratchpad",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (!scratchpad.notes.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = scratchpad.notes,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        if (scratchpad.touchedFiles.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Touched files: ${scratchpad.touchedFiles.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }

                        if (scratchpad.completedCriteria.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Completed criteria:",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            scratchpad.completedCriteria.forEach { criterion ->
                                Text(
                                    text = "  ✓ $criterion",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF2E7D32)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskStatusBadge(status: AgentTaskStatus) {
    val (bg, fg) = when (status) {
        AgentTaskStatus.PENDING -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        AgentTaskStatus.AGENT_RUNNING -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        AgentTaskStatus.AWAITING_REVIEW -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        AgentTaskStatus.MERGED -> Color(0xFFD4EDDA) to Color(0xFF155724)
    }

    Badge(containerColor = bg, contentColor = fg) {
        Text(
            text = status.name,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun WorkspaceReadinessSection(
    statuses: List<github.magnusp.thoughtless.workspace.WorkspaceCheckResult>,
    cloningWorkspace: String?,
    cloneError: String?,
    onClone: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = "🛠️", fontSize = 16.sp)
                    Text(
                        text = "Workspace Pre-Flight Readiness",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                val allReady = statuses.all { it.status == github.magnusp.thoughtless.workspace.WorkspaceStatus.READY }
                Text(
                    text = if (allReady) "All workspaces ready" else "${statuses.count { it.status != github.magnusp.thoughtless.workspace.WorkspaceStatus.READY }} need attention",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (allReady) Color(0xFF10B981) else Color(0xFFF59E0B)
                )
            }

            if (!cloneError.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Clone error: $cloneError",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                statuses.forEach { check ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = check.workspace,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (check.details != null) {
                                Text(
                                    text = check.details,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (check.status == github.magnusp.thoughtless.workspace.WorkspaceStatus.READY) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFF065F46)
                            ) {
                                Text(
                                    text = "READY",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFD1FAE5),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        } else if (check.status == github.magnusp.thoughtless.workspace.WorkspaceStatus.MISSING_LOCAL_CLONE) {
                            val isCloning = cloningWorkspace == check.workspace
                            Button(
                                onClick = { onClone(check.workspace) },
                                enabled = !isCloning,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text(if (isCloning) "Cloning..." else "Clone Repo", fontSize = 12.sp)
                            }
                        } else {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.errorContainer
                            ) {
                                Text(
                                    text = check.status.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
