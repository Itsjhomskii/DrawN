package drawn.service.payment;

import java.math.BigDecimal;

public record OrganizerEarnings(BigDecimal grossTicketRevenue, BigDecimal platformCommission,
                                BigDecimal netEarnings, int ticketsSold, int verifiedTransactions) { }
