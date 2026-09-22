package github.magnusp.thoughtless.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import github.magnusp.thoughtless.domain.model.ContextNode
import github.magnusp.thoughtless.domain.model.NodeType

@Composable
fun DocumentWorkspaceView(
    viewModel: WorkspaceViewModel,
    modifier: Modifier = Modifier,
) {
    val documents by viewModel.documentRoots.collectAsState()
    val allNodes by viewModel.allNodes.collectAsState()
    val selectedDocId by viewModel.selectedDocumentId.collectAsState()
    val editorContent by viewModel.editorContent.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var isCreatingDoc by remember { mutableStateOf(false) }
    var newDocTitle by remember { mutableStateOf("") }

    // Wikilink autocomplete state
    val showWikilinkSuggestions = editorContent.endsWith("[[")
    val wikilinkCandidates = remember(editorContent, allNodes) {
        if (showWikilinkSuggestions) {
            allNodes.take(8)
        } else emptyList()
    }

    Row(modifier = modifier.fillMaxSize()) {
        // Document list sidebar
        Column(
            modifier = Modifier
                .width(260.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface)
                .padding(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "DOCUMENTS",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = { isCreatingDoc = !isCreatingDoc },
                    modifier = Modifier.height(28.dp),
                ) {
                    Text(
                        text = if (isCreatingDoc) "Cancel" else "+ New",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            AnimatedVisibility(visible = isCreatingDoc) {
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    OutlinedTextField(
                        value = newDocTitle,
                        onValueChange = { newDocTitle = it },
                        placeholder = { Text("Doc title...", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Button(
                        onClick = {
                            if (newDocTitle.isNotBlank()) {
                                viewModel.createNewDocument(newDocTitle)
                                newDocTitle = ""
                                isCreatingDoc = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(32.dp),
                    ) {
                        Text("Create Spec", fontSize = 11.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Filter docs...", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().height(44.dp),
            )

            Spacer(modifier = Modifier.height(8.dp))

            val filteredDocs = documents.filter {
                searchQuery.isBlank() || it.label.contains(searchQuery, ignoreCase = true) || it.id.contains(searchQuery, ignoreCase = true)
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filteredDocs, key = { it.id }) { doc ->
                    val isSelected = doc.id == selectedDocId
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                else Color.Transparent
                            )
                            .clickable { viewModel.selectDocument(doc.id) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = if (doc.type == NodeType.SPEC) "📄" else "📑",
                                    fontSize = 12.sp,
                                )
                                Text(
                                    text = doc.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                text = doc.id,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        VerticalDivider(
            modifier = Modifier.fillMaxHeight(),
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
        )

        // Main Editor Area + Bottom Inspector
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            // Editor Toolbar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = selectedDocId ?: "No document selected",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Button(
                    onClick = { viewModel.saveCurrentDocument() },
                    enabled = selectedDocId != null,
                    modifier = Modifier.height(32.dp),
                ) {
                    Text("Save & Parse AST", fontSize = 12.sp)
                }
            }

            // Editor Input
            Box(modifier = Modifier.weight(0.6f).fillMaxWidth().padding(16.dp)) {
                OutlinedTextField(
                    value = editorContent,
                    onValueChange = { viewModel.updateEditorContent(it) },
                    placeholder = { Text("Write markdown specification with [[wikilinks]]...") },
                    modifier = Modifier.fillMaxSize(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                )

                // Wikilink autocomplete popup
                if (showWikilinkSuggestions && wikilinkCandidates.isNotEmpty()) {
                    Card(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(bottom = 8.dp, start = 8.dp)
                            .width(300.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                text = "Link Target Suggestion",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            wikilinkCandidates.forEach { candidate ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable {
                                            viewModel.updateEditorContent(editorContent + "${candidate.id}]] ")
                                        }
                                        .padding(horizontal = 6.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = candidate.id,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = candidate.type.name,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
            )

            // Inspector Panel (Backlinks, references, impact)
            Box(modifier = Modifier.weight(0.4f).fillMaxWidth()) {
                BacklinksInspectorPanel(
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
