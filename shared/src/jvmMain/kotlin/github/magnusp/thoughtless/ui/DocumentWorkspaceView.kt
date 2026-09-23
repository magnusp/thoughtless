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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import github.magnusp.thoughtless.domain.model.ContextNode
import github.magnusp.thoughtless.domain.model.NodeType
import github.magnusp.thoughtless.domain.wikilink.WikilinkResolver

private enum class WorkspaceEditorMode {
    EDIT,
    PREVIEW,
}

@Composable
fun DocumentWorkspaceView(
    viewModel: WorkspaceViewModel,
    modifier: Modifier = Modifier,
) {
    val documents by viewModel.documentRoots.collectAsState()
    val allNodes by viewModel.allNodes.collectAsState()
    val selectedDocId by viewModel.selectedDocumentId.collectAsState()
    val editorContent by viewModel.editorContent.collectAsState()
    val navigationFeedback by viewModel.navigationFeedback.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var isCreatingDoc by remember { mutableStateOf(false) }
    var newDocTitle by remember { mutableStateOf("") }
    var editorMode by remember { mutableStateOf(WorkspaceEditorMode.EDIT) }
    var documentToDeleteForConfirmation by remember { mutableStateOf<String?>(null) }

    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val visualTransformation = remember { WikilinkVisualTransformation() }

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
                        placeholder = { Text("Spec Title") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Button(
                        onClick = {
                            if (newDocTitle.isNotBlank()) {
                                viewModel.createNewDocument(newDocTitle.trim())
                                newDocTitle = ""
                                isCreatingDoc = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(32.dp),
                    ) {
                        Text("Create Document", fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Search Filter
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search documents...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Documents tree
            val filteredDocs = documents.filter {
                it.label.contains(searchQuery, ignoreCase = true) ||
                        it.id.contains(searchQuery, ignoreCase = true)
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(filteredDocs, key = { it.id }) { doc ->
                    val isSelected = doc.id == selectedDocId
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { viewModel.selectDocument(doc.id) },
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = when (doc.type) {
                                        NodeType.SPEC -> "📄"
                                        NodeType.REQUIREMENT -> "📌"
                                        NodeType.ENTITY -> "📦"
                                        NodeType.ENDPOINT -> "⚡"
                                        NodeType.TABLE -> "🗄️"
                                        else -> "📝"
                                    },
                                    fontSize = 14.sp,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = doc.label,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = doc.id,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            IconButton(
                                onClick = { documentToDeleteForConfirmation = doc.id },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Text(
                                    text = "✕",
                                    fontSize = 11.sp,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = selectedDocId ?: "No document selected",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    // Navigation Hint Badge
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = "Shift + Click wikilink to navigate",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Mode Toggle: Edit vs Preview
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    ) {
                        Row {
                            TextButton(
                                onClick = { editorMode = WorkspaceEditorMode.EDIT },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = if (editorMode == WorkspaceEditorMode.EDIT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                modifier = Modifier.height(28.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("Edit", fontSize = 11.sp, fontWeight = if (editorMode == WorkspaceEditorMode.EDIT) FontWeight.Bold else FontWeight.Normal)
                            }
                            TextButton(
                                onClick = { editorMode = WorkspaceEditorMode.PREVIEW },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = if (editorMode == WorkspaceEditorMode.PREVIEW) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                modifier = Modifier.height(28.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("Preview", fontSize = 11.sp, fontWeight = if (editorMode == WorkspaceEditorMode.PREVIEW) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }

                    Button(
                        onClick = { viewModel.saveCurrentDocument() },
                        enabled = selectedDocId != null,
                        modifier = Modifier.height(32.dp),
                    ) {
                        Text("Save & Parse AST", fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = { documentToDeleteForConfirmation = selectedDocId },
                        enabled = selectedDocId != null,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                    ) {
                        Text("Drop Document", fontSize = 12.sp)
                    }
                }
            }

            // Feedback Banner (e.g. Navigated to doc, or doc not found)
            if (navigationFeedback != null) {
                Surface(
                    color = if (navigationFeedback!!.contains("not found") || navigationFeedback!!.contains("Invalid")) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        Color(0xFF065F46)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = navigationFeedback!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (navigationFeedback!!.contains("not found") || navigationFeedback!!.contains("Invalid")) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                Color(0xFFD1FAE5)
                            }
                        )
                        TextButton(
                            onClick = { viewModel.clearNavigationFeedback() },
                            modifier = Modifier.height(24.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                        ) {
                            Text("✕", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }

            // Editor / Preview Input Area
            Box(modifier = Modifier.weight(0.6f).fillMaxWidth().padding(16.dp)) {
                if (editorMode == WorkspaceEditorMode.EDIT) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = editorContent,
                        onValueChange = { viewModel.updateEditorContent(it) },
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(editorContent, selectedDocId) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        if (event.keyboardModifiers.isShiftPressed) {
                                            for (change in event.changes) {
                                                if (change.pressed) {
                                                    val layout = textLayoutResult
                                                    if (layout != null) {
                                                        val offset = layout.getOffsetForPosition(change.position)
                                                        val link = WikilinkResolver.findWikilinkAtOffset(
                                                            text = editorContent,
                                                            offset = offset,
                                                            currentDocumentId = selectedDocId
                                                        )
                                                        if (link != null) {
                                                            viewModel.navigateToWikilink(link.targetNodeId)
                                                            change.consume()
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                        visualTransformation = visualTransformation,
                        onTextLayout = { textLayoutResult = it },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        decorationBox = { innerTextField ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                                ),
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                                    if (editorContent.isEmpty()) {
                                        Text(
                                            "Write markdown specification with [[wikilinks]]...",
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 13.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                            )
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        }
                    )
                } else {
                    // Preview Mode with interactive Wikilinks
                    MarkdownPreviewWithWikilinks(
                        content = editorContent,
                        currentDocId = selectedDocId,
                        onNavigate = { targetId -> viewModel.navigateToWikilink(targetId) }
                    )
                }

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

    // Confirmation Dialog for Dropping a Document
    val docToDrop = documentToDeleteForConfirmation
    if (docToDrop != null) {
        AlertDialog(
            onDismissRequest = { documentToDeleteForConfirmation = null },
            title = {
                Text(
                    text = "Drop Document?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to drop document '$docToDrop'? This will remove the document, its section anchors, and all associated graph relationships.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteDocument(docToDrop)
                        documentToDeleteForConfirmation = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text("Drop")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { documentToDeleteForConfirmation = null }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun MarkdownPreviewWithWikilinks(
    content: String,
    currentDocId: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val wikilinks = remember(content) { WikilinkResolver.extractWikilinks(content, currentDocId) }

    Surface(
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxSize().padding(4.dp)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    text = "Rendered Markdown Preview",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            if (wikilinks.isNotEmpty()) {
                item {
                    Text(
                        text = "Interactive Wikilinks in Document:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        wikilinks.forEach { link ->
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { onNavigate(link.targetNodeId) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text("🔗", fontSize = 11.sp)
                                    Text(
                                        text = link.alias ?: link.targetNodeId,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            // Raw rendered text
            item {
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        }
    }
}
