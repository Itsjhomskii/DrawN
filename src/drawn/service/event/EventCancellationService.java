package drawn.service.event;

import drawn.model.event.BookingStatus;
import drawn.model.event.Event;
import drawn.model.event.EventApprovalStatus;
import drawn.model.event.VenueReservation;
import drawn.model.payment.PaymentPurpose;
import drawn.model.payment.PaymentStatus;
import drawn.model.payment.PaymentTransaction;
import drawn.model.ticket.TicketPurchaseStatus;
import drawn.model.user.Customer;
import drawn.storage.AccountStore;
import drawn.storage.EventRepository;
import drawn.storage.PaymentRepository;
import drawn.storage.ReservationRepository;
import drawn.storage.TicketRepository;
import drawn.storage.TransactionJournal;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/** Customer-owned event cancellation with recoverable ticket refunds. */
public final class EventCancellationService {
    private final EventRepository events;
    private final ReservationRepository reservations;
    private final TicketRepository tickets;
    private final PaymentRepository payments;
    private final AccountStore accounts;
    private final TransactionJournal journal;
    private final Clock clock;

    public EventCancellationService(EventRepository events, ReservationRepository reservations, TicketRepository tickets,
                                    PaymentRepository payments, AccountStore accounts, TransactionJournal journal) {
        this(events, reservations, tickets, payments, accounts, journal, Clock.systemDefaultZone());
    }
    public EventCancellationService(EventRepository events, ReservationRepository reservations, TicketRepository tickets,
                                    PaymentRepository payments, AccountStore accounts, TransactionJournal journal, Clock clock) {
        this.events=Objects.requireNonNull(events);this.reservations=Objects.requireNonNull(reservations);
        this.tickets=Objects.requireNonNull(tickets);this.payments=Objects.requireNonNull(payments);
        this.accounts=Objects.requireNonNull(accounts);this.journal=Objects.requireNonNull(journal);this.clock=Objects.requireNonNull(clock);
    }

    public Event cancel(Customer organizer, String eventId) throws IOException {
        Event event=owned(organizer,eventId);
        validateCancelable(event);
        String linkedReservationId=events.reservationId(eventId);
        List<VenueReservation> related=reservations.findAll().stream()
                .filter(r->r.eventId().equals(eventId)||r.id().equals(linkedReservationId)).toList();
        if(related.stream().anyMatch(r->r.status()!=BookingStatus.CANCELLED&&r.status()!=BookingStatus.DECLINED&&r.status()!=BookingStatus.EXPIRED))
            throw new IllegalArgumentException("Cancel the associated venue reservation before cancelling this event.");
        if(event.approvalStatus()!=EventApprovalStatus.CANCELLATION_PENDING){
            journal.begin("event-cancel:"+eventId,"EVENT_CANCELLATION",List.of(eventId,organizer.getId()));
            events.updateStatus(eventId,EventApprovalStatus.CANCELLATION_PENDING);
        }
        finish(eventId,"event-cancel:"+eventId);
        return owned(organizer,eventId);
    }

    public void recoverPending() throws IOException {
        java.util.Set<String> blocked=new java.util.HashSet<>();
        for(Event event:events.findAll()) if(event.approvalStatus()==EventApprovalStatus.CANCELLATION_PENDING) {
            try { finish(event.id(),"event-cancel:"+event.id()); }
            catch(ReconciliationRequiredException ex) { blocked.add(event.id()); }
        }
        for(TransactionJournal.Entry entry:journal.unfinished()) if(entry.kind().equals("EVENT_CANCELLATION")) {
            String eventId=entry.payload().get(0);if(blocked.contains(eventId))continue;
            try { finish(eventId,entry.id()); }
            catch(ReconciliationRequiredException ex) { blocked.add(eventId); }
        }
    }

    private void finish(String eventId,String operation) throws IOException {
        for(TicketRepository.Purchase purchase:tickets.allPurchaseRecords()) {
            if(!purchase.eventId().equals(eventId)||purchase.status()==TicketPurchaseStatus.CANCELLED
                    ||purchase.status()==TicketPurchaseStatus.FAILED)continue;
            if(purchase.status()==TicketPurchaseStatus.REFUNDED){tickets.reconcileSoldQuantity(eventId,purchase.ticketTypeId());continue;}
            PaymentTransaction paid=payments.findAll().stream().filter(p->p.referenceId().equals(purchase.id())
                    &&p.customerId().equals(purchase.purchaserId())&&p.purpose()==PaymentPurpose.TICKET_RESERVATION
                    &&(p.status()==PaymentStatus.PAID||p.status()==PaymentStatus.VERIFIED)
                    &&p.amount().compareTo(purchase.amount())==0).findFirst().orElse(null);
            boolean alreadyRefunded=payments.findAll().stream().anyMatch(p->p.referenceId().equals(purchase.id())
                    &&p.customerId().equals(purchase.purchaserId())&&p.purpose()==PaymentPurpose.TICKET_RESERVATION
                    &&p.status()==PaymentStatus.REFUNDED);
            if(alreadyRefunded){tickets.updatePurchaseStatus(purchase.id(),TicketPurchaseStatus.REFUNDED);
                tickets.reconcileSoldQuantity(eventId,purchase.ticketTypeId());continue;}
            if(paid==null)throw new ReconciliationRequiredException("Cancellation is pending: ticket purchase " + purchase.id()
                    + " has no matching paid payment record.");
            String refundId="ticket-refund-payment:"+purchase.id(); String refundRef="ticket-refund:"+purchase.id();
            String refundOperation="ticket-refund-op:"+purchase.id();
            journal.begin(refundOperation,"TICKET_REFUND",List.of(purchase.purchaserId(),purchase.id(),
                    purchase.amount().toPlainString(),refundId,eventId,purchase.ticketTypeId(),Integer.toString(purchase.quantity())));
            AccountStore.Account buyer=accounts.findAccount(purchase.purchaserId());
            if(!accounts.hasWalletTransaction(buyer.getId(),"REFUND:"+refundRef))accounts.refund(buyer,purchase.amount(),refundRef);
            if(!payments.hasTransaction(refundId))payments.append(new PaymentTransaction(refundId,buyer.getId(),purchase.id(),
                    PaymentPurpose.TICKET_RESERVATION,purchase.amount(),PaymentStatus.REFUNDED,LocalDateTime.now(clock)));
            tickets.updatePurchaseStatus(purchase.id(),TicketPurchaseStatus.REFUNDED);
            tickets.reconcileSoldQuantity(eventId,purchase.ticketTypeId());
            journal.state(refundOperation,"COMPLETED");
        }
        events.updateStatus(eventId,EventApprovalStatus.CANCELLED);
        journal.state(operation,"COMPLETED");
    }

    private void validateCancelable(Event e) {
        if(e.approvalStatus()==EventApprovalStatus.DRAFT)throw new IllegalArgumentException("Draft events should be deleted instead of cancelled.");
        if(e.approvalStatus()==EventApprovalStatus.CANCELLED)throw new IllegalArgumentException("Event is already cancelled.");
        if(!e.date().isAfter(LocalDate.now(clock)))throw new IllegalArgumentException("Past or currently scheduled events cannot be cancelled.");
        if(e.approvalStatus()!=EventApprovalStatus.CANCELLATION_PENDING&&e.approvalStatus()!=EventApprovalStatus.PENDING
                &&e.approvalStatus()!=EventApprovalStatus.REJECTED&&e.approvalStatus()!=EventApprovalStatus.APPROVED
                &&e.approvalStatus()!=EventApprovalStatus.PUBLISHED)throw new IllegalArgumentException("This event cannot be cancelled in its current status.");
    }
    private Event owned(Customer customer,String id)throws IOException{
        Objects.requireNonNull(customer,"Customer cannot be null");
        return events.findAll().stream().filter(e->e.id().equals(id)&&e.organizerId().equals(customer.getId())).findFirst()
                .orElseThrow(()->new IllegalArgumentException("Event not found for this account."));
    }
    private static final class ReconciliationRequiredException extends IOException {
        private ReconciliationRequiredException(String message) { super(message); }
    }
}
