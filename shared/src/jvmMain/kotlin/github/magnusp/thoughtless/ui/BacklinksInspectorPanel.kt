package github.magnusp.thoughtless.ui

import androidx.compose.foundation.background
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
import github.magnusp.thoughtless.domain.model.ContextEdge
import github.magnusp.thoughtless.domain.model.ContextNode

private enum class InspectorTab {
    BACKLINKS,
    OUTGOING,
    IMPACT
}

@Composable
fun BacklinksInspectorPanel(
    viewModel: WorkspaceViewModel,
    modifier: Modifier = Modifier
) {
    val selectedDocId by viewModel.selectedDocumentId.collectAsState()
    val incomingBacklinks by viewModel.incomingBacklinks.collectAsState()
    val outgoingEdges by viewModel.outgoingEdges.collectAsState()
    val impactedNodes by viewModel.impactedNodes.collectAsState()

    var activeTab by remember { mutableStateOf(InspectorTab.BACKLINKS) }

    Surface(
        modifier = modifier.fillMaxHeight().width(320.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Text(
                text = "Graph Inspector",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (selectedDocId == null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Select a document to inspect connections and impact.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Selected: $selectedDocId",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Tabs for Incoming, Outgoing, and Impact
                TabRow(
                    selectedTabIndex = activeTab.ordinal,
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Tab(
                        selected = activeTab == InspectorTab.BACKLINKS,
                        onClick = { activeTab = InspectorTab.BACKLINKS },
                        text = { Text("In (${incomingBacklinks.size})", fontSize = 12.sp) }
                    )
                    Tab(
                        selected = activeTab == InspectorTab.OUTGOING,
                        onClick = { activeTab = InspectorTab.OUTGOING },
                        text = { Text("Out (${outgoingEdges.size})", fontSize = 12.sp) }
                    )
                    Tab(
                        selected = activeTab == InspectorTab.IMPACT,
                        onClick = { activeTab = InspectorTab.IMPACT },
                        text = { Text("Impact (${impactedNodes.size})", fontSize = 12.sp) }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                when (activeTab) {
                    InspectorTab.BACKLINKS -> {
                        InspectorListSection(
                            title = "Incoming Backlinks",
                            emptyText = "No incoming links to this document.",
                            items = incomingBacklinks,
                            onNodeClick = { node -> viewModel.selectDocument(node.id) }
                        )
                    }
                    InspectorTab.OUTGOING -> {
                        OutgoingEdgesSection(
                            edges = outgoingEdges,
                            onNodeClick = { targetNodeId -> viewModel.selectDocument(targetNodeId) }
                        )
                    }
                    InspectorTab.IMPACT -> {
                        InspectorListSection(
                            title = "Multi-Hop Impacted Nodes",
                            emptyText = "No downstream nodes impacted.",
                            items = impactedNodes,
                            onNodeClick = { node -> viewModel.selectDocument(node.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InspectorListSection(
    title: String,
    emptyText: String,
    items: List<ContextNode>,
    onNodeClick: (ContextNode) -> Unit
) {
    if (items.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(items, key = { it.id }) { node ->
                NodeCard(node = node, onClick = { onNodeClick(node) })
            }
        }
    }
}

@Composable
private fun OutgoingEdgesSection(
    edges: List<ContextEdge>,
    onNodeClick: (String) -> Unit
) {
    if (edges.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No outgoing links from this document.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(edges, key = { "${it.fromId}->${it.toId}:${it.relation}" }) { edge ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth().clickable { onNodeClick(edge.toId) }
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = edge.toId,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Badge(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ) {
                                Text(
                                    text = edge.relation,
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
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
private fun NodeCard(
    node: ContextNode,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = node.label.ifBlank { node.id },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Badge(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                ) {
                    Text(
                        text = node.type.name,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }

            if (node.id != node.label) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = node.id,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}
