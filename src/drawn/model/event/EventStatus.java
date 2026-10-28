package drawn.model.event;

/** Lifecycle states for an event, independent of its booking payment state. */
public enum EventStatus {
    SCHEDULED,
    PUBLISHED,
    CANCELLED,
    COMPLETED
}
