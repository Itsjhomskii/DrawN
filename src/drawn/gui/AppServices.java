package drawn.gui;

import drawn.service.customer.CustomerAccessService;
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

    public AppServices(Path dataDirectory) {
        accounts = new AccountStore(dataDirectory);
        EventRepository events = new EventRepository(dataDirectory);
        ReservationRepository reservations = new ReservationRepository(dataDirectory);
        PaymentRepository payments = new PaymentRepository(dataDirectory);
        TicketRepository tickets = new TicketRepository(dataDirectory);
        TransactionJournal journal = new TransactionJournal(dataDirectory);
        PaymentService payment = new PaymentService(accounts, reservations, payments, journal, events);
        new EventService(events, reservations, tickets, payments, java.time.Clock.systemDefaultZone());
        new ReservationService(events, reservations, java.time.Clock.systemDefaultZone(), payment);
        new OrganizerEarningsService(events, tickets, payments, CommissionPolicy.configured());
        publishing = new EventPublishingService(events, reservations, tickets, payments, accounts, journal);
        EventCancellationService cancellation = new EventCancellationService(events, reservations, tickets, payments, accounts, journal);
        customerAccess = new CustomerAccessService(accounts);
        this.payment = payment;
        this.cancellation = cancellation;
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
}
