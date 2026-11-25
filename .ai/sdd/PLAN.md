# Plan - TaskFlow Pro MVP

## Phases

### Phase 1: Foundation
| Spec | Module | Scope |
|------|--------|-------|
| 001-auth | `auth` | Sign-up, login (email/password + magic link), server-side sessions |
| 002-workspaces | `workspace` | Workspaces, members, roles, invites |

### Phase 2: Core
| Spec | Module | Scope |
|------|--------|-------|
| 003-tasks | `task` | Tasks, subtasks, tags, due dates, assignees |

### Phase 3: Differentiators
| Spec | Module | Scope |
|------|--------|-------|
| 004-automations | `automation` | Rules: when X happens, do Y |
| 005-notifications | `notification` | Real-time notifications over WebSocket |

## Dependencies
- 002-workspaces needs 001-auth: a member is an authenticated `User`.
- 003-tasks needs 002-workspaces: every task belongs to a workspace.
- 004-automations needs 003-tasks: rules react to task events
  (e.g. `TaskCompleted`).
- 005-notifications needs 003-tasks: notifications are sent on task
  events.
- 004 and 005 do not depend on each other and can be built in parallel.

## Out of MVP
Google Calendar integration, recurring tasks, Kanban boards,
task comments, public API — see `product.md` (Future Features).