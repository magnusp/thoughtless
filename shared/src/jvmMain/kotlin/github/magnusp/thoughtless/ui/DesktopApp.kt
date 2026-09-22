package github.magnusp.thoughtless.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import github.magnusp.thoughtless.AppContent

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
fun DesktopApp(
    viewModel: WorkspaceViewModel = remember { WorkspaceViewModel.createDefault() }
) {
    val activeTab by viewModel.activeTab.collectAsState()

    MaterialTheme(colorScheme = ThoughtlessDarkScheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                // Navigation Rail
                NavigationRailBar(
                    activeTab = activeTab,
                    onSelectTab = { viewModel.selectTab(it) }
                )

                VerticalDivider(
                    modifier = Modifier.fillMaxHeight(),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                )

                // Tab Content
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    when (activeTab) {
                        WorkspaceNavTab.DOCUMENTS -> {
                            DocumentWorkspaceView(viewModel = viewModel)
                        }
                        WorkspaceNavTab.TASKS -> {
                            // Classic tasks view using AppContent from commonMain
                            AppContent(
                                tasks = viewModel.tasks.collectAsState().value,
                                projects = viewModel.projects.collectAsState().value,
                                selectedProjectId = viewModel.selectedProjectId.collectAsState().value,
                                onSelectProject = viewModel::selectProject,
                                onCreateProject = viewModel::createProject,
                                onDeleteProject = viewModel::deleteProject,
                                onToggleTask = viewModel::toggleTaskCompletion,
                                onDeleteTask = viewModel::deleteTask,
                                onAddTask = { title, desc, prio -> viewModel.createTask(title, desc, priority = prio) },
                            )
                        }
                        WorkspaceNavTab.AGENT_QUEUE -> {
                            AgentQueueView(viewModel = viewModel)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavigationRailBar(
    activeTab: WorkspaceNavTab,
    onSelectTab: (WorkspaceNavTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(72.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 16.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // App icon / branding
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(44.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "TL",
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontSize = 16.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Tabs
        NavRailItem(
            label = "Docs",
            icon = "📄",
            isSelected = activeTab == WorkspaceNavTab.DOCUMENTS,
            onClick = { onSelectTab(WorkspaceNavTab.DOCUMENTS) }
        )

        Spacer(modifier = Modifier.height(12.dp))

        NavRailItem(
            label = "Tasks",
            icon = "📋",
            isSelected = activeTab == WorkspaceNavTab.TASKS,
            onClick = { onSelectTab(WorkspaceNavTab.TASKS) }
        )

        Spacer(modifier = Modifier.height(12.dp))

        NavRailItem(
            label = "Queue",
            icon = "🤖",
            isSelected = activeTab == WorkspaceNavTab.AGENT_QUEUE,
            onClick = { onSelectTab(WorkspaceNavTab.AGENT_QUEUE) }
        )
    }
}

@Composable
private fun NavRailItem(
    label: String,
    icon: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent
    val contentColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = icon, fontSize = 20.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = contentColor,
        )
    }
}
