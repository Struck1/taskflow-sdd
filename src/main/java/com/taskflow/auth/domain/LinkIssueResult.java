package com.taskflow.auth.domain;

/** Outcome of asking a {@link User} for a new verification or magic link (FR-005, FR-009). */
public sealed interface LinkIssueResult {

    /** A new link was issued. Older unused links of the same purpose were invalidated. */
    record Issued(LinkTokenId linkTokenId) implements LinkIssueResult {
    }

    /** The limit of links per window was reached. Nothing changed, and nothing must be sent. */
    record RateLimited() implements LinkIssueResult {
    }
}
