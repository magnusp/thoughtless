# Spike 2: ATProto Connectivity — Findings & Architecture Decisions

This document captures the evaluation, architecture choices, and implementation details resulting from [Spike 2 (Issue #5)](https://github.com/magnusp/thoughtless/issues/5).

---

## 1. Client Library Evaluation

### Evaluated Options
1. **Third-party SDKs (`christiandeange/ozone`, `atproto-kotlin`, `kbsky`)**:
   - **`christiandeange/ozone`**: A comprehensive Kotlin Multiplatform SDK for ATProto and Bluesky. It provides Ktor-based XRPC transport, ATProto OAuth 2.0 (with PKCE and DPoP), Jetstream client, and rich text facet rendering in Compose. It generates code primarily against upstream official Bluesky lexicons (`app.bsky.*`).
   - **`atproto-kotlin` / `kbsky`**: Similar multiplatform wrappers focused on official social client lexicons.
   - **Trade-offs for Thoughtless**:
     - Pre-packaged SDKs focus almost exclusively on social feed lexicons (`app.bsky.feed.post`, etc.). Custom application records (`thoughtless.task`, `thoughtless.project`) require either running their lexicon codegen toolchain or falling back to generic XRPC calls.
     - Heavy external SDKs introduce coupling to specific Kotlin compiler versions and Ktor release cycles, increasing risk of version conflict with Compose Multiplatform.
     - However, `christiandeange/ozone` has valuable reference implementations for ATProto OAuth 2.0 and DPoP that could be leveraged when upgrading from app passwords to full OAuth in future milestones.
2. **Custom Ktor XRPC Client (Chosen for v1 foundation)**:
   - Built directly on top of standard `ktor-client` (3.5.2) and `kotlinx-serialization-json` (1.11.0).
   - Native Kotlin Multiplatform support (`commonMain`).
   - Directly maps custom typed records (`thoughtless.task`, `thoughtless.project`) without code-generator plugins.
   - Clean, typed interface for core ATProto XRPC endpoints:
     - `com.atproto.server.createSession`
     - `com.atproto.repo.createRecord`
     - `com.atproto.repo.getRecord`
     - `com.atproto.repo.listRecords`
     - `com.atproto.repo.deleteRecord`
   - Zero unnecessary dependencies, transparent error handling, and trivial mock testing via Ktor's `MockEngine`.

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
