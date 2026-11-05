package drawn.model.event;

/** Customer submission status, separate from the event's scheduled lifecycle. */
public enum EventApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED
}
