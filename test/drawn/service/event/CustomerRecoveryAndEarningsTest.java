package drawn.service.event;

import drawn.model.event.BookingStatus;
import drawn.model.event.Event;
import drawn.model.event.EventApprovalStatus;
import drawn.model.event.EventDraft;
import drawn.model.event.EventPurpose;
import drawn.model.event.EventType;
import drawn.model.event.EventVisibility;
import drawn.model.event.ReservationPricing;
import drawn.model.event.VenueCatalog;
import drawn.model.event.VenueReservation;
import drawn.model.payment.PaymentPurpose;
import drawn.model.payment.PaymentStatus;
import drawn.model.payment.PaymentTransaction;
import drawn.model.ticket.TicketType;
import drawn.model.user.ContactMethod;
import drawn.model.user.Customer;
import drawn.service.payment.CommissionPolicy;
import drawn.service.payment.OrganizerEarningsService;
import drawn.service.payment.PaymentService;
import drawn.storage.AccountStore;
import drawn.storage.EventRepository;
import drawn.storage.PaymentRepository;
import drawn.storage.ReservationRepository;
import drawn.storage.TicketRepository;
import drawn.storage.TransactionJournal;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;

/** Isolated checks for customer lifecycle, CSV compatibility, earnings, and journal recovery. */
public final class CustomerRecoveryAndEarningsTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);
    private static final LocalDate EVENT_DAY = TODAY.plusDays(30);
    private CustomerRecoveryAndEarningsTest() { }

    public static void main(String[] args) throws Exception {
        if(args.length>0&&args[0].equals("wallet-ledger-crash")){crashAfterWalletLedger(args);return;}
        Path dir = Files.createTempDirectory("drawn-customer-recovery-");
        try {
            verifyChildProcessWalletRecovery();
            Clock clock = Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.UTC);
            EventRepository events = new EventRepository(dir); ReservationRepository reservations = new ReservationRepository(dir);
            AccountStore accounts = new AccountStore(dir); PaymentRepository payments = new PaymentRepository(dir);
            TicketRepository tickets = new TicketRepository(dir); TransactionJournal journal = new TransactionJournal(dir);
            Customer owner = customer("owner@example.test"), buyer = customer("buyer@example.test");
            Customer secondBuyer=customer("buyer-2@example.test");
            AccountStore.Account ownerWallet = account(owner, "10000.00"), buyerWallet = account(buyer, "10000.00"),
                    secondBuyerWallet=account(secondBuyer,"10000.00");
            accounts.register(ownerWallet, ownerWallet.getWalletBalance()); accounts.register(buyerWallet, buyerWallet.getWalletBalance());
            accounts.register(secondBuyerWallet,secondBuyerWallet.getWalletBalance());

            EventService eventService = new EventService(events, reservations, tickets, payments, clock);
            VenueReservation reserved = reservation(owner, "reservation-1", "", BookingStatus.CONFIRMED, PaymentStatus.PAID);
            reservations.save(reserved);
            payments.append(new PaymentTransaction("reservation-1-payment", owner.getId(), reserved.id(),
                    PaymentPurpose.VENUE_BOOKING, reserved.cost(), PaymentStatus.PAID, LocalDateTime.now()));
            Event draft = eventService.saveDraftForReservation(owner, draft("Draft", EventPurpose.TICKETED_EVENT), reserved.id());
            reservations.attachEvent(reserved.id(), draft.id(), owner.getId());
            Event edited = eventService.editDraft(owner, draft.id(), draft("Edited", EventPurpose.TICKETED_EVENT));
            assert edited.id().equals(draft.id()) && edited.name().equals("Edited");
            assert eventService.submitDraft(owner, edited.id()).approvalStatus() == EventApprovalStatus.PENDING;
            expectIllegal(() -> eventService.submitDraft(buyer, edited.id()));

            Event orphan = eventService.saveDraft(owner, draft("No reservation", EventPurpose.SOCIAL_GATHERING));
            expectIllegal(() -> eventService.submitDraft(owner, orphan.id()));
            eventService.deleteDraft(owner, orphan.id());
            assert eventService.eventsFor(owner).stream().noneMatch(e -> e.id().equals(orphan.id()));
            Event unrecordedPayment=eventService.saveDraft(owner,draft("Unrecorded venue payment",EventPurpose.SOCIAL_GATHERING));
            VenueReservation unrecordedReservation=new VenueReservation("unrecorded-reservation",unrecordedPayment.id(),
                    owner.getId(),unrecordedPayment.venue().name(),unrecordedPayment.date(),unrecordedPayment.startTime(),
                    unrecordedPayment.endTime(),ReservationPricing.HOURLY,new BigDecimal("50.00"),BookingStatus.CONFIRMED,
                    PaymentStatus.PAID,"");
            reservations.save(unrecordedReservation);
            expectIllegal(()->eventService.submitDraft(owner,unrecordedPayment.id()));
            expectIllegal(() -> eventService.deleteDraft(owner, draft.id()));
            expectIllegal(() -> eventService.deleteDraft(buyer,draft.id()));

            Event resolvedDraft=eventService.saveDraft(owner,draft("Resolved draft",EventPurpose.SOCIAL_GATHERING));
            VenueReservation resolvedReservation=reservation(owner,"resolved-reservation",resolvedDraft.id(),BookingStatus.CANCELLED,PaymentStatus.REFUNDED);
            reservations.save(resolvedReservation);events.linkReservation(resolvedDraft.id(),resolvedReservation.id());
            payments.append(new PaymentTransaction("resolved-payment",owner.getId(),resolvedReservation.id(),PaymentPurpose.VENUE_BOOKING,
                    resolvedReservation.cost(),PaymentStatus.PAID,LocalDateTime.now()));
            payments.append(new PaymentTransaction("resolved-refund",owner.getId(),resolvedReservation.id(),PaymentPurpose.VENUE_BOOKING,
                    resolvedReservation.cost(),PaymentStatus.REFUNDED,LocalDateTime.now()));
            eventService.deleteDraft(owner,resolvedDraft.id());
            assert reservations.findById(resolvedReservation.id()).eventId().equals(resolvedDraft.id());
            Event unresolvedDraft=eventService.saveDraft(owner,draft("Unresolved draft",EventPurpose.SOCIAL_GATHERING));
            VenueReservation unresolvedReservation=reservation(owner,"unresolved-reservation",unresolvedDraft.id(),BookingStatus.CANCELLED,PaymentStatus.PAID);
            reservations.save(unresolvedReservation);
            payments.append(new PaymentTransaction("unresolved-payment",owner.getId(),unresolvedReservation.id(),PaymentPurpose.VENUE_BOOKING,
                    unresolvedReservation.cost(),PaymentStatus.PAID,LocalDateTime.now()));
            expectIllegal(()->eventService.deleteDraft(owner,unresolvedDraft.id()));

            events.updateStatus(edited.id(), EventApprovalStatus.REJECTED);
            Event resubmitted = eventService.submitDraft(owner, edited.id());
            assert resubmitted.id().equals(edited.id()) && resubmitted.approvalStatus() == EventApprovalStatus.PENDING;

            PaymentService paymentService = new PaymentService(accounts, reservations, payments, journal, events);
            assert java.util.Arrays.stream(PaymentService.class.getConstructors()).noneMatch(c->c.getParameterCount()==3);
            assert java.util.Arrays.stream(EventPublishingService.class.getConstructors()).noneMatch(c->c.getParameterCount()==5);
            EventCancellationService cancellationService = new EventCancellationService(events,reservations,tickets,payments,accounts,journal,clock);
            VenueReservation cancelTarget = reservation(owner, "cancel-paid", "", BookingStatus.CONFIRMED, PaymentStatus.PAID);
            reservations.save(cancelTarget);
            accounts.charge(ownerWallet, cancelTarget.cost(), "venue:" + cancelTarget.id());
            payments.append(new PaymentTransaction("original-venue-payment", owner.getId(), cancelTarget.id(),
                    PaymentPurpose.VENUE_BOOKING, cancelTarget.cost(), PaymentStatus.PAID, LocalDateTime.now()));
            expectIllegal(() -> paymentService.cancelReservation(buyer, buyerWallet, cancelTarget.id()));
            VenueReservation cancelled = paymentService.cancelReservation(owner, ownerWallet, cancelTarget.id());
            assert cancelled.status() == BookingStatus.CANCELLED && cancelled.paymentStatus() == PaymentStatus.REFUNDED;
            assert ownerWallet.getWalletBalance().compareTo(new BigDecimal("10000.00")) == 0;
            expectIllegal(() -> paymentService.cancelReservation(owner, ownerWallet, cancelTarget.id()));
            assert payments.findByCustomer(owner.getId()).stream().filter(tx -> tx.status() == PaymentStatus.REFUNDED
                    && tx.referenceId().equals(cancelTarget.id())).count() == 1;
            assert payments.findByCustomer(owner.getId()).stream().anyMatch(tx -> tx.id().equals("original-venue-payment"));
            assert payments.findByCustomer(owner.getId()).stream().anyMatch(tx -> tx.status() == PaymentStatus.REFUNDED
                    && tx.referenceId().equals(cancelTarget.id()));

            Event cancelEvent = eventService.saveDraft(owner, draft("Cancel event", EventPurpose.SOCIAL_GATHERING));
            events.updateStatus(cancelEvent.id(), EventApprovalStatus.APPROVED);
            assert cancellationService.cancel(owner, cancelEvent.id()).approvalStatus() == EventApprovalStatus.CANCELLED;
            expectIllegal(() -> cancellationService.cancel(owner, cancelEvent.id()));
            Event completed = new Event("completed", owner.getId(), "Past event", "Description", EventType.CONCERT,
                    EventPurpose.SOCIAL_GATHERING, EventVisibility.PRIVATE, TODAY.minusDays(1), LocalTime.of(9, 0),
                    LocalTime.of(11, 0), 10, VenueCatalog.all().get(0), EventApprovalStatus.APPROVED,
                    LocalDateTime.now(), List.of());
            events.save(completed);
            expectIllegal(() -> cancellationService.cancel(owner, completed.id()));

            // Simulate a process stop after a wallet debit but before venue records were committed.
            VenueReservation recovery = reservation(owner, "recover-venue", "", BookingStatus.APPROVED, PaymentStatus.UNPAID);
            reservations.save(recovery);
            journal.begin("venuepay:" + recovery.id(), "VENUE_PAYMENT",
                    List.of(owner.getId(), recovery.id(), recovery.cost().toPlainString(), "venue-payment:" + recovery.id()));
            accounts.charge(ownerWallet, recovery.cost(), "venue:" + recovery.id());
            paymentService.recoverPending();
            assert reservations.findById(recovery.id()).paymentStatus() == PaymentStatus.PAID;
            assert payments.findAll().stream().anyMatch(tx -> tx.id().equals("venue-payment:" + recovery.id()));

            VenueReservation afterLedger = reservation(owner, "recover-after-ledger", "", BookingStatus.APPROVED, PaymentStatus.UNPAID);
            reservations.save(afterLedger);
            journal.begin("venuepay:" + afterLedger.id(), "VENUE_PAYMENT",
                    List.of(owner.getId(), afterLedger.id(), afterLedger.cost().toPlainString(), "venue-payment:" + afterLedger.id()));
            accounts.charge(ownerWallet, afterLedger.cost(), "venue:" + afterLedger.id());
            payments.append(new PaymentTransaction("venue-payment:" + afterLedger.id(), owner.getId(), afterLedger.id(),
                    PaymentPurpose.VENUE_BOOKING, afterLedger.cost(), PaymentStatus.PAID, LocalDateTime.now()));
            paymentService.recoverPending(); paymentService.recoverPending();
            assert reservations.findById(afterLedger.id()).paymentStatus() == PaymentStatus.PAID;
            assert payments.findAll().stream().filter(tx -> tx.id().equals("venue-payment:" + afterLedger.id())).count() == 1;

            // Legacy records without an opening time stay usable and never become LocalDateTime.MIN.
            Files.writeString(dir.resolve("ticket-types.csv"), "id,eventId,name,price,quantity,sold,salesDeadline\n"
                    + "legacy-1," + edited.id() + ",Legacy,12.50,10,0,not-a-date\n");
            TicketType legacy = tickets.types(edited.id()).get(0);
            assert legacy.salesStart() == null && legacy.salesDeadline() == null;
            tickets.saveTypes(edited.id(), List.of(legacy));
            String migrated = Files.readString(dir.resolve("ticket-types.csv"));
            assert !migrated.contains("-999999999-01-01T00:00");
            assert tickets.types(edited.id()).get(0).id().equals("legacy-1");

            events.updateStatus(edited.id(), EventApprovalStatus.PUBLISHED);
            tickets.publish(edited.id(), owner.getId());
            EventPublishingService publishing = new EventPublishingService(events, reservations, tickets, payments, accounts, journal);
            // No reliable legacy opening/closing dates means sales are open immediately with no invented dates.
            publishing.buyTicket(buyer, buyerWallet, edited.id(), "legacy-1", 1);
            assert tickets.types(edited.id()).get(0).salesStart() == null;

            // Ticket recovery can resume after the wallet is charged but before purchase, inventory, or ledger writes.
            journal.begin("ticket:recovered-purchase", "TICKET_PURCHASE",
                    List.of(buyer.getId(), edited.id(), "legacy-1", "2", "25.00", "ticket-payment:recovered-purchase"));
            accounts.charge(buyerWallet, new BigDecimal("25.00"), "ticket:recovered-purchase");
            publishing.recoverPending();
            assert tickets.types(edited.id()).get(0).sold() == 3;
            assert tickets.hasPurchase("recovered-purchase");
            assert payments.hasTransaction("ticket-payment:recovered-purchase");

            // Only unique paid/verified ticket activity with a matching purchase belongs in organizer earnings.
            TicketType otherType = new TicketType("ticket-2", edited.id(), "VIP", "", new BigDecimal("5.00"), 20,
                    0, null, null);
            tickets.saveTypes(edited.id(), List.of(tickets.types(edited.id()).get(0), otherType));
            tickets.purchase("pending-purchase", edited.id(), "ticket-2", buyer.getId(), 1, new BigDecimal("5.00"));
            tickets.purchase("failed-purchase", edited.id(), "ticket-2", buyer.getId(), 1, new BigDecimal("5.00"));
            tickets.purchase("refunded-purchase", edited.id(), "ticket-2", buyer.getId(), 1, new BigDecimal("5.00"));
            payments.append(tx("pending-purchase", buyer, new BigDecimal("5.00"), PaymentStatus.PENDING_VERIFICATION));
            payments.append(tx("failed-purchase", buyer, new BigDecimal("5.00"), PaymentStatus.REJECTED));
            payments.append(tx("refunded-purchase", buyer, new BigDecimal("5.00"), PaymentStatus.PAID));
            payments.append(tx("refunded-purchase", buyer, new BigDecimal("5.00"), PaymentStatus.REFUNDED));
            Event secondOwnerEvent = new Event("second-owner-event", owner.getId(), "Second ticket event", "Description",
                    EventType.CONCERT, EventPurpose.TICKETED_EVENT, EventVisibility.PUBLIC, EVENT_DAY,
                    LocalTime.of(13, 0), LocalTime.of(15, 0), 10, VenueCatalog.all().get(5),
                    EventApprovalStatus.PUBLISHED, LocalDateTime.now(), List.of());
            Customer secondOrganizer = customer("second-organizer@example.test");
            Event otherOrganizerEvent = new Event("other-organizer-event", secondOrganizer.getId(), "Other event", "Description",
                    EventType.CONCERT, EventPurpose.TICKETED_EVENT, EventVisibility.PUBLIC, EVENT_DAY,
                    LocalTime.of(16, 0), LocalTime.of(18, 0), 10, VenueCatalog.all().get(5),
                    EventApprovalStatus.PUBLISHED, LocalDateTime.now(), List.of());
            events.save(secondOwnerEvent); events.save(otherOrganizerEvent);
            tickets.purchase("second-owner-purchase", secondOwnerEvent.id(), "other-ticket", buyer.getId(), 1,
                    new BigDecimal("7.00"));
            tickets.purchase("other-organizer-purchase", otherOrganizerEvent.id(), "other-ticket", buyer.getId(), 1,
                    new BigDecimal("50.00"));
            payments.append(tx("second-owner-purchase", buyer, new BigDecimal("7.00"), PaymentStatus.VERIFIED));
            payments.append(tx("other-organizer-purchase", buyer, new BigDecimal("50.00"), PaymentStatus.PAID));
            var earnings = new OrganizerEarningsService(events, tickets, payments, new CommissionPolicy(new BigDecimal("10")))
                    .forOrganizer(owner);
            assert earnings.grossTicketRevenue().compareTo(new BigDecimal("44.50")) == 0;
            assert earnings.platformCommission().compareTo(new BigDecimal("4.45")) == 0;
            assert earnings.netEarnings().compareTo(new BigDecimal("40.05")) == 0;
            assert earnings.ticketsSold() == 4 && earnings.verifiedTransactions() == 3;
            var otherEarnings = new OrganizerEarningsService(events, tickets, payments,
                    new CommissionPolicy(new BigDecimal("10"))).forOrganizer(secondOrganizer);
            assert otherEarnings.grossTicketRevenue().compareTo(new BigDecimal("50.00")) == 0;
            var changedRate = new OrganizerEarningsService(events, tickets, payments, new CommissionPolicy(new BigDecimal("25")))
                    .forOrganizer(owner);
            assert changedRate.platformCommission().compareTo(new BigDecimal("11.13")) == 0;
            expectIllegal(() -> new CommissionPolicy(new BigDecimal("101")));

            // Recover after the purchase row was written, and after inventory plus ledger were written.
            journal.begin("ticket:after-purchase", "TICKET_PURCHASE",
                    List.of(buyer.getId(), edited.id(), "legacy-1", "1", "12.50", "ticket-payment:after-purchase"));
            accounts.charge(buyerWallet, new BigDecimal("12.50"), "ticket:after-purchase");
            tickets.purchase("after-purchase", edited.id(), "legacy-1", buyer.getId(), 1, new BigDecimal("12.50"));
            publishing.recoverPending();
            assert tickets.types(edited.id()).get(0).sold() == 4;
            journal.begin("ticket:after-inventory", "TICKET_PURCHASE",
                    List.of(buyer.getId(), edited.id(), "legacy-1", "1", "12.50", "ticket-payment:after-inventory"));
            accounts.charge(buyerWallet, new BigDecimal("12.50"), "ticket:after-inventory");
            tickets.purchase("after-inventory", edited.id(), "legacy-1", buyer.getId(), 1, new BigDecimal("12.50"));
            tickets.reconcileSoldQuantity(edited.id(), "legacy-1");
            payments.append(new PaymentTransaction("ticket-payment:after-inventory", buyer.getId(), "after-inventory",
                    PaymentPurpose.TICKET_RESERVATION, new BigDecimal("12.50"), PaymentStatus.PAID, LocalDateTime.now()));
            publishing.recoverPending(); publishing.recoverPending();
            assert tickets.types(edited.id()).get(0).sold() == 5;
            assert payments.findAll().stream().filter(tx -> tx.id().equals("ticket-payment:after-inventory")).count() == 1;
            Event soldEvent = new Event("sold-event", owner.getId(), "Sold event", "Description", EventType.CONCERT,
                    EventPurpose.TICKETED_EVENT, EventVisibility.PUBLIC, EVENT_DAY, LocalTime.of(10, 0),
                    LocalTime.of(12, 0), 10, VenueCatalog.all().get(5), EventApprovalStatus.PUBLISHED,
                    LocalDateTime.now(), List.of());
            events.save(soldEvent);
            TicketType soldType = new TicketType("ticket-1", soldEvent.id(), "Regular", "", new BigDecimal("10.00"),
                    10, 0, null, null);
            tickets.saveTypes(soldEvent.id(), List.of(soldType));
            tickets.purchase("sold-event-purchase", soldEvent.id(), "ticket-1", buyer.getId(), 1,
                    new BigDecimal("10.00"));
            accounts.charge(buyerWallet,new BigDecimal("10.00"),"ticket:sold-event-purchase");
            payments.append(new PaymentTransaction("sold-event-paid",buyer.getId(),"sold-event-purchase",
                    PaymentPurpose.TICKET_RESERVATION,new BigDecimal("10.00"),PaymentStatus.VERIFIED,LocalDateTime.now()));
            tickets.purchase("sold-event-purchase-2",soldEvent.id(),"ticket-1",secondBuyer.getId(),1,new BigDecimal("10.00"));
            accounts.charge(secondBuyerWallet,new BigDecimal("10.00"),"ticket:sold-event-purchase-2");
            payments.append(new PaymentTransaction("sold-event-paid-2",secondBuyer.getId(),"sold-event-purchase-2",
                    PaymentPurpose.TICKET_RESERVATION,new BigDecimal("10.00"),PaymentStatus.PAID,LocalDateTime.now()));
            assert cancellationService.cancel(owner,soldEvent.id()).approvalStatus()==EventApprovalStatus.CANCELLED;
            assert tickets.allPurchaseRecords().stream().filter(p->p.id().equals("sold-event-purchase")).findFirst().orElseThrow().status()
                    ==drawn.model.ticket.TicketPurchaseStatus.REFUNDED;
            assert tickets.types(soldEvent.id()).get(0).sold()==0;
            assert payments.findAll().stream().anyMatch(tx->tx.id().equals("ticket-refund-payment:sold-event-purchase")
                    &&tx.status()==PaymentStatus.REFUNDED);
            assert tickets.allPurchaseRecords().stream().filter(p->p.eventId().equals(soldEvent.id())
                    &&p.status()==drawn.model.ticket.TicketPurchaseStatus.REFUNDED).count()==2;
            assert accounts.findAccount(secondBuyer.getId()).getWalletBalance().compareTo(new BigDecimal("10000.00"))==0;

            Event unresolvedTicketEvent=new Event("unresolved-ticket-event",owner.getId(),"Legacy purchase","Description",
                    EventType.CONCERT,EventPurpose.TICKETED_EVENT,EventVisibility.PUBLIC,EVENT_DAY,LocalTime.of(10,0),
                    LocalTime.NOON,10,VenueCatalog.all().get(5),EventApprovalStatus.PUBLISHED,LocalDateTime.now(),List.of());
            events.save(unresolvedTicketEvent);tickets.saveTypes(unresolvedTicketEvent.id(),List.of(new TicketType("legacy-purchase-type",
                    unresolvedTicketEvent.id(),"Regular","",new BigDecimal("8.00"),10,0,null,null)));
            tickets.purchase("unmatched-legacy-purchase",unresolvedTicketEvent.id(),"legacy-purchase-type",buyer.getId(),
                    1,new BigDecimal("8.00"));
            try { cancellationService.cancel(owner,unresolvedTicketEvent.id());throw new AssertionError("Expected unresolved payment reconciliation."); }
            catch(java.io.IOException expected) { }
            assert events.findAll().stream().filter(e->e.id().equals(unresolvedTicketEvent.id())).findFirst().orElseThrow()
                    .approvalStatus()==EventApprovalStatus.CANCELLATION_PENDING;
            cancellationService.recoverPending();
            assert events.findAll().stream().filter(e->e.id().equals(unresolvedTicketEvent.id())).findFirst().orElseThrow()
                    .approvalStatus()==EventApprovalStatus.CANCELLATION_PENDING;
        } finally {
            try (var paths = Files.walk(dir)) { paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ex) { throw new RuntimeException(ex); }
            }); }
        }
        System.out.println("CustomerRecoveryAndEarningsTest passed.");
    }

    private static void verifyChildProcessWalletRecovery() throws Exception {
        Path dir=Files.createTempDirectory("drawn-wallet-crash-");
        try {
            AccountStore store=new AccountStore(dir);
            AccountStore.Account wallet=new AccountStore.Account("crash-account","Test","User",TODAY.minusYears(25).toString(),
                    "crash@example.test","",ContactMethod.EMAIL,"pw",new BigDecimal("1000.00"));
            store.register(wallet,new BigDecimal("1000.00"));
            String javaPath=Path.of(System.getProperty("java.home"),"bin","java.exe").toString();
            String classpath=Path.of("out").toAbsolutePath().toString();
            Process child=new ProcessBuilder(javaPath,"-cp",classpath,CustomerRecoveryAndEarningsTest.class.getName(),
                    "wallet-ledger-crash",dir.toString(),wallet.getId()).redirectErrorStream(true).start();
            if(!child.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)){child.destroyForcibly();throw new AssertionError("Wallet crash child timed out.");}
            assert child.exitValue()==73 : "Expected forced child exit after durable wallet-ledger append.";
            AccountStore recovered=new AccountStore(dir);
            AccountStore.Account afterRecovery=recovered.findAccount(wallet.getId());
            assert afterRecovery.getWalletBalance().compareTo(new BigDecimal("975.00"))==0;
            recovered.charge(afterRecovery,new BigDecimal("25.00"),"crash-operation");
            assert recovered.findAccount(wallet.getId()).getWalletBalance().compareTo(new BigDecimal("975.00"))==0;
            assert recovered.loadTransactions(wallet.getId()).stream().filter(tx->tx.type().equals("PAYMENT:crash-operation")).count()==1;
        } finally {
            try(var paths=Files.walk(dir)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ex){throw new RuntimeException(ex);}});}
        }
    }

    private static void crashAfterWalletLedger(String[] args) throws Exception {
        Path dir=Path.of(args[1]);String accountId=args[2];
        var method=AccountStore.class.getDeclaredMethod("appendWallet",String.class,String.class,String.class,
                BigDecimal.class,BigDecimal.class,BigDecimal.class);method.setAccessible(true);
        method.invoke(new AccountStore(dir),"PAYMENT:crash-operation:"+accountId,accountId,"PAYMENT:crash-operation",
                new BigDecimal("25.00"),new BigDecimal("1000.00"),new BigDecimal("975.00"));
        Runtime.getRuntime().halt(73);
    }

    private static PaymentTransaction tx(String reference, Customer customer, BigDecimal amount, PaymentStatus status) {
        return new PaymentTransaction("tx-" + status + "-" + reference, customer.getId(), reference,
                PaymentPurpose.TICKET_RESERVATION, amount, status, LocalDateTime.now());
    }
    private static VenueReservation reservation(Customer owner, String id, String eventId, BookingStatus status,
                                                PaymentStatus paymentStatus) {
        return new VenueReservation(id, eventId, owner.getId(), VenueCatalog.all().get(5).name(), EVENT_DAY,
                LocalTime.of(9, 0), LocalTime.of(11, 0), ReservationPricing.HOURLY, new BigDecimal("50.00"),
                status, paymentStatus, "");
    }
    private static EventDraft draft(String name, EventPurpose purpose) {
        var venue = VenueCatalog.all().stream().filter(v -> v.supports(purpose)).findFirst().orElseThrow();
        return new EventDraft(name, "A detailed event description", EventType.CONCERT, purpose,
                EventVisibility.PUBLIC, EVENT_DAY, LocalTime.of(9, 0), LocalTime.of(11, 0), 20, venue, List.of());
    }
    private static Customer customer(String id) { return new Customer(id, id, ContactMethod.EMAIL, id, TODAY.minusYears(25)); }
    private static AccountStore.Account account(Customer c, String balance) {
        return new AccountStore.Account(c.getId(), "Test", "User", TODAY.minusYears(25).toString(), c.getId(), "",
                ContactMethod.EMAIL, "pw", new BigDecimal(balance));
    }
    private static void expectIllegal(Throwing action) throws Exception {
        try { action.run(); throw new AssertionError("Expected IllegalArgumentException"); }
        catch (IllegalArgumentException expected) { }
    }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
}
