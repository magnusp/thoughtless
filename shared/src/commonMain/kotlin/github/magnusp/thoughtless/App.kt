package github.magnusp.thoughtless

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import github.magnusp.thoughtless.domain.model.Project
import github.magnusp.thoughtless.domain.model.Task
import github.magnusp.thoughtless.domain.model.TaskPriority
import github.magnusp.thoughtless.domain.model.TaskStatus
import github.magnusp.thoughtless.ui.AppDependencies
import github.magnusp.thoughtless.ui.TaskListViewModel

private val ThoughtlessDarkScheme = darkColorScheme(
    primary = Color(0xFF818CF8),
    onPrimary = Color(0xFF1E1B4B),
    primaryContainer = Color(0xFF312E81),
    onPrimaryContainer = Color(0xFFE0E7FF),
    surface = Color(0xFF18181B),
    onSurface = Color(0xFFF4F4F5),
    surfaceVariant = Color(0xFF27272A),
    onSurfaceVariant = Color(0xFFA1A1AA),
    background = Color(0xFF09090B),
    onBackground = Color(0xFFF4F4F5),
    outline = Color(0xFF3F3F46),
)

@Composable
fun App(
    viewModel: TaskListViewModel = remember { AppDependencies().viewModel }
) {
    MaterialTheme(colorScheme = ThoughtlessDarkScheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            val tasks by viewModel.tasks.collectAsState()
            val projects by viewModel.projects.collectAsState()
            val selectedProjectId by viewModel.selectedProjectId.collectAsState()

            AppContent(
                tasks = tasks,
                projects = projects,
                selectedProjectId = selectedProjectId,
                onSelectProject = viewModel::selectProject,
                onCreateProject = { name, color, ws -> viewModel.createProject(name = name, color = color, workspace = ws) },
                onDeleteProject = viewModel::deleteProject,
                onToggleTask = viewModel::toggleTaskCompletion,
                onDeleteTask = viewModel::deleteTask,
                onAddTask = { title, description, priority ->
                    viewModel.createTask(
                        title = title,
                        description = description,
                        projectId = selectedProjectId,
                        priority = priority,
                    )
                },
            )
        }
    }
}

@Composable
fun AppContent(
    tasks: List<Task>,
    projects: List<Project>,
    selectedProjectId: String?,
    onSelectProject: (String?) -> Unit,
    onCreateProject: (name: String, color: String?, workspace: String?) -> Unit,
    onDeleteProject: (String) -> Unit,
    onToggleTask: (Task) -> Unit,
    onDeleteTask: (String) -> Unit,
    onAddTask: (title: String, description: String?, priority: TaskPriority) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxSize()) {
        Sidebar(
            projects = projects,
            selectedProjectId = selectedProjectId,
            onSelectProject = onSelectProject,
            onCreateProject = onCreateProject,
            onDeleteProject = onDeleteProject,
            modifier = Modifier
                .width(280.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp),
        )

        VerticalDivider(
            modifier = Modifier.fillMaxHeight(),
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
        )

        MainContent(
            tasks = tasks,
            projects = projects,
            selectedProjectId = selectedProjectId,
            onToggleTask = onToggleTask,
            onDeleteTask = onDeleteTask,
            onAddTask = onAddTask,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(24.dp),
        )
    }
}

@Composable
fun Sidebar(
    projects: List<Project>,
    selectedProjectId: String?,
    onSelectProject: (String?) -> Unit,
    onCreateProject: (name: String, color: String?, workspace: String?) -> Unit,
    onDeleteProject: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isAddingProject by remember { mutableStateOf(false) }
    var newProjectName by remember { mutableStateOf("") }
    var newProjectWorkspace by remember { mutableStateOf("") }
    var selectedColorIndex by remember { mutableStateOf(0) }

    val projectColors = remember {
        listOf("#6366F1", "#EC4899", "#10B981", "#F59E0B", "#8B5CF6", "#06B6D4")
    }

    Column(modifier = modifier) {
        // App header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "✦",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column {
                Text(
                    text = "thoughtless",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Local-first task tracker",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Navigation section
        Text(
            text = "VIEWS",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )

        SidebarNavItem(
            title = "All Tasks",
            icon = "📋",
            isSelected = selectedProjectId == null,
            onClick = { onSelectProject(null) },
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Projects section
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "PROJECTS",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = { isAddingProject = !isAddingProject },
                modifier = Modifier.height(28.dp),
            ) {
                Text(
                    text = if (isAddingProject) "Cancel" else "+ New",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        AnimatedVisibility(visible = isAddingProject) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    OutlinedTextField(
                        value = newProjectName,
                        onValueChange = { newProjectName = it },
                        placeholder = { Text("Project name...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = newProjectWorkspace,
                        onValueChange = { newProjectWorkspace = it },
                        placeholder = { Text("Workspace (e.g. github.com/org/repo)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        projectColors.forEachIndexed { index, hex ->
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(Color(parseHexColor(hex)))
                                    .clickable { selectedColorIndex = index }
                                    .padding(2.dp),
                            ) {
                                if (selectedColorIndex == index) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(CircleShape)
                                            .background(Color.White.copy(alpha = 0.5f)),
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            if (newProjectName.isNotBlank()) {
                                onCreateProject(
                                    newProjectName.trim(),
                                    projectColors[selectedColorIndex],
                                    newProjectWorkspace.trim().ifBlank { null }
                                )
                                newProjectName = ""
                                newProjectWorkspace = ""
                                isAddingProject = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Create Project")
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(projects, key = { it.id }) { project ->
                SidebarProjectItem(
                    project = project,
                    isSelected = selectedProjectId == project.id,
                    onClick = { onSelectProject(project.id) },
                    onDelete = { onDeleteProject(project.id) },
                )
            }
        }
    }
}

@Composable
fun SidebarNavItem(
    title: String,
    icon: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    } else {
        Color.Transparent
    }
    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text = icon, fontSize = 16.sp)
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = contentColor,
        )
    }
}

@Composable
fun SidebarProjectItem(
    project: Project,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    } else {
        Color.Transparent
    }
    val dotColor = project.color?.let { Color(parseHexColor(it)) } ?: MaterialTheme.colorScheme.primary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f),
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Text(
                text = project.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(24.dp),
        ) {
            Text(
                text = "×",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 18.sp,
            )
        }
    }
}

@Composable
fun MainContent(
    tasks: List<Task>,
    projects: List<Project>,
    selectedProjectId: String?,
    onToggleTask: (Task) -> Unit,
    onDeleteTask: (String) -> Unit,
    onAddTask: (title: String, description: String?, priority: TaskPriority) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentProject = projects.firstOrNull { it.id == selectedProjectId }
    val title = currentProject?.name ?: "All Tasks"
    val completedCount = tasks.count { it.status == TaskStatus.DONE }
    val activeCount = tasks.size - completedCount

    Column(modifier = modifier) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "$activeCount active • $completedCount completed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Add task card
        AddTaskCard(onAddTask = onAddTask)

        Spacer(modifier = Modifier.height(20.dp))

        // Task list
        if (tasks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No tasks yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Add a task above to get started!",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(tasks, key = { it.id }) { task ->
                    val project = projects.firstOrNull { it.id == task.projectId }
                    TaskRow(
                        task = task,
                        projectName = if (selectedProjectId == null) project?.name else null,
                        projectColor = project?.color,
                        onToggle = { onToggleTask(task) },
                        onDelete = { onDeleteTask(task.id) },
                    )
                }
            }
        }
    }
}

@Composable
fun AddTaskCard(
    onAddTask: (title: String, description: String?, priority: TaskPriority) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var isExpanded by remember { mutableStateOf(false) }
    var priority by remember { mutableStateOf(TaskPriority.NONE) }
    var showPriorityMenu by remember { mutableStateOf(false) }

    fun submit() {
        if (title.isNotBlank()) {
            onAddTask(title.trim(), description.trim().ifBlank { null }, priority)
            title = ""
            description = ""
            priority = TaskPriority.NONE
            isExpanded = false
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                placeholder = { Text("What needs to be done? (Press Enter to add)") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            (event.key == Key.Enter || event.key == Key.NumPadEnter)
                        ) {
                            submit()
                            true
                        } else false
                    },
            )

            AnimatedVisibility(visible = isExpanded) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        placeholder = { Text("Add more details or notes...") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        OutlinedButton(
                            onClick = { showPriorityMenu = true },
                            modifier = Modifier.height(34.dp),
                        ) {
                            Text(
                                text = "Priority: ${priority.displayName}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        DropdownMenu(
                            expanded = showPriorityMenu,
                            onDismissRequest = { showPriorityMenu = false },
                        ) {
                            TaskPriority.entries.forEach { p ->
                                DropdownMenuItem(
                                    text = { Text(p.displayName) },
                                    onClick = {
                                        priority = p
                                        showPriorityMenu = false
                                    },
                                )
                            }
                        }
                    }

                    TextButton(
                        onClick = { isExpanded = !isExpanded },
                        modifier = Modifier.height(34.dp),
                    ) {
                        Text(
                            text = if (isExpanded) "Hide details" else "+ Details",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }

                Button(
                    onClick = { submit() },
                    modifier = Modifier.height(34.dp),
                ) {
                    Text(
                        text = "Add Task",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
fun TaskRow(
    task: Task,
    projectName: String?,
    projectColor: String?,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val isDone = task.status == TaskStatus.DONE

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isDone) {
                MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Checkbox(
                checked = isDone,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary,
                    uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isDone) FontWeight.Normal else FontWeight.Medium,
                        color = if (isDone) {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        textDecoration = if (isDone) TextDecoration.LineThrough else TextDecoration.None,
                    )

                    if (task.priority != TaskPriority.NONE) {
                        PriorityBadge(priority = task.priority)
                    }

                    if (projectName != null) {
                        ProjectBadge(name = projectName, color = projectColor)
                    }

                    if (!task.workspace.isNullOrBlank()) {
                        WorkspaceBadge(workspace = task.workspace)
                    }
                }

                if (!task.description.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = task.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(28.dp),
            ) {
                Text(
                    text = "🗑",
                    fontSize = 14.sp,
                )
            }
        }
    }
}

@Composable
fun PriorityBadge(priority: TaskPriority) {
    val (color, text) = when (priority) {
        TaskPriority.URGENT -> Color(0xFFEF4444) to "Urgent"
        TaskPriority.HIGH -> Color(0xFFF97316) to "High"
        TaskPriority.MEDIUM -> Color(0xFFEAB308) to "Medium"
        TaskPriority.LOW -> Color(0xFF3B82F6) to "Low"
        TaskPriority.NONE -> Color.Transparent to ""
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.2f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
fun ProjectBadge(name: String, color: String?) {
    val badgeColor = color?.let { Color(parseHexColor(it)) } ?: MaterialTheme.colorScheme.primary

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(badgeColor.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(badgeColor),
        )
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = badgeColor,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun WorkspaceBadge(workspace: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = "📁 $workspace",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun parseHexColor(hex: String): Long {
    val clean = hex.removePrefix("#")
    val fullHex = if (clean.length == 6) "FF$clean" else clean
    return fullHex.toLongOrNull(16) ?: 0xFF6366F1
}