# Thoughtless MCP Protocol Specification

The **Model Context Protocol (MCP)** interface exposes Thoughtless's embedded **ArcadeDB context graph**, **document AST parser**, and **DAG decomposition engine** directly to autonomous LLM coding agents (e.g., Claude Code, Cursor, Antigravity, Aider).

By delegating task refinement, graph queries, and DAG validation to MCP tools, external LLMs can autonomously inspect, refine, decompose, and schedule tasks without interacting with the Desktop Compose UI.

---

## Architecture Overview & Governance Model

```
┌────────────────────────────────────────────────────────┐
│ External LLMs / Autonomous Agents (Claude Code, etc.)  │
└───────────────────────────┬────────────────────────────┘
                            │ Model Context Protocol (stdio / JSON-RPC)
                            ▼
┌────────────────────────────────────────────────────────┐
│            Thoughtless Headless MCP Server             │
├────────────────────────────────────────────────────────┤
│  • Task Refinement & Bound Decomposition               │
│  • Discovered Work -> Proposals (`propose_task`)       │
│  • AST Wikilink Resolution (`MUTATES`, `DEPENDS_ON`)   │
│  • Graph Traversal & Impact Analysis (ArcadeDB)        │
│  • Kahn's Topological Sort & Cycle Detection           │
└───────────────────────────┬────────────────────────────┘
                            │ Read / Write
                            ▼
┌────────────────────────────────────────────────────────┐
│           Embedded ArcadeDB Property Graph             │
└────────────────────────────────────────────────────────┘
```

### Human-in-the-Loop Governance: Tasks vs. Proposals

To prevent autonomous agents from hallucinating scope, diverging into unvetted refactors, or polluting the active Kahn DAG, Thoughtless enforces a strict separation:

1. **Active Tasks (`Task`)**:
   - Only exist in the active execution DAG.
   - Origin: Human created, approved from frozen specs via bounded decomposition (`decompose_task`), or explicitly promoted from a proposal by an operator.
   - Status transitions: `PENDING` -> `AGENT_RUNNING` -> `AWAITING_REVIEW` -> `MERGED` / `DONE`.
2. **Discovered Work / Findings (`TaskProposal`)**:
   - Origin: Autonomous agents exploring code, uncovering edge cases, missing dependencies, or technical debt during execution.
   - Status: `PROPOSED` (held in the **Review Drawer / Inbox**).
   - **Never enters the active DAG automatically**.
   - Requires Human Operator action:
     - **Accept**: Promotes proposal to a first-class `Task` and links it into the DAG.
     - **Refine & Accept**: Operator edits title, priority, target files, or acceptance criteria before scheduling.
     - **Deny / Dismiss**: Discards or archives the finding without scheduling.

---

## MCP Tools Reference

### 1. Task Refinement & Proposals

These tools allow an external LLM to act as the architect/planner—refining existing work or submitting newly discovered findings for operator governance.

#### `propose_task`
Proposes a new task, architectural spike, exploration, or edge case discovered dynamically by an agent during planning or execution.
- **Parameters**:
  - `title` (string, required): Clear title describing the discovered requirement.
  - `rationale` (string, required): Why this work is necessary, what was discovered, or which file prompted this finding.
  - `type` (string, enum: `TASK`, `SPIKE`, `EXPLORATION`, `RFC`, optional, default `TASK`): Classification of the proposed work.
  - `suggestedWorkspace` (string, optional): Canonical repository identifier (e.g. `github.com/org/repo`) or logical name. Host absolute paths are forbidden.
  - `suggestedTargetFile` (string, optional): Relative target file path the agent identified as needing modification.
  - `suggestedContextFiles` (string[], optional): Relative context documents or tests.
  - `suggestedDependsOn` (string[], optional): Existing task IDs this proposal would depend on.
  - `acceptanceCriteria` (string[], optional): Recommended verification criteria.
  - `sourceTaskId` (string, optional): The ID of the task the agent was executing when this discovery was made.
- **Returns**: Created `TaskProposal` entity in `PROPOSED` status. (Does **not** mutate active execution tiers).

#### `refine_task`
Updates an existing approved task with unambiguous requirements, acceptance criteria, and explicit target files.
- **Parameters**:
  - `taskId` (string, required): Unique identifier of the task.
  - `title` (string, optional): Refined concise task title.
  - `description` (string, optional): Refined task description with contextual wikilinks.
  - `workspace` (string, optional): Canonical repository URL or logical workspace name. Host absolute paths are forbidden.
  - `acceptanceCriteria` (string[], optional): Concrete, verifiable criteria required for agent completion.
  - `targetFile` (string, optional): Primary relative file the autonomous agent is expected to mutate (e.g. `ArcadeDBEngine.kt`).
  - `contextFiles` (string[], optional): Associated spec, schema, or test files needed for context (all relative paths).
  - `priority` (integer `0..4`, optional): Task priority (`NONE=0`, `LOW=1`, `MEDIUM=2`, `HIGH=3`, `URGENT=4`).
- **Returns**: Updated task entity.

#### `decompose_task`
Splits a complex, **already approved** parent task or formal specification into atomic, DAG-schedulable subtasks within its frozen scope.
- **Parameters**:
  - `parentTaskId` (string, optional): ID of the approved parent task being broken down.
  - `specId` (string, optional): ID of the frozen specification document driving the decomposition.
  - `subtasks` (array of objects, required):
    - `id` (string, required): Unique subtask slug (e.g. `task-auth-token-refresh`).
    - `title` (string, required): Subtask title.
    - `workspace` (string, optional): Canonical workspace identifier.
    - `targetFile` (string, optional): File path mutated by this task (relative).
    - `contextFiles` (string[], optional): Context reference files (relative).
    - `dependsOn` (string[], required): IDs of prerequisite tasks that must complete before this task.
    - `acceptanceCriteria` (string[], required): Step-by-step verification criteria.
- **Returns**: Created tasks and the generated `DEPENDS_ON` graph edges.

#### `validate_task_dag`
Executes Kahn's topological sort and cycle detection over a set of tasks to verify execution feasibility before freezing the plan. Automatically ensures the **Disjoint Target Invariant**: tasks with identical `(workspace, targetFile)` are sequenced into distinct execution tiers.
- **Parameters**:
  - `taskIds` (string[], optional): Subset of tasks to validate (defaults to all pending tasks in the project/spec).
- **Returns**:
  - `isValid` (boolean): `true` if acyclic and valid.
  - `executionTiers` (string[][]): Parallel execution tiers (Tier 1 tasks have in-degree 0; Tier 2 depends on Tier 1, etc.).
  - `cycles` (string[], optional): Cycle path if a dependency cycle is detected (e.g., `A -> B -> A`).

---

### 2. Context Graph Traversal & Impact Analysis

Tools enabling the LLM to query the property graph and understand architectural ripple effects before suggesting changes.

#### `get_node_context`
Retrieves a node's content, properties, and direct relationships.
- **Parameters**:
  - `nodeId` (string, required): ID of the document, section, or task (e.g. `spec-auth#token-exchange`).
- **Returns**: Node metadata, markdown body, incoming backlinks (`in`), and outgoing links (`out`).

#### `analyze_impact`
Traverses the graph up to $N$ hops deep along semantic edges (`MUTATES`, `DEPENDS_ON`, `IMPLEMENTS`, `REFERENCES`) to discover all downstream nodes impacted by modifying a file or specification.
- **Parameters**:
  - `nodeId` (string, required): Target document, specification, or file node.
  - `maxHops` (integer, optional, default `3`): Traversal depth.
  - `relationTypes` (string[], optional): Filter edges (e.g. `["MUTATES", "DEPENDS_ON"]`).
- **Returns**: Tree of impacted files, specifications, and tasks with relation types.

#### `query_graph`
Executes an OpenCypher query directly against the embedded ArcadeDB database.
- **Parameters**:
  - `query` (string, required): OpenCypher query string (e.g. `MATCH (t:Task)-[:DEPENDS_ON]->(dep:Task) RETURN t.id, dep.id`).
- **Returns**: Structured query records.

---

### 3. Agent Execution & Review Lifecycle

Tools for coordinating autonomous task execution, submitting results, and transitioning through human-in-the-loop review.

#### `claim_next_task`
Claims the highest priority task ready for execution from Tier 1 (tasks with all dependencies satisfied).
- **Parameters**:
  - `agentId` (string, required): Identifier of the executing agent or session.
- **Returns**: Task details including canonical `workspace` identifier, resolved relative `targetFile` and `contextFiles`, and acceptance criteria; marks task status as `AGENT_RUNNING`.

#### Multi-Agent Concurrency & Worktree Protocol
When multiple agents execute concurrently on the same host machine against the same repository/workspace:
1. **Disjoint Target Invariant**: The scheduler guarantees tasks in the same parallel tier never touch the same `(workspace, targetFile)`.
2. **Git Worktree Isolation**: Agents MUST NOT share a dirty working directory. When claiming a task, each agent binds execution to a private worktree:
   ```bash
   git worktree add -B "agent/$TASK_ID" "/tmp/workspaces/$TASK_ID" origin/main
   ```
   All edits, build commands, and tests are executed within that dedicated worktree directory. Upon completion and submission, the worktree is cleaned up.

#### `update_task_progress`
Continuously updates the agent execution scratchpad / workspace while in the execution loop (`AGENT_RUNNING`). Allows the agent to record its current approach, touched files, completed criteria checklist, and notes for live operator visibility and context recovery.
- **Parameters**:
  - `taskId` (string, required): Unique identifier of the task.
  - `currentStep` (string, optional): Short summary of current activity (e.g. `"Running regression tests for DAG sort"`).
  - `notes` (string, optional): Running hypotheses, blockers, or architectural observations.
  - `touchedFiles` (string[], optional): Files modified or inspected so far (relative paths).
  - `completedCriteria` (string[], optional): Subset of acceptance criteria successfully verified.
- **Returns**: Updated `AgentScratchpad` entity with confirmation timestamp.

#### `submit_task_for_review`
Submits completed agent work and transitions the task into `AWAITING_REVIEW`. Because continuous execution history and context are maintained in the task scratchpad (`update_task_progress`), this tool is slimmed down to essential review findings.
- **Parameters**:
  - `taskId` (string, required): ID of the task.
  - `reviewSummary` (string, required): Concise high-level summary for human review.
  - `diffUrlOrBranch` (string, optional): Diff URL, patch reference, or git branch name.
  - `testsPassed` (boolean, required): Whether automated test suites / verification commands passed.
  - `verificationOutput` (string, optional): Concise test execution log or verification output snippet.
- **Returns**: Updated task state (`AWAITING_REVIEW`). Ready for human approval in the Desktop UI.

---

## Example MCP Client Configuration

To configure Claude Code, Cursor, or Antigravity to connect to the Thoughtless MCP server:

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
> [!TIP]
> If the Thoughtless Desktop App is open, `:mcpServer:run` automatically bridges all stdio JSON-RPC traffic to the Desktop App's in-process sidecar on `127.0.0.1:8765`, providing live UI updates and zero database lock conflicts. If the Desktop App is closed, `:mcpServer:run` launches standalone with embedded ArcadeDB at `~/.thoughtless/graph`.

