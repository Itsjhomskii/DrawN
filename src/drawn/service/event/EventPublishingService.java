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
    public EventPublishingService(EventRepository events, ReservationRepository reservations, TicketRepository tickets,
                                  PaymentRepository payments, AccountStore accounts) {
        this.events=Objects.requireNonNull(events);this.reservations=Objects.requireNonNull(reservations);
        this.tickets=Objects.requireNonNull(tickets);this.payments=Objects.requireNonNull(payments);this.accounts=Objects.requireNonNull(accounts);
    }

    public List<Event> publishedEvents()throws IOException{
        Set<String> published=new HashSet<>(tickets.publishedEventIds());
        Set<String> confirmed=new HashSet<>();
        for(VenueReservation r:reservations.findAll())if(r.status()==BookingStatus.CONFIRMED&&r.paymentStatus()==PaymentStatus.PAID)confirmed.add(r.eventId());
        return events.findAll().stream().filter(e->published.contains(e.id())&&confirmed.contains(e.id())).toList();
    }
    public List<TicketType> ticketTypes(String eventId)throws IOException{return tickets.types(eventId);}
    public List<VenueReservation> reservationsFor(Customer customer)throws IOException{
        return reservations.findAll().stream().filter(r->r.organizerId().equals(customer.getId())).toList();
    }

    public void configureTickets(Customer organizer,String eventId,List<TicketType> types)throws IOException{
        Event event=ownedEvent(organizer,eventId);requireEligible(event);
        if(types==null||types.isEmpty())throw new IllegalArgumentException("Add at least one ticket category.");
        Set<String> names=new HashSet<>();int total=0;
        for(TicketType type:types){
            if(!type.eventId().equals(eventId))throw new IllegalArgumentException("Ticket category belongs to another event.");
            if(!names.add(type.name().trim().toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("Ticket category names must be unique.");
            if(!type.salesDeadline().isAfter(LocalDateTime.now()))throw new IllegalArgumentException("Ticket sales deadline must be in the future.");
            if(type.salesDeadline().isAfter(event.date().atTime(event.startTime())))throw new IllegalArgumentException("Ticket sales deadline must be before event start.");
            total=Math.addExact(total,type.quantity());
        }
        if(total>event.venue().capacity())throw new IllegalArgumentException("Total ticket quantity exceeds venue capacity.");
        List<TicketType> prior=tickets.types(eventId);
        if(prior.stream().anyMatch(t->t.sold()>0))throw new IllegalArgumentException("Ticket categories cannot change after sales begin.");
        tickets.saveTypes(eventId,types);
    }

    public void publish(Customer organizer,String eventId)throws IOException{
        Event event=ownedEvent(organizer,eventId);requireEligible(event);
        if(tickets.types(eventId).isEmpty())throw new IllegalArgumentException("Configure ticket categories before publishing.");
        tickets.publish(eventId,organizer.getId());
    }

    public void buyTicket(Customer purchaser,AccountStore.Account account,String eventId,String ticketTypeId,int quantity)throws IOException{
        if(quantity<=0)throw new IllegalArgumentException("Ticket quantity must be positive.");
        if(!account.getId().equals(purchaser.getId()))throw new IllegalArgumentException("Account does not match purchaser.");
        Event event=events.findAll().stream().filter(e->e.id().equals(eventId)).findFirst().orElseThrow(()->new IllegalArgumentException("Event not found."));
        if(!tickets.isPublished(eventId))throw new IllegalArgumentException("Event is not published.");
        requireEligible(event);
        TicketType type=tickets.types(eventId).stream().filter(t->t.id().equals(ticketTypeId)).findFirst()
                .orElseThrow(()->new IllegalArgumentException("Ticket category not found."));
        if(!LocalDateTime.now().isBefore(type.salesDeadline()))throw new IllegalArgumentException("Ticket sales deadline has passed.");
        if(quantity>type.remaining())throw new IllegalArgumentException("Requested quantity exceeds ticket availability.");
        int soldAcrossEvent=tickets.types(eventId).stream().mapToInt(TicketType::sold).sum();
        if(soldAcrossEvent+quantity>event.venue().capacity())throw new IllegalArgumentException("Ticket sales exceed venue capacity.");
        BigDecimal amount=type.price().multiply(BigDecimal.valueOf(quantity));
        String purchaseId=UUID.randomUUID().toString();
        accounts.charge(account,amount,"ticket:"+purchaseId);
        try{
            tickets.updateType(type.sold(quantity));
            tickets.purchase(purchaseId,eventId,type.id(),purchaser.getId(),quantity,amount);
            payments.append(new PaymentTransaction(UUID.randomUUID().toString(),purchaser.getId(),purchaseId,
                    PaymentPurpose.TICKET_RESERVATION,amount,PaymentStatus.PAID,LocalDateTime.now()));
        }catch(IOException|RuntimeException ex){
            accounts.refund(account,amount,"ticket-rollback:"+purchaseId);throw ex;
        }
    }

    public List<String> purchaseHistory(Customer customer)throws IOException{return tickets.purchaseHistory(customer.getId());}

    private Event ownedEvent(Customer organizer,String id)throws IOException{
        Event event=events.findAll().stream().filter(e->e.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Event not found."));
        if(!event.organizerId().equals(organizer.getId()))throw new IllegalArgumentException("Event does not belong to this organizer.");
        return event;
    }
    private void requireEligible(Event event)throws IOException{
        VenueReservation reservation=reservations.findAll().stream().filter(r->r.eventId().equals(event.id())).findFirst()
                .orElseThrow(()->new IllegalArgumentException("Event has no venue reservation."));
        if(reservation.status()!=BookingStatus.CONFIRMED||reservation.paymentStatus()!=PaymentStatus.PAID)
            throw new IllegalArgumentException("Event must be approved and fully paid before ticket management.");
        if(!event.venue().supports(drawn.model.event.EventPurpose.TICKETED_EVENT))
            throw new IllegalArgumentException("Venue does not support ticket sales.");
    }
}
