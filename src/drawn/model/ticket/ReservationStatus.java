package drawn.model.ticket;

/** Lifecycle states for a public-event ticket reservation. */
public enum ReservationStatus {
    PENDING_PAYMENT,
    CONFIRMED,
    CANCELLED,
    EXPIRED
}
