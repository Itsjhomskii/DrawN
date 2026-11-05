package drawn.model.event;

/** Lifecycle states for a venue booking. */
public enum BookingStatus {
    PENDING,
    APPROVED,
    DECLINED,
    PENDING_PAYMENT,
    CONFIRMED,
    CANCELLED,
    EXPIRED
}
