package drawn.service.payment;

import drawn.model.event.Event;
import drawn.model.payment.PaymentPurpose;
import drawn.model.payment.PaymentStatus;
import drawn.model.payment.PaymentTransaction;
import drawn.model.user.Customer;
import drawn.storage.EventRepository;
import drawn.storage.PaymentRepository;
import drawn.storage.TicketRepository;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Calculates organizer earnings from unique, paid ticket purchases only. */
public final class OrganizerEarningsService {
    private final EventRepository events;
    private final TicketRepository tickets;
    private final PaymentRepository payments;
    private final CommissionPolicy policy;

    public OrganizerEarningsService(EventRepository events, TicketRepository tickets, PaymentRepository payments,
                                    CommissionPolicy policy) {
        this.events = events; this.tickets = tickets; this.payments = payments; this.policy = policy;
    }

    public OrganizerEarnings forOrganizer(Customer organizer) throws IOException {
        Map<String, Event> eventById = new HashMap<>();
        for (Event event : events.findByOrganizer(organizer.getId())) eventById.put(event.id(), event);
        Map<String, TicketRepository.Purchase> purchaseById = new HashMap<>();
        for (TicketRepository.Purchase purchase : tickets.allPurchaseRecords())
            if (eventById.containsKey(purchase.eventId())) purchaseById.putIfAbsent(purchase.id(), purchase);
        List<PaymentTransaction> history = payments.findAll();
        Set<String> refunded = new HashSet<>();
        for (PaymentTransaction tx : history) if (tx.purpose() == PaymentPurpose.TICKET_RESERVATION
                && tx.status() == PaymentStatus.REFUNDED) refunded.add(tx.referenceId());
        Set<String> counted = new HashSet<>();
        BigDecimal gross = BigDecimal.ZERO.setScale(2);
        int sold = 0, verified = 0;
        for (PaymentTransaction tx : history) {
            if (tx.purpose() != PaymentPurpose.TICKET_RESERVATION
                    || (tx.status() != PaymentStatus.PAID && tx.status() != PaymentStatus.VERIFIED)
                    || refunded.contains(tx.referenceId())) continue;
            TicketRepository.Purchase purchase = purchaseById.get(tx.referenceId());
            if (purchase == null || !purchase.purchaserId().equals(tx.customerId())) continue;
            Event event = eventById.get(purchase.eventId());
            if (event == null || event.purpose() != drawn.model.event.EventPurpose.TICKETED_EVENT
                    || event.visibility() != drawn.model.event.EventVisibility.PUBLIC
                    || event.approvalStatus() != drawn.model.event.EventApprovalStatus.PUBLISHED) continue;
            if (tx.amount().compareTo(purchase.amount()) != 0 || !counted.add(tx.referenceId())) continue;
            gross = gross.add(tx.amount().setScale(2, RoundingMode.HALF_UP));
            sold = Math.addExact(sold, purchase.quantity());
            verified++;
        }
        BigDecimal commission = gross.multiply(policy.percent()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        return new OrganizerEarnings(gross, commission, gross.subtract(commission), sold, verified);
    }

}
