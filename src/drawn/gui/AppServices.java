package drawn.gui;

import drawn.service.admin.AdminAuditLog;
import drawn.service.admin.AdminDashboardService;
import drawn.service.customer.CustomerAccessService;
import drawn.service.customer.CustomerWorkflowService;
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
import java.time.Clock;

/** GUI composition root, wired the same way as drawn.DrawN; all stateful operations remain in existing services. */
public final class AppServices {
    private final AccountStore accounts;
    private final CustomerAccessService customerAccess;
    private final EventService events;
    private final ReservationService reservations;
    private final PaymentService payment;
    private final EventPublishingService publishing;
    private final EventCancellationService cancellation;
    private final OrganizerEarningsService earnings;
    private final CustomerWorkflowService workflow;
    private final drawn.service.customer.CustomerVenueWorkflowService venueWorkflow;
    private final drawn.service.customer.CustomerEventCreationService eventCreation;
    private final AdminDashboardService adminDashboard;

    public AppServices(Path dataDirectory) {
        accounts = new AccountStore(dataDirectory);
        EventRepository eventRepository = new EventRepository(dataDirectory);
        ReservationRepository reservationRepository = new ReservationRepository(dataDirectory);
        PaymentRepository paymentRepository = new PaymentRepository(dataDirectory);
        TicketRepository ticketRepository = new TicketRepository(dataDirectory);
        TransactionJournal journal = new TransactionJournal(dataDirectory);
        PrivateEventAccessRepository privateAccess = new PrivateEventAccessRepository(dataDirectory);
        customerAccess = new CustomerAccessService(accounts);
        earnings = new OrganizerEarningsService(eventRepository, ticketRepository, paymentRepository, CommissionPolicy.configured());
        events = new EventService(eventRepository, reservationRepository, ticketRepository, paymentRepository, Clock.systemDefaultZone());
        payment = new PaymentService(accounts, reservationRepository, paymentRepository, journal, eventRepository);
        reservations = new ReservationService(eventRepository, reservationRepository, Clock.systemDefaultZone(), payment);
        publishing = new EventPublishingService(eventRepository, reservationRepository, ticketRepository, paymentRepository, accounts, journal);
        cancellation = new EventCancellationService(eventRepository, reservationRepository, ticketRepository, paymentRepository, accounts, journal);
        workflow = new CustomerWorkflowService(accounts, eventRepository, reservationRepository, ticketRepository,
                privateAccess, reservations, payment, events, publishing);
        venueWorkflow = new drawn.service.customer.CustomerVenueWorkflowService(accounts, reservationRepository, reservations, payment);
        eventCreation = new drawn.service.customer.CustomerEventCreationService(events, publishing, privateAccess);
        adminDashboard = new AdminDashboardService(reservations, eventRepository, ticketRepository, paymentRepository,
                accounts, journal, CommissionPolicy.configured(), new AdminAuditLog(dataDirectory, Clock.systemDefaultZone()));
    }

    public void recoverPending() throws IOException {
        payment.recoverPending();
        publishing.recoverPending();
        cancellation.recoverPending();
    }

    public AccountStore accounts() { return accounts; }
    public CustomerAccessService customerAccess() { return customerAccess; }
    public EventService events() { return events; }
    public ReservationService reservations() { return reservations; }
    public PaymentService payment() { return payment; }
    public EventPublishingService publishing() { return publishing; }
    public EventCancellationService cancellation() { return cancellation; }
    public OrganizerEarningsService earnings() { return earnings; }
    public CustomerWorkflowService workflow() { return workflow; }
    public drawn.service.customer.CustomerVenueWorkflowService venueWorkflow() { return venueWorkflow; }
    public drawn.service.customer.CustomerEventCreationService eventCreationService() { return eventCreation; }
    public AdminDashboardService adminDashboard() { return adminDashboard; }
}
