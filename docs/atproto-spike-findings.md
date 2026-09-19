# Spike 2: ATProto Connectivity — Findings & Architecture Decisions

This document captures the evaluation, architecture choices, and implementation details resulting from [Spike 2 (Issue #5)](https://github.com/magnusp/thoughtless/issues/5).

---

## 1. Client Library Evaluation

### Evaluated Options
1. **Third-party SDKs (`atproto-kotlin`, `kbsky`)**:
   - Both libraries are designed specifically for Bluesky's social network lexicons (`app.bsky.feed.post`, etc.).
   - Using them for custom schemas (`thoughtless.task`) requires either writing a code generator plugin for Lexicon files or falling back to raw low-level calls.
   - Introducing external SDKs adds risk of compiler/runtime version mismatch with Compose Multiplatform and newer Kotlin versions.
2. **Custom Ktor XRPC Client (Chosen)**:
   - Built directly on top of `ktor-client` and `kotlinx-serialization-json`.
   - Native Kotlin Multiplatform support (`commonMain`).
   - Clean, typed interface for ATProto XRPC endpoints:
     - `com.atproto.server.createSession`
     - `com.atproto.repo.createRecord`
     - `com.atproto.repo.getRecord`
     - `com.atproto.repo.listRecords`
     - `com.atproto.repo.deleteRecord`
   - Zero unnecessary dependencies or generated overhead.
   - Straightforward unit and integration testing via Ktor's `MockEngine`.

---

## 2. Lexicon Schema Definitions

The Lexicon schema definitions are stored in the `/lexicons` directory:

- **`thoughtless.task`** ([`lexicons/thoughtless.task.json`](../lexicons/thoughtless.task.json)):
  - Record key type: `tid`
  - Fields: `title`, `description`, `status` (`TODO`, `IN_PROGRESS`, `DONE`, `CANCELLED`), `priority` (0..4), `dueDate`, `projectId`, `createdAt`, `updatedAt`, `completedAt`.
- **`thoughtless.project`** ([`lexicons/thoughtless.project.json`](../lexicons/thoughtless.project.json)):
  - Record key type: `tid`
  - Fields: `name`, `description`, `color`, `createdAt`, `updatedAt`.
- **`thoughtless.label`** ([`lexicons/thoughtless.label.json`](../lexicons/thoughtless.label.json)):
  - Record key type: `tid`
  - Fields: `name`, `color`.

---

## 3. PDS Hosting Strategy

| Strategy | Feasibility | Trade-offs | Recommendation |
| :--- | :--- | :--- | :--- |
| **Hosted PDS (e.g. `bsky.social`)** | Immediate | Requires Bluesky account (App Password). Relies on public ATProto relay network. | **Default for v1 sync** — zero infrastructure setup for users. |
| **Self-Hosted PDS** | High | User runs official PDS container (`@atproto/pds` or `indigo`). Full user ownership. | **Supported** via configurable `pdsUrl`. |
| **Embedded Local PDS** | Medium/High | Bundling a local Go/Node binary or in-memory PDS inside the desktop app. | Complex distribution footprint; unnecessary when local SQLite already serves as the local source of truth. |

**Decision**: 
Thoughtless will treat the PDS URL as user-configurable. By default, users can authenticate against their existing Bluesky account on `https://bsky.social` using an App Password, or point to any self-hosted PDS.

---

## 4. Sync Architecture (Convergence of Spike 1 & Spike 2)

```
┌────────────────────────────────────────────────────────┐
│                   Desktop Compose UI                   │
└───────────────────────────┬────────────────────────────┘
                            │ StateFlow / Actions
┌───────────────────────────▼────────────────────────────┐
│                   TaskListViewModel                    │
└───────────────────────────┬────────────────────────────┘
                            │
┌───────────────────────────▼────────────────────────────┐
│                    TaskRepository                      │
│                                                        │
│   ┌────────────────────────┐   ┌───────────────────┐   │
│   │   Local SQLite Store   │   │  ATProto Sync     │   │
│   │   (SQLDelight 2.3.2)   │◄──┤  (XRPC Client)    │   │
│   └────────────────────────┘   └─────────┬─────────┘   │
└──────────────────────────────────────────┼─────────────┘
                                           │ HTTPS (XRPC)
                                 ┌─────────▼─────────┐
                                 │   ATProto PDS     │
                                 │ (bsky.social /    │
                                 │  self-hosted)     │
                                 └───────────────────┘
```

- **Local-first guarantee**: The application always reads and writes to local SQLite first. The app is 100% operational offline.
- **Sync worker**: A background sync adapter observes SQLite changes and pushes record commits to the ATProto PDS (`createRecord`, `putRecord`, `deleteRecord`). Incoming commits from the PDS are merged into SQLite.
