# Thoughtless User Guide & Architecture Reference

**Thoughtless** is a local-first, decentralized **Agentic Context Graph Engine** built with Kotlin Multiplatform (Compose Desktop), embedded ArcadeDB graph persistence, Flexmark Markdown AST ingestion, and ATProto federated sync.

---

## 1. Application Navigation & Views

The Compose Desktop interface features a persistent dark-theme 3-tab navigation rail on the left:

```
┌────┐ ┌─────────────────────────────────────────────────────────────┐
│ 📄 │ │ Documents Workspace (Specs, RFCs, Markdown AST & Backlinks) │
├────┤ ├─────────────────────────────────────────────────────────────┤
│ 📋 │ │ Tasks Workspace (Interactive Todo & Project Manager)        │
├────┤ ├─────────────────────────────────────────────────────────────┤
│ 🤖 │ │ Agent Queue (Topological DAG Tiers & Approval Workflow)     │
└────┘ └─────────────────────────────────────────────────────────────┘
```

1. **Docs (`📄`) — `DocumentWorkspaceView`**:
   - **Sidebar**: Lists root documents and formal specifications. Filter docs using the search box or click **`+ New`** to create a structured specification.
   - **Editor**: Monospace Markdown editor with live autocomplete for wikilinks when typing `[[`. Click **`Save & Parse AST`** to break documents down into section nodes, extract links, and update the graph.
   - **Graph Inspector Panel**: Displays real-time **Incoming Backlinks (`In`)**, **Outgoing Relations (`Out`)**, and **Multi-Hop Impact Analysis (`Impact`)** with clickable graph navigation.
2. **Tasks (`📋`) — `AppContent`**:
   - Traditional local-first task and project tracker.
   - Organize tasks by projects, set priorities (`None`, `Low`, `Medium`, `High`, `Urgent`), toggle completion, or delete tasks.
3. **Queue (`🤖`) — `AgentQueueView`**:
   - Displays tasks partitioned into **Execution Tiers** calculated via Kahn's topological sort algorithm.
   - Review agent work with one-click **Approve** or **Reject** actions.
   - Export machine-executable agent DAG specifications via **`Export Agent DAG JSON`**.

---

## 2. Wikilinks Specification & Relations

Thoughtless treats Markdown documents as living nodes in an embedded property graph. When you type `[[` in the editor, an autocomplete popup displays available graph nodes.

### 2.1 Basic Wikilinks

A standard wikilink establishes a default `REFERENCES` relationship from the current document or section to the destination node:

```markdown
See [[doc-security-overview]] for cryptographic requirements.
```
- **From**: The enclosing document or section ID (e.g. `doc-auth#architecture`).
- **To**: `doc-security-overview`.
- **Relation**: `REFERENCES`.

### 2.2 Aliased Wikilinks

Display custom link text without changing the target node ID using the pipe (`|`) delimiter:

```markdown
Read the [[doc-security-overview|Security Overview Guide]].
```
- **Target ID**: `doc-security-overview` (text after `|` is used for display).

### 2.3 Typed Relation Wikilinks

To establish semantic graph edges that govern downstream impact analysis and agent planning, prefix the target ID with a supported relation followed by a colon (`:`):

| Format | Relation Type | Semantic Meaning |
| :--- | :--- | :--- |
| `[[IMPLEMENTS:doc-auth]]` | `IMPLEMENTS` | This node or task implements the target specification/requirement. |
| `[[MUTATES:ArcadeDBEngine.kt]]` | `MUTATES` | This node or task directly modifies the target file or component. |
| `[[DEPENDS_ON:task-storage]]` | `DEPENDS_ON` | Upstream dependency constraint (used for Kahn DAG scheduling). |
| `[[DEPENDS:task-storage]]` | `DEPENDS_ON` | Alias for `DEPENDS_ON`. |
| `[[REFERENCES:doc-intro]]` | `REFERENCES` | General cross-reference (equivalent to omitting the prefix). |
| `[[REF:doc-intro]]` | `REFERENCES` | Alias for `REFERENCES`. |

### 2.4 Combining Typed Relations with Aliases

```markdown
This feature [[IMPLEMENTS:spec-auth-flow|Authentication Flow RFC]].
Ensures we satisfy [[DEPENDS_ON:task-db-migration|Database Schema Migration]].
```

### 2.5 Multi-Hop Impact Analysis

When you edit or inspect a node in the **Graph Inspector**, the engine traverses outgoing semantic edges (`REFERENCES`, `IMPLEMENTS`, `MUTATES`, `DEPENDS_ON`) up to 3 hops deep. If you modify a shared spec or data contract, all downstream components appear under the **Impact** tab.

---

## 3. Promoting Documents to Tasks

Thoughtless supports two pathways to turn architecture documents into actionable work:

### 3.1 Direct Promotion via Task Creation

In the **Tasks (`📋`)** view:
1. Create a task (e.g., `Setup Storage Layer`).
2. Add target files or context file wikilinks in the description:
   ```text
   Implement embedded ArcadeDB persistence. Targets [[MUTATES:ArcadeDBEngine.kt]].
   ```
3. Set the priority and associate it with a project.

### 3.2 AI-Assisted Spec Interview & DAG Decomposition

Using the background Spec Engine and DAG Decomposer services:
1. **Interactive Architect Interview** (`SpecEngineService`):
   - You supply an initial goal: `Implement WebAuthn passkey authentication`.
   - The local Ollama LLM acts as Principal Architect, asking clarifying questions on boundaries, inputs/outputs, and non-goals.
2. **Spec Freezing**:
   - Once questions are answered, the engine synthesizes a frozen specification (`specs/<slug>.md`) with YAML frontmatter, markdown sections, and `[[wikilinks]]`.
   - The Markdown AST parser breaks down headers into child section nodes (`spec-id#overview`, `spec-id#non-goals`) in ArcadeDB.
3. **DAG Decomposition** (`DAGDecomposerService`):
   - The specification is broken down into discrete executable tasks (`Task`) with concrete `targetFile`, `contextFiles`, `acceptanceCriteria`, and `dependsOn` arrays.
   - Tasks are persisted to the `TaskRepository` and linked in the graph with `DEPENDS_ON` edges.

---

## 4. Topological Execution Tiers & Agent Approval Workflow

Once tasks with `dependsOn` relationships exist in the graph, the **Agent Queue (`🤖`)** organizes and safeguards autonomous execution:

```
[Tier 1: Setup Storage Layer] ──> [Tier 2: Implement ATProto Sync] ──> [Tier 3: Launch Agent Worker]
```

### 4.1 Execution Tiers via Kahn's Algorithm
- **Tier 1**: All tasks with in-degree 0 (no unresolved dependencies). These can execute immediately in parallel.
- **Tier 2+**: Tasks that depend on Tier 1 tasks. They remain blocked until upstream tasks are completed.
- **Cycle Detection**: If circular dependencies exist (e.g. A → B → A), `CycleDetectedException` is thrown, alerting the user to break the cycle.

### 4.2 Agent Status Lifecycle & Human Approval

Every task in the queue transitions through a strict state machine:

```
[PENDING] ──(Agent picks task)──> [AGENT_RUNNING]
                                          │
                               (Agent submits code/patch)
                                          │
                                          ▼
                                 [AWAITING_REVIEW]
                                    │         │
                           (User clicks)   (User clicks)
                            "Approve"       "Reject"
                                │                 │
                                ▼                 ▼
                             [MERGED]         [PENDING]
                         (TaskStatus.DONE)
```

1. **`PENDING`**: Task is waiting for execution.
2. **`AGENT_RUNNING`**: Autonomous worker is currently implementing changes against specified `targetFile` and `contextFiles`.
3. **`AWAITING_REVIEW`**: Agent completes work and requests human review. The task card highlights green in the Agent Queue.
4. **User Action**:
   - **Click `Approve`**: Promotes status to `MERGED` and marks the underlying task as `TaskStatus.DONE`. Unblocks downstream dependent tasks in subsequent tiers.
   - **Click `Reject`**: Sends the task back to `PENDING` for re-execution or refinement.

### 4.3 Review Drawer: Discovered Proposals & Agent Scratchpad

In addition to scheduled tasks, autonomous workers can discover work dynamically or report progress:
- **Discovered Work Proposals (`propose_task`)**: Findings submitted by agents land in the **Review Drawer / Inbox** in `PROPOSED` status. They do not enter the active DAG until an operator reviews them (Accept, Refine & Accept, or Deny).
- **Agent Scratchpad (`update_task_progress`)**: While executing (`AGENT_RUNNING`), agents publish their `currentStep`, running `notes`, `touchedFiles`, and `completedCriteria` directly onto the task card for live visibility.

### 4.4 Exporting for External Agents

Click **`Export Agent DAG JSON`** in the Agent Queue to generate the complete JSON payload conforming to `thoughtless.agentTask`:

```json
{
  "specId": "export-default",
  "totalTasks": 2,
  "topologicalOrder": ["task-storage", "task-sync"],
  "executionTiers": [
    ["task-storage"],
    ["task-sync"]
  ],
  "tasks": [
    {
      "id": "task-storage",
      "title": "Setup Storage Layer",
      "type": "TASK",
      "targetFile": "ArcadeDBEngine.kt",
      "contextFiles": ["docs/agent-sync.md"],
      "dependsOn": [],
      "acceptanceCriteria": ["ArcadeDB engine starts without errors"]
    }
  ]
}
```
External CLI workers or autonomous LLMs can consume this JSON to execute the plan step-by-step.

---

## 5. Headless MCP Server & External Agent Coordination

Thoughtless includes a headless **Model Context Protocol (MCP)** server module (`:mcpServer`) that exposes the embedded ArcadeDB context graph, task management, and DAG decomposition engine over JSON-RPC 2.0 / stdio.

### 5.1 Connecting External Agents (Seamless Desktop & Headless Concurrency)

Thoughtless supports running autonomous agents while the Desktop app is open simultaneously:
- **Desktop In-Process Sidecar**: When the Desktop App starts, it automatically launches an in-process MCP socket listener on `127.0.0.1:8765` (configurable via `THOUGHTLESS_MCP_PORT`).
- **Auto-Proxy Bridge**: Running `./gradlew :mcpServer:run --quiet` automatically detects the running Desktop App and acts as a transparent stdio-to-socket proxy. Any tool calls (such as creating proposals or updating scratchpads) mutate the exact same in-memory models and embedded database live in the UI without database lock contention.
- **Standalone Fallback**: If the Desktop App is not running, `:mcpServer:run` opens the embedded ArcadeDB database directly at `~/.thoughtless/graph` (or `THOUGHTLESS_DB_PATH`).

To configure Claude Code, Cursor, or Antigravity to connect to Thoughtless:

```json
{
  "mcpServers": {
    "thoughtless": {
      "command": "./gradlew",
      "args": [":mcpServer:run", "--quiet"]
    }
  }
}
```

### 5.2 Multi-Agent Concurrency & Worktree Protocol
- **Disjoint Target Invariant**: The scheduler guarantees tasks in the same parallel execution tier never touch the same `(workspace, targetFile)`.
- **Git Worktree Isolation**: Agents should not mutate a shared working directory. When claiming a task via `claim_next_task`, agents should bind execution to an isolated git worktree:
  ```bash
  git worktree add -B "agent/$TASK_ID" "/tmp/workspaces/$TASK_ID" origin/main
  ```
- **Operator Identity & Workspaces**: Canonical repository identifiers (e.g. `github.com/org/repo`) are validated by `WorkspaceValidator`. Clones and credentials reside securely under `~/.thoughtless`.

---

## 6. Federated Sync (ATProto)

All local entities—**Specs**, **Context Nodes**, **Context Edges**, and **Agent Tasks**—are serialized according to the ATProto Lexicon schemas (`lexicons/thoughtless.*.json`). 

- **Push**: `GraphSyncService.pushLocalGraphToPds()` writes all local records to your personal data server (PDS).
- **Pull**: `GraphSyncService.pullPeerGraph(peerDid)` fetches records published by collaborator DIDs and merges them into ArcadeDB using Last-Write-Wins (LWW) conflict resolution.
