# Thoughtless

**Thoughtless** is a local-first, decentralized **Agentic Context Graph Engine** built with Kotlin Multiplatform (Compose Desktop), embedded ArcadeDB graph persistence, Flexmark Markdown AST ingestion, and ATProto federated sync.

For complete architectural details, see the [User Guide & Architecture Reference](docs/user-guide.md), [MCP Protocol Specification](docs/mcp-specification.md), and [ATProto Architecture Findings](docs/atproto-spike-findings.md).

---

## Key Features & User Workflow

The Compose Desktop app provides a 3-rail workspace for managing software architecture and coordinating agentic execution:

1. **Documents Workspace (`📄 Docs`)**:
   - Write Markdown specifications and system RFCs with live autocomplete for `[[wikilinks]]`.
   - Click **Save & Parse AST** to ingest documents into the embedded ArcadeDB property graph as discrete section nodes and typed relation edges.
   - **Graph Inspector Panel**: Live inspection of incoming backlinks, outgoing edges, and **multi-hop impact analysis** (up to 3 hops deep).
2. **Tasks Manager (`📋 Tasks`)**:
   - Manage and organize actionable tasks across projects with custom priorities and completion states.
3. **Agent Task Execution Queue (`🤖 Queue`)**:
   - Automatically organizes tasks into parallel **Execution Tiers** using Kahn's topological sort DAG decomposition.
   - **Agent Approval Workflow**: Review agent-completed tasks (`AWAITING_REVIEW`) with one-click **Approve** (promotes to `MERGED` / `DONE` and unblocks dependent tasks) or **Reject** (returns to `PENDING`).
   - Export machine-executable plans via **Export Agent DAG JSON**.

---

## Wikilinks Format & Typed Relations

Thoughtless links Markdown documents into the graph using `[[wikilinks]]`:

- **Standard Reference**: `[[doc-security-overview]]` (creates a `REFERENCES` edge).
- **Aliased Link**: `[[doc-security-overview|Security Guide]]` (renders label while linking target ID).
- **Typed Relations**: Prefix the target ID with a relation and colon (`:`):
  - `[[IMPLEMENTS:doc-auth]]`: Indicates the current section/task implements the target specification.
  - `[[MUTATES:ArcadeDBEngine.kt]]`: Identifies files or components modified by this item.
  - `[[DEPENDS_ON:task-storage]]` or `[[DEPENDS:task-storage]]`: Enforces upstream dependency constraints for DAG scheduling.
  - `[[REFERENCES:doc-intro]]` or `[[REF:doc-intro]]`: Explicit cross-referencing.

---

## Promoting Work & Agentic Lifecycle

```
[Markdown Spec / RFC] ──(Decomposition)──> [Tasks with DEPENDS_ON edges]
                                                      │
                                           (Kahn's Topological Sort)
                                                      │
                                                      ▼
                                              [Execution Tiers]
                                                      │
                                             (Agent Execution)
                                                      │
                                                      ▼
                                              [AWAITING_REVIEW]
                                                │           │
                                       (Approve)▼           ▼(Reject)
                                            [MERGED]     [PENDING]
```

1. **Promote to Tasks**:
   - Directly create tasks referencing target files and specs via wikilinks.
   - Or run the AI architect interview (`SpecEngineService`) and decompose specifications into topologically sorted tasks (`DAGDecomposerService`).
2. **Review & Approve Agent Work**:
   - In the **Agent Queue** tab, tasks whose dependencies are met execute in Tier 1.
   - When an agent finishes, the card transitions to `AWAITING_REVIEW`.
   - Click **Approve** on the task card to merge the change, mark it `DONE`, and dynamically release downstream dependent tasks in subsequent tiers.

---

## Running the Application

### Desktop App
- Standard run: `./gradlew :desktopApp:run`
- Hot reload: `./gradlew :desktopApp:hotRun --auto`

### Running Tests
- Desktop & Shared Tests: `./gradlew :shared:jvmTest`
- Full Project Verification: `./gradlew check`

### Local ATProto PDS (Docker Compose)
Run a local ATProto Personal Data Server for federated sync testing:
```bash
./scripts/start-local-pds.sh
```
Or start via Docker Compose directly:
```bash
docker compose -f docker/docker-compose.pds.yml up -d
```

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)…