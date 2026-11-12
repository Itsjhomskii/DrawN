package drawn.gui;

import drawn.service.customer.CustomerAccessService;
import drawn.service.customer.CustomerEventCreationService;
import drawn.service.customer.CustomerVenueWorkflowService;
import drawn.service.event.EventCancellationService;
import drawn.service.event.EventPublishingService;
import drawn.service.event.EventService;
import drawn.service.event.ReservationService;
import drawn.service.payment.CommissionPolicy;
import drawn.service.payment.OrganizerEarningsService;
import drawn.service.payment.PaymentService;
import drawn.storage.AccountStore;
import drawn.storage.EventRepository;
import drawn.storage.PaymentRepository;
import drawn.storage.PrivateEventAccessRepository;
import drawn.storage.ReservationRepository;
import drawn.storage.TicketRepository;
import drawn.storage.TransactionJournal;

import java.io.IOException;
import java.nio.file.Path;

/** GUI composition root; all stateful operations remain in existing services. */
public final class AppServices {
    private final AccountStore accounts;
    private final EventPublishingService publishing;
    private final CustomerAccessService customerAccess;
    private final EventService eventService;
    private final ReservationService reservationService;
    private final PaymentService paymentService;
    private final EventCancellationService cancellationService;
    private final PrivateEventAccessRepository privateEventAccess;
    private final CustomerVenueWorkflowService venueWorkflow;
    private final CustomerEventCreationService eventCreationService;

    public AppServices(Path dataDirectory) {
        accounts = new AccountStore(dataDirectory);
        EventRepository events = new EventRepository(dataDirectory);
        ReservationRepository reservations = new ReservationRepository(dataDirectory);
        PaymentRepository payments = new PaymentRepository(dataDirectory);
        TicketRepository tickets = new TicketRepository(dataDirectory);
        TransactionJournal journal = new TransactionJournal(dataDirectory);
        PaymentService payment = new PaymentService(accounts, reservations, payments, journal, events);
        eventService = new EventService(events, reservations, tickets, payments, java.time.Clock.systemDefaultZone());
        reservationService = new ReservationService(events, reservations, java.time.Clock.systemDefaultZone(), payment);
        new OrganizerEarningsService(events, tickets, payments, CommissionPolicy.configured());
        publishing = new EventPublishingService(events, reservations, tickets, payments, accounts, journal);
        cancellationService = new EventCancellationService(events, reservations, tickets, payments, accounts, journal);
        customerAccess = new CustomerAccessService(accounts);
        privateEventAccess = new PrivateEventAccessRepository(dataDirectory);
        paymentService = payment;
        venueWorkflow = new CustomerVenueWorkflowService(accounts, reservations, reservationService, payment);
        eventCreationService = new CustomerEventCreationService(eventService, publishing, privateEventAccess);
        this.payment = payment;
        this.cancellation = cancellationService;
    }

    private final PaymentService payment;
    private final EventCancellationService cancellation;

    public void recoverPending() throws IOException {
        payment.recoverPending();
        publishing.recoverPending();
        cancellation.recoverPending();
    }
    public CustomerAccessService customerAccess() { return customerAccess; }
    public EventPublishingService publishing() { return publishing; }
    public EventService eventService() { return eventService; }
    public ReservationService reservationService() { return reservationService; }
    public PaymentService paymentService() { return paymentService; }
    public EventCancellationService cancellationService() { return cancellationService; }
    public AccountStore accounts() { return accounts; }
    public PrivateEventAccessRepository privateEventAccess() { return privateEventAccess; }
    public CustomerVenueWorkflowService venueWorkflow() { return venueWorkflow; }
    public CustomerEventCreationService eventCreationService() { return eventCreationService; }
    public java.util.List<AccountStore.Transaction> walletTransactions(AccountStore.Account account) throws IOException {
        return accounts.loadTransactions(account.getId());
    }
}
