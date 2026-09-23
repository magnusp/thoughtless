# Thoughtless MCP Protocol Specification

The **Model Context Protocol (MCP)** interface exposes Thoughtless's embedded **ArcadeDB context graph**, **document AST parser**, and **DAG decomposition engine** directly to autonomous LLM coding agents (e.g., Claude Code, Cursor, Antigravity, Aider).

By delegating task refinement, graph queries, and DAG validation to MCP tools, external LLMs can autonomously inspect, refine, decompose, and schedule tasks without interacting with the Desktop Compose UI.

---

## Architecture Overview

```
┌────────────────────────────────────────────────────────┐
│ External LLMs / Autonomous Agents (Claude Code, etc.)  │
└───────────────────────────┬────────────────────────────┘
                            │ Model Context Protocol (stdio / JSON-RPC)
                            ▼
┌────────────────────────────────────────────────────────┐
│            Thoughtless Headless MCP Server             │
├────────────────────────────────────────────────────────┤
│  • Task Refinement & Decomposition                     │
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

---

## MCP Tools Reference

### 1. Task Refinement & Decomposition

These tools allow an external LLM to act as the architect/planner—taking ambiguous requirements or specifications and refining them into machine-executable DAG nodes.

#### `refine_task`
Updates task details with unambiguous requirements, acceptance criteria, and explicit target files.
- **Parameters**:
  - `taskId` (string, required): Unique identifier of the task.
  - `title` (string, optional): Refined concise task title.
  - `description` (string, optional): Refined task description with contextual wikilinks.
  - `acceptanceCriteria` (string[], optional): Concrete, verifiable criteria required for agent completion.
  - `targetFile` (string, optional): Primary file the autonomous agent is expected to mutate (e.g. `ArcadeDBEngine.kt`).
  - `contextFiles` (string[], optional): Associated spec, schema, or test files needed for context.
  - `priority` (integer `0..4`, optional): Task priority (`NONE=0`, `LOW=1`, `MEDIUM=2`, `HIGH=3`, `URGENT=4`).
- **Returns**: Updated task entity.

#### `decompose_task`
Splits a complex parent task or high-level specification into a set of atomic, DAG-schedulable subtasks with explicit dependency links.
- **Parameters**:
  - `parentTaskId` (string, optional): ID of the task being broken down.
  - `specId` (string, optional): ID of the specification document driving the tasks.
  - `subtasks` (array of objects, required):
    - `id` (string, required): Unique subtask slug (e.g. `task-auth-token-refresh`).
    - `title` (string, required): Subtask title.
    - `targetFile` (string, optional): File path mutated by this task.
    - `contextFiles` (string[], optional): Context reference files.
    - `dependsOn` (string[], required): IDs of prerequisite tasks that must complete before this task.
    - `acceptanceCriteria` (string[], required): Step-by-step verification criteria.
- **Returns**: Created tasks and the generated `DEPENDS_ON` graph edges.

#### `validate_task_dag`
Executes Kahn's topological sort and cycle detection over a set of tasks to verify execution feasibility before freezing the plan.
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
- **Returns**: Task details, resolved context files, and acceptance criteria; marks task status as `AGENT_RUNNING`.

#### `submit_task_for_review`
Submits completed agent work (patch, branch, or test results) and transitions the task into `AWAITING_REVIEW`.
- **Parameters**:
  - `taskId` (string, required): ID of the task.
  - `agentId` (string, required): Identifier of the agent submitting work.
  - `patchSummary` (string, required): Summary of code changes made.
  - `verificationOutput` (string, optional): Test execution log or verification proof.
- **Returns**: Updated task state (`AWAITING_REVIEW`). Ready for human approval in the Desktop UI.

---

## Example MCP Client Configuration

To configure Claude Code, Cursor, or Antigravity to connect to the Thoughtless MCP server:

```json
{
  "mcpServers": {
    "thoughtless": {
      "command": "./gradlew",
      "args": [":mcpServer:run", "--quiet"],
      "env": {
        "THOUGHTLESS_DB_PATH": "/home/user/.thoughtless/arcadedb"
      }
    }
  }
}
```
