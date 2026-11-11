package drawn.service.event;

import drawn.model.event.BookingStatus;
import drawn.model.event.Event;
import drawn.model.event.ReservationPricing;
import drawn.model.event.VenueReservation;
import drawn.model.payment.PaymentPurpose;
import drawn.model.payment.PaymentStatus;
import drawn.model.payment.PaymentTransaction;
import drawn.model.ticket.TicketType;
import drawn.model.user.Customer;
import drawn.storage.AccountStore;
import drawn.storage.EventRepository;
import drawn.storage.PaymentRepository;
import drawn.storage.ReservationRepository;
import drawn.storage.TicketRepository;
import drawn.storage.TransactionJournal;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Optional organizer-owned publishing and ticket sales operations. */
public final class EventPublishingService {
    private final EventRepository events;
    private final ReservationRepository reservations;
    private final TicketRepository tickets;
    private final PaymentRepository payments;
    private final AccountStore accounts;
    private final TransactionJournal journal;
    public EventPublishingService(EventRepository events, ReservationRepository reservations, TicketRepository tickets,
                                  PaymentRepository payments, AccountStore accounts, TransactionJournal journal) {
        this.events=Objects.requireNonNull(events);this.reservations=Objects.requireNonNull(reservations);
        this.tickets=Objects.requireNonNull(tickets);this.payments=Objects.requireNonNull(payments);this.accounts=Objects.requireNonNull(accounts);
        this.journal=Objects.requireNonNull(journal, "Transaction journal is required for ticket payments.");
    }

    public List<Event> publishedEvents()throws IOException{
        Set<String> published=new HashSet<>(tickets.publishedEventIds());
        return events.findAll().stream().filter(e->published.contains(e.id())
                &&e.approvalStatus()==drawn.model.event.EventApprovalStatus.PUBLISHED
                &&e.visibility()==drawn.model.event.EventVisibility.PUBLIC).toList();
    }
    public List<TicketType> ticketTypes(String eventId)throws IOException{return tickets.types(eventId);}
    public Event event(String eventId)throws IOException{return events.findAll().stream().filter(e->e.id().equals(eventId)).findFirst().orElseThrow(()->new IllegalArgumentException("Event not found."));}
    public List<VenueReservation> reservationsFor(Customer customer)throws IOException{
        return reservations.findAll().stream().filter(r->r.organizerId().equals(customer.getId())).toList();
    }

    public void configureTickets(Customer organizer,String eventId,List<TicketType> types)throws IOException{
        Event event=ownedEvent(organizer,eventId);
        if(event.purpose()!=drawn.model.event.EventPurpose.TICKETED_EVENT)
            throw new IllegalArgumentException("Social events cannot have ticket categories.");
        if(types==null||types.isEmpty())throw new IllegalArgumentException("Add at least one ticket category.");
        Set<String> names=new HashSet<>();int total=0;
        for(TicketType type:types){
            if(!type.eventId().equals(eventId))throw new IllegalArgumentException("Ticket category belongs to another event.");
            if(type.price().signum()<=0)throw new IllegalArgumentException("Paid ticket prices must be greater than zero.");
            if(!names.add(type.name().trim().toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("Ticket category names must be unique.");
            if(type.salesStart()!=null&&type.salesDeadline()!=null&&!type.salesDeadline().isAfter(type.salesStart()))throw new IllegalArgumentException("Ticket sales end must be after the start.");
            if(type.salesStart()==null||type.salesDeadline()==null)throw new IllegalArgumentException("New ticket categories need a sales opening and closing time.");
            if(type.salesDeadline().isAfter(event.date().atTime(event.startTime())))throw new IllegalArgumentException("Ticket sales deadline must be before event start.");
            if(!type.salesDeadline().isAfter(LocalDateTime.now()))throw new IllegalArgumentException("Ticket sales end must be in the future.");
            total=Math.addExact(total,type.quantity());
        }
        if(total>event.venue().capacity())throw new IllegalArgumentException("Total ticket quantity exceeds venue capacity.");
        List<TicketType> prior=tickets.types(eventId);
        if(prior.stream().anyMatch(t->t.sold()>0))throw new IllegalArgumentException("Ticket categories cannot change after sales begin.");
        tickets.saveTypes(eventId,types);
    }

    public void publish(Customer organizer,String eventId)throws IOException{
        Event event=ownedEvent(organizer,eventId);
        if(event.approvalStatus()!=drawn.model.event.EventApprovalStatus.APPROVED)
            throw new IllegalArgumentException("Only approved events can be published.");
        if(tickets.isPublished(eventId))throw new IllegalArgumentException("Event is already published.");
        if(tickets.types(eventId).isEmpty())throw new IllegalArgumentException("Configure ticket categories before publishing.");
        tickets.publish(eventId,organizer.getId());
        events.updateStatus(eventId,drawn.model.event.EventApprovalStatus.PUBLISHED);
    }

    public void buyTicket(Customer purchaser,AccountStore.Account account,String eventId,String ticketTypeId,int quantity)throws IOException{
        if(quantity<=0)throw new IllegalArgumentException("Ticket quantity must be positive.");
        if(!account.getId().equals(purchaser.getId()))throw new IllegalArgumentException("Account does not match purchaser.");
        Event event=events.findAll().stream().filter(e->e.id().equals(eventId)).findFirst().orElseThrow(()->new IllegalArgumentException("Event not found."));
        if(!tickets.isPublished(eventId))throw new IllegalArgumentException("Event is not published.");
        if(event.approvalStatus()!=drawn.model.event.EventApprovalStatus.PUBLISHED
                ||event.visibility()!=drawn.model.event.EventVisibility.PUBLIC
                ||event.purpose()!=drawn.model.event.EventPurpose.TICKETED_EVENT)
            throw new IllegalArgumentException("Tickets are not available for this event.");
        if(event.organizerId().equals(purchaser.getId()))throw new IllegalArgumentException("Organizers cannot purchase tickets to their own event.");
        TicketType type=tickets.types(eventId).stream().filter(t->t.id().equals(ticketTypeId)).findFirst()
                .orElseThrow(()->new IllegalArgumentException("Ticket category not found."));
        LocalDateTime now=LocalDateTime.now();
        if(type.salesStart()!=null&&now.isBefore(type.salesStart()))throw new IllegalArgumentException("Ticket sales have not opened.");
        if(type.salesDeadline()!=null&&!now.isBefore(type.salesDeadline()))throw new IllegalArgumentException("Ticket sales deadline has passed.");
        if(quantity>type.remaining())throw new IllegalArgumentException("Requested quantity exceeds ticket availability.");
        int soldAcrossEvent=tickets.types(eventId).stream().mapToInt(TicketType::sold).sum();
        if(soldAcrossEvent+quantity>event.venue().capacity())throw new IllegalArgumentException("Ticket sales exceed venue capacity.");
        BigDecimal amount=type.price().multiply(BigDecimal.valueOf(quantity));
        String purchaseId=UUID.randomUUID().toString();
        String transactionId="ticket-payment:"+purchaseId;
        String operation="ticket:"+purchaseId;
        journal.begin(operation,"TICKET_PURCHASE",List.of(purchaser.getId(),eventId,type.id(),
                Integer.toString(quantity),amount.toPlainString(),transactionId));
        try { completeTicketPurchase(account,purchaseId,purchaser.getId(),eventId,type.id(),quantity,amount,transactionId,operation); }
        catch(IOException|RuntimeException ex) {
            if(!accounts.hasWalletTransaction(account.getId(),"PAYMENT:ticket:"+purchaseId)
                    &&!tickets.hasPurchase(purchaseId)&&!payments.hasTransaction(transactionId))journal.state(operation,"FAILED");
            throw ex;
        }
    }

    public List<String> purchaseHistory(Customer customer)throws IOException{return tickets.purchaseHistory(customer.getId());}
    public int activeTicketCount(Customer customer)throws IOException{return tickets.purchasedCount(customer.getId());}

    private void completeTicketPurchase(AccountStore.Account account,String purchaseId,String customerId,String eventId,
                                       String typeId,int quantity,BigDecimal amount,String transactionId,String operation)
            throws IOException {
        String walletRef="ticket:"+purchaseId;
        if(!accounts.hasWalletTransaction(account.getId(),"PAYMENT:"+walletRef))accounts.charge(account,amount,walletRef);
        tickets.purchase(purchaseId,eventId,typeId,customerId,quantity,amount);
        tickets.reconcileSoldQuantity(eventId,typeId);
        if(!payments.hasTransaction(customerId,purchaseId,PaymentPurpose.TICKET_RESERVATION,PaymentStatus.PAID)
                &&!payments.hasTransaction(transactionId))payments.append(new PaymentTransaction(transactionId,customerId,purchaseId,
                PaymentPurpose.TICKET_RESERVATION,amount,PaymentStatus.PAID,LocalDateTime.now()));
        journal.state(operation,"COMPLETED");
    }

    public void recoverPending()throws IOException{
        for(TransactionJournal.Entry entry:journal.unfinished())if(entry.kind().equals("TICKET_PURCHASE")){
            List<String> p=entry.payload();AccountStore.Account account=accounts.findAccount(p.get(0));
            completeTicketPurchase(account,entry.id().substring("ticket:".length()),p.get(0),p.get(1),p.get(2),
                    Integer.parseInt(p.get(3)),new BigDecimal(p.get(4)),p.get(5),entry.id());
        }
    }

    private Event ownedEvent(Customer organizer,String id)throws IOException{
        Event event=events.findAll().stream().filter(e->e.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Event not found."));
        if(!event.organizerId().equals(organizer.getId()))throw new IllegalArgumentException("Event does not belong to this organizer.");
        return event;
    }
}
