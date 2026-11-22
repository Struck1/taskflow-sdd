# Principles — TaskFlow Pro

1. **Workspace isolation.** Data from one workspace MUST never be
   visible to another. Every query on workspace-owned data is filtered
   by `workspaceId`.
2. **Rules live in the domain or the database.** A business rule MUST
   be enforced in an aggregate or a database constraint — never only
   in a controller or UI.
3. **No code before approval.** Implementation MUST NOT start until the
   feature's `.status` is `tasks:approved`. A file existing on disk is
   not approval.
4. **When in doubt, it is out of scope.** If it is unclear whether
   something is in scope, it is not. Stop and ask.
5. **No silent failures.** Errors in automations and notifications
   MUST be logged and retried — never swallowed.
6. **Time is UTC.** Timestamps are stored as `timestamptz` in UTC and
   converted to the user's timezone only at the edges.