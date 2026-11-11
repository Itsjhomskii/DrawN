package drawn.service.event;

import drawn.model.event.BookingStatus;
import drawn.model.event.Event;
import drawn.model.event.EventDraft;
import drawn.model.event.EventPurpose;
import drawn.model.event.EventType;
import drawn.model.event.EventVisibility;
import drawn.model.event.ReservationPricing;
import drawn.model.event.TicketCategory;
import drawn.model.event.Venue;
import drawn.model.event.VenueCatalog;
import drawn.model.event.VenueType;
import drawn.model.event.VenueReservation;
import drawn.model.payment.PaymentPurpose;
import drawn.model.payment.PaymentStatus;
import drawn.model.ticket.TicketType;
import drawn.model.user.ContactMethod;
import drawn.model.user.Customer;
import drawn.storage.AccountStore;
import drawn.storage.EventRepository;
import drawn.storage.PaymentRepository;
import drawn.storage.ReservationRepository;
import drawn.storage.TicketRepository;
import drawn.storage.TransactionJournal;
import drawn.service.payment.PaymentService;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;

/** Integration checks for the reservation, wallet payment, publishing, and ticket workflows. */
public final class Phase34WorkflowTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);
    private static final LocalDate EVENT_DATE = TODAY.plusDays(30);
    private Phase34WorkflowTest() { }

    public static void main(String[] args) throws Exception {
        Path dir=Files.createTempDirectory("drawn-phase34-");
        try {
            Clock clock=Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.UTC);
            EventRepository eventRepo=new EventRepository(dir); ReservationRepository reservationRepo=new ReservationRepository(dir);
            AccountStore accounts=new AccountStore(dir); PaymentRepository paymentRepo=new PaymentRepository(dir);
            TicketRepository ticketRepo=new TicketRepository(dir);
            TransactionJournal journal=new TransactionJournal(dir);
            EventService eventService=new EventService(eventRepo,reservationRepo,ticketRepo,paymentRepo,clock);
            PaymentService paymentService=new PaymentService(accounts,reservationRepo,paymentRepo,journal,eventRepo);
            ReservationService reservationService=new ReservationService(eventRepo,reservationRepo,clock,paymentService);
            EventPublishingService publishing=new EventPublishingService(eventRepo,reservationRepo,ticketRepo,paymentRepo,accounts,journal);
            Customer owner=customer("owner@example.test",TODAY.minusYears(30));
            Customer buyer=customer("buyer@example.test",TODAY.minusYears(25));
            AccountStore.Account ownerAccount=account(owner,"100000.00");
            AccountStore.Account buyerAccount=account(buyer,"100000.00");
            accounts.register(ownerAccount,new BigDecimal("100000.00"));
            accounts.register(buyerAccount,new BigDecimal("100000.00"));

            Event event=eventService.saveDraft(owner,draft("Concert",EVENT_DATE,LocalTime.of(9,0),LocalTime.of(11,0),25,
                    VenueCatalog.all().get(5),"Description"));
            VenueReservation pending=reservationService.request(owner,event,ReservationPricing.HOURLY);
            assert pending.status()==BookingStatus.PENDING;
            assert pending.cost().compareTo(VenueCatalog.all().get(5).hourlyRate().multiply(new BigDecimal("2")))==0;
            assert ReservationService.price(event,ReservationPricing.HALF_DAY_MORNING)
                    .compareTo(VenueCatalog.all().get(5).morningRate())==0;
            reservationService.approve(pending.id());
            VenueReservation confirmed=paymentService.payVenue(owner,ownerAccount,pending.id());
            assert confirmed.status()==BookingStatus.CONFIRMED&&confirmed.paymentStatus()==PaymentStatus.PAID;
            assert eventService.submitDraft(owner,event.id()).approvalStatus()==drawn.model.event.EventApprovalStatus.PENDING;
            Event birthday=new Event("birthday",owner.getId(),"Birthday","Private gathering",EventType.BIRTHDAY,
                    drawn.model.event.EventPurpose.SOCIAL_GATHERING,drawn.model.event.EventVisibility.PRIVATE,EVENT_DATE,
                    LocalTime.of(10,10),LocalTime.of(10,11),1,VenueCatalog.all().get(4),
                    drawn.model.event.EventApprovalStatus.PENDING,LocalDateTime.now(),List.of());
            assert ReservationService.price(birthday,ReservationPricing.HALF_DAY_MORNING)
                    .compareTo(VenueCatalog.all().get(4).morningRate())==0;
            expectIllegal(()->ReservationService.validatePricing(LocalTime.of(13,0),LocalTime.of(15,0),ReservationPricing.HALF_DAY_MORNING));
            expectIllegal(()->eventService.saveDraft(owner,draft("Long description",EVENT_DATE,LocalTime.NOON,
                    LocalTime.of(13,0),10,VenueCatalog.all().get(0),"x".repeat(501))));
            Event boundaryDescription=eventService.saveDraft(owner,draft("Description boundary",EVENT_DATE,
                    LocalTime.NOON,LocalTime.of(13,0),10,VenueCatalog.all().get(0),"x".repeat(500)));
            assert boundaryDescription.description().length()==500;
            expectIllegal(()->eventService.saveDraft(owner,draft("Over capacity",EVENT_DATE,LocalTime.NOON,
                    LocalTime.of(13,0),21,new Venue("Small",drawn.model.event.VenueType.BOTH,20,
                    new BigDecimal("10.00"),new BigDecimal("20.00"),new BigDecimal("20.00")),"ok")));

            BigDecimal balanceAfterPayment=accounts.loadAccounts().stream().filter(a->a.getId().equals(owner.getId())).findFirst().orElseThrow().getWalletBalance();
            assert balanceAfterPayment.compareTo(new BigDecimal("100000.00").subtract(pending.cost()))==0 : balanceAfterPayment;
            assert ownerAccount.getWalletBalance().compareTo(new BigDecimal("100000.00").subtract(pending.cost()))==0;
            assert paymentService.history(owner).size()==1;
            expectIllegal(()->paymentService.payVenue(owner,ownerAccount,pending.id()));
            expectIllegal(()->publishing.publish(owner,event.id()));

            eventService.review(event.id(),true);
            expectIllegal(()->publishing.publish(owner,event.id()));

            TicketType general=new TicketType("type-1",event.id(),"General","",new BigDecimal("25.00"),40,0,
                    TODAY.atStartOfDay(),EVENT_DATE.atTime(8,0));
            publishing.configureTickets(owner,event.id(),List.of(general));
            publishing.publish(owner,event.id());
            assert publishing.publishedEvents().size()==1;
            expectIllegal(()->publishing.publish(owner,event.id()));
            expectIllegal(()->publishing.configureTickets(buyer,event.id(),List.of(general)));
            publishing.buyTicket(buyer,buyerAccount,event.id(),general.id(),2);
            expectIllegal(()->publishing.buyTicket(owner,ownerAccount,event.id(),general.id(),1));
            assert publishing.ticketTypes(event.id()).get(0).sold()==2;
            assert publishing.purchaseHistory(buyer).size()==1;
            assert paymentRepo.findByCustomer(buyer.getId()).get(0).purpose()==PaymentPurpose.TICKET_RESERVATION;
            assert buyerAccount.getWalletBalance().compareTo(new BigDecimal("99950.00"))==0;
            expectIllegal(()->publishing.buyTicket(buyer,buyerAccount,event.id(),general.id(),39));

            Event conflicting=eventService.saveDraft(owner,draft("Conflict",EVENT_DATE,LocalTime.of(10,0),
                    LocalTime.of(12,0),20,event.venue(),"Another event"));
            VenueReservation conflict=reservationService.request(owner,conflicting,ReservationPricing.HOURLY);
            expectIllegal(()->reservationService.approve(conflict.id()));

            Event rejected=eventService.saveDraft(owner,draft("Rejected event",EVENT_DATE,LocalTime.of(12,0),
                    LocalTime.of(13,0),10,VenueCatalog.all().get(5),"Rejected details"));
            VenueReservation rejectedReservation=reservationService.request(owner,rejected,ReservationPricing.HOURLY);
            reservationService.approve(rejectedReservation.id());
            paymentService.payVenue(owner,ownerAccount,rejectedReservation.id());
            eventService.submitDraft(owner,rejected.id());
            eventService.review(rejected.id(),false);
            expectIllegal(()->publishing.publish(owner,rejected.id()));

            VenueReservation declined=reservationService.decline(pending.id(),"Schedule changed");
            assert declined.status()==BookingStatus.DECLINED&&declined.paymentStatus()==PaymentStatus.REFUNDED;
            BigDecimal balanceAfterDecline=accounts.loadAccounts().stream().filter(a->a.getId().equals(owner.getId())).findFirst().orElseThrow().getWalletBalance();
            assert balanceAfterDecline.compareTo(new BigDecimal("100000.00").subtract(rejectedReservation.cost()))==0 : balanceAfterDecline;
            assert paymentService.history(owner).stream().anyMatch(tx->tx.status()==PaymentStatus.REFUNDED);
            assert publishing.publishedEvents().size()==1;
            expectIllegal(()->reservationService.decline(conflict.id()," "));

            EventRepository reloadEvents=new EventRepository(dir);
            assert reloadEvents.findByOrganizer(owner.getId()).size()==4;
            assert new ReservationRepository(dir).findById(pending.id()).paymentStatus()==PaymentStatus.REFUNDED;
            assert new TicketRepository(dir).types(event.id()).get(0).sold()==2;

            VenueReservation independent=reservationService.request(owner,VenueCatalog.all().get(5),EVENT_DATE,
                    LocalTime.of(14,0),LocalTime.of(16,0),ReservationPricing.HOURLY);
            assert independent.eventId().isBlank();
            reservationService.approve(independent.id());
            VenueReservation independentPaid=paymentService.payVenue(owner,ownerAccount,independent.id());
            assert independentPaid.paymentStatus()==PaymentStatus.PAID;
            reservationService.attachEvent(owner,independent.id(),boundaryDescription.id());
            assert new ReservationRepository(dir).findById(independent.id()).eventId().equals(boundaryDescription.id());
            expectIllegal(()->reservationService.attachEvent(buyer,independent.id(),boundaryDescription.id()));
        } finally {
            try(var paths=Files.walk(dir)){paths.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ex){throw new RuntimeException(ex);}});}
        }
        System.out.println("Phase34WorkflowTest passed.");
    }
    private static Customer customer(String id,LocalDate dob){return new Customer(id,id,ContactMethod.EMAIL,id,dob);}
    private static AccountStore.Account account(Customer c,String balance){return new AccountStore.Account(c.getId(),"Test","User",TODAY.minusYears(25).toString(),c.getId(),"",ContactMethod.EMAIL,"pw",new BigDecimal(balance));}
    private static EventDraft draft(String name,LocalDate date,LocalTime start,LocalTime end,int pax,Venue venue,String description){
        EventPurpose purpose=venue.type()==VenueType.COMMERCIAL?EventPurpose.TICKETED_EVENT:EventPurpose.SOCIAL_GATHERING;
        return new EventDraft(name,description,EventType.CONCERT,purpose,EventVisibility.PUBLIC,
                date,start,end,pax,venue,List.<TicketCategory>of());
    }
    private static void expectIllegal(Throwing action)throws Exception{try{action.run();throw new AssertionError("Expected IllegalArgumentException");}catch(IllegalArgumentException expected){}}
    @FunctionalInterface private interface Throwing{void run()throws Exception;}
}
