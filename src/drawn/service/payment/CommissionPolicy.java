package drawn.service.payment;

import java.math.BigDecimal;

/** Central platform commission configuration, expressed as a percentage (0–100). */
public final class CommissionPolicy {
    private final BigDecimal percent;

    public CommissionPolicy(BigDecimal percent) {
        if (percent == null || percent.signum() < 0 || percent.compareTo(new BigDecimal("100")) > 0)
            throw new IllegalArgumentException("Commission must be between 0 and 100 percent.");
        this.percent = percent.stripTrailingZeros();
    }

    public BigDecimal percent() { return percent; }

    public static CommissionPolicy configured() {
        String configured = System.getProperty("drawn.commission.percent", "10");
        try { return new CommissionPolicy(new BigDecimal(configured)); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("Invalid drawn.commission.percent setting.", ex); }
    }
}
