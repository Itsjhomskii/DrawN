package drawn.model.payment;

/** Status of a payment awaiting manual review or already processed. */
public enum PaymentStatus {
    PENDING_VERIFICATION,
    VERIFIED,
    REJECTED,
    EXPIRED
}
