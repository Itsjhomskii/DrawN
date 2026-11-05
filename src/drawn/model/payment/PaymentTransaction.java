package drawn.model.payment;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

public record PaymentTransaction(String id, String customerId, String referenceId, PaymentPurpose purpose,
                                 BigDecimal amount, PaymentStatus status, LocalDateTime timestamp) {
    public PaymentTransaction {
        Objects.requireNonNull(id); Objects.requireNonNull(customerId); Objects.requireNonNull(referenceId);
        Objects.requireNonNull(purpose); Objects.requireNonNull(amount); Objects.requireNonNull(status);
        Objects.requireNonNull(timestamp);
    }
}
