package drawn.model.event;

/** Customer submission status, separate from the event's scheduled lifecycle. */
public enum EventApprovalStatus {
    DRAFT,
    PENDING,
    APPROVED,
    REJECTED,
    PUBLISHED,
    CANCELLATION_PENDING,
    CANCELLED
}
