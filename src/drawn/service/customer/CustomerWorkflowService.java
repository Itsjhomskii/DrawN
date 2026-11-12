package drawn.service.customer;

import drawn.model.event.BookingStatus;
import drawn.model.event.Event;
import drawn.model.event.EventApprovalStatus;
import drawn.model.event.EventDraft;
import drawn.model.event.EventPurpose;
import drawn.model.event.EventVisibility;
import drawn.model.event.ReservationPricing;
import drawn.model.event.TicketCategory;
import drawn.model.event.Venue;
import drawn.model.event.VenueCatalog;
import drawn.model.event.VenueReservation;
import drawn.model.payment.PaymentStatus;
import drawn.model.ticket.TicketType;
import drawn.model.user.Customer;
import drawn.service.event.EventPublishingService;
import drawn.service.event.EventService;
import drawn.service.event.ReservationService;
import drawn.service.payment.PaymentService;
import drawn.storage.AccountStore;
import drawn.storage.EventRepository;
import drawn.storage.PrivateEventAccessRepository;
import drawn.storage.ReservationRepository;
import drawn.storage.TicketRepository;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Customer workflow steps shared by the console (drawn.DrawN) and the JavaFX GUI.
 * Moved unchanged out of DrawN so both front ends run the same rules.
 */
public final class CustomerWorkflowService {
    private final AccountStore accounts;
    private final EventRepository eventRepository;
    private final ReservationRepository reservationRepository;
    private final TicketRepository ticketRepository;
    private final PrivateEventAccessRepository privateEventAccess;
    private final ReservationService reservationService;
    private final PaymentService paymentService;
    private final EventService eventService;
    private final EventPublishingService publishingService;

    public CustomerWorkflowService(AccountStore accounts, EventRepository eventRepository,
                                   ReservationRepository reservationRepository, TicketRepository ticketRepository,
                                   PrivateEventAccessRepository privateEventAccess, ReservationService reservationService,
                                   PaymentService paymentService, EventService eventService,
                                   EventPublishingService publishingService) {
        this.accounts = accounts;
        this.eventRepository = eventRepository;
        this.reservationRepository = reservationRepository;
        this.ticketRepository = ticketRepository;
        this.privateEventAccess = privateEventAccess;
        this.reservationService = reservationService;
        this.paymentService = paymentService;
        this.eventService = eventService;
        this.publishingService = publishingService;
    }

    // ---- Venues & reservations ----

    public static List<Venue> compatibleVenues(EventPurpose purpose) {
        return VenueCatalog.all().stream().filter(venue -> venue.supports(purpose)).toList();
    }

    public static Optional<Venue> venueNamed(String name) {
        return VenueCatalog.all().stream().filter(v -> v.name().equals(name)).findFirst();
    }

    public static BigDecimal reservationQuote(Venue venue, LocalTime start, LocalTime end, ReservationPricing pricing) {
        return switch (pricing) {
            case HALF_DAY_MORNING -> venue.morningRate();
            case HALF_DAY_AFTERNOON -> venue.afternoonRate();
            case HOURLY -> venue.hourlyRate().multiply(BigDecimal.valueOf(Duration.between(start, end).toMinutes()))
                    .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
        };
    }

    public static String reservationCostExplanation(VenueReservation reservation) {
        Venue venue = venueNamed(reservation.venueName()).orElse(null);
        if (venue == null) return reservation.pricing() + " rate; stored total PHP " + reservation.cost().setScale(2).toPlainString();
        return switch (reservation.pricing()) {
            case HALF_DAY_MORNING -> "fixed morning block PHP " + venue.morningRate().setScale(2).toPlainString() + " (not based on guest count or minutes)";
            case HALF_DAY_AFTERNOON -> "fixed afternoon block PHP " + venue.afternoonRate().setScale(2).toPlainString() + " (not based on guest count or minutes)";
            case HOURLY -> {
                long minutes = Duration.between(reservation.startTime(), reservation.endTime()).toMinutes();
                BigDecimal hours = BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP);
                yield venue.hourlyRate().toPlainString() + "/hour × " + hours.toPlainString() + " hours";
            }
        };
    }

    /** Validates the schedule and wallet balance, then returns the amount due. Nothing is saved. */
    public BigDecimal quoteVenueRequest(AccountStore.Account account, Venue venue, LocalTime start, LocalTime end,
                                        ReservationPricing pricing) {
        ReservationService.validatePricing(start, end, pricing);
        BigDecimal quote = reservationQuote(venue, start, end, pricing);
        if (account.getWalletBalance().compareTo(quote) < 0)
            throw new IllegalArgumentException("Insufficient wallet balance.");
        return quote;
    }

    /** Creates the reservation request and pays for it in full from the wallet. */
    public VenueReservation requestAndPayVenue(Customer customer, AccountStore.Account account, Venue venue, LocalDate date,
                                               LocalTime start, LocalTime end, ReservationPricing pricing) throws IOException {
        VenueReservation reservation = reservationService.request(customer, venue, date, start, end, pricing);
        try {
            return paymentService.payVenue(customer, account, reservation.id());
        } catch (IOException | RuntimeException paymentFailure) {
            // If no financial record was written, remove the just-created unpaid request.
            // Otherwise leave it for the journal recovery path instead of losing the payment.
            VenueReservation current = reservationRepository.findById(reservation.id());
            if (current.paymentStatus() == PaymentStatus.UNPAID
                    && !accounts.hasWalletTransaction(account.getId(), "PAYMENT:venue:" + reservation.id()))
                reservationRepository.delete(reservation.id());
            throw paymentFailure;
        }
    }

    public List<VenueReservation> activeReservations(Customer customer) throws IOException {
        return reservationService.forCustomer(customer).stream().filter(reservation -> reservation.status() != BookingStatus.CANCELLED
                && reservation.status() != BookingStatus.DECLINED && reservation.status() != BookingStatus.EXPIRED).toList();
    }

    public List<VenueReservation> unlinkedReservations(Customer customer) throws IOException {
        return reservationService.forCustomer(customer).stream().filter(reservation -> reservation.eventId().isBlank()).toList();
    }

    /** Approved, paid reservations that do not have an event yet. */
    public List<VenueReservation> reservationsReadyForEvent(Customer customer) throws IOException {
        return reservationService.forCustomer(customer).stream()
                .filter(r -> (r.status() == BookingStatus.APPROVED || r.status() == BookingStatus.CONFIRMED)
                        && r.paymentStatus() == PaymentStatus.PAID
                        && r.eventId().isBlank()).toList();
    }

    public Optional<VenueReservation> reservationForEvent(Customer customer, String eventId) throws IOException {
        return reservationService.forCustomer(customer).stream().filter(reservation -> reservation.eventId().equals(eventId)).findFirst();
    }

    // ---- Events ----

    public List<Event> activeEvents(Customer customer) throws IOException {
        return eventService.eventsFor(customer).stream()
                .filter(event -> event.approvalStatus() != EventApprovalStatus.CANCELLED).toList();
    }

    public List<Event> privateEvents(String keyword) throws IOException {
        return eventRepository.findAll().stream().filter(event -> event.approvalStatus()
                        == EventApprovalStatus.PUBLISHED && event.visibility() == EventVisibility.PRIVATE)
                .filter(event -> {
                    try { return privateEventAccess.matches(event.id(), keyword); }
                    catch (IOException ex) { throw new IllegalStateException(ex); }
                }).toList();
    }

    /** The catalog venue behind a reservation, checked against the chosen event purpose. */
    public static Venue reservedVenue(VenueReservation reservation, EventPurpose purpose) {
        Venue venue = venueNamed(reservation.venueName())
                .orElseThrow(() -> new IllegalArgumentException("Reserved venue details are unavailable."));
        if (!venue.supports(purpose)) throw new IllegalArgumentException("Selected reservation venue does not support this event purpose.");
        return venue;
    }

    public static void requireTicketsFitVenue(List<TicketCategory> categories, Venue venue) {
        int ticketQuantity = categories.stream().mapToInt(TicketCategory::quantity).sum();
        if (ticketQuantity > venue.capacity()) throw new IllegalArgumentException("Total ticket quantity exceeds venue capacity.");
    }

    /** Saves the event against an approved reservation, configures tickets and publishes; rolls back on failure. */
    public Event createAndPublishEvent(Customer customer, EventDraft draft, VenueReservation reservation,
                                       String privateKeyword) throws IOException {
        Event saved = null;
        try {
            saved = eventService.createPublishedForApprovedReservation(customer, draft, reservation.id());
            if (draft.visibility() == EventVisibility.PRIVATE) privateEventAccess.save(saved.id(), privateKeyword);
            if (draft.purpose() == EventPurpose.TICKETED_EVENT) {
                String eventId = saved.id();
                LocalDateTime salesStart = LocalDateTime.now();
                LocalDateTime salesDeadline = reservation.date().atTime(reservation.startTime()).minusMinutes(1);
                List<TicketType> types = draft.ticketCategories().stream().map(category -> new TicketType(UUID.randomUUID().toString(),
                        eventId, category.name(), category.name() + " ticket", category.price(), category.quantity(), 0,
                        salesStart, salesDeadline)).toList();
                publishingService.configureTickets(customer, saved.id(), types);
                publishingService.publish(customer, saved.id());
            }
            return saved;
        } catch (IOException | RuntimeException creationFailure) {
            if (saved != null) {
                try {
                    privateEventAccess.delete(saved.id());
                    eventService.discardFailedCustomerCreation(customer, saved.id(), reservation.id());
                } catch (IOException | RuntimeException rollbackFailure) { creationFailure.addSuppressed(rollbackFailure); }
            }
            throw creationFailure;
        }
    }

    public static String displayPurpose(EventPurpose purpose) {
        return purpose == EventPurpose.SOCIAL_GATHERING ? "Social Gathering" : "Commercial / Ticketed Event";
    }

    // ---- Tickets ----

    public String ticketCategoryName(TicketRepository.Purchase purchase) throws IOException {
        return publishingService.ticketTypes(purchase.eventId()).stream().filter(t -> t.id().equals(purchase.ticketTypeId()))
                .map(TicketType::name).findFirst().orElse("Ticket");
    }

    public List<TicketRepository.Purchase> activePurchases(Customer customer) throws IOException {
        return publishingService.purchases(customer).stream()
                .filter(purchase -> purchase.status() == drawn.model.ticket.TicketPurchaseStatus.ACTIVE).toList();
    }

    // ---- Wallet history ----

    public static boolean isFunding(AccountStore.Transaction transaction) {
        return transaction.type().equals("TOP_UP") || transaction.type().equals("INITIAL_FUNDING");
    }

    public static String fundingLabel(AccountStore.Transaction transaction) {
        return transaction.type().equals("TOP_UP") ? "Wallet top-up" : "Initial wallet funding";
    }

    public static String transactionLabel(String type) {
        if (type.startsWith("PAYMENT:ticket:")) return "Ticket purchase";
        if (type.startsWith("REFUND:ticket-cancellation:")) return "Ticket cancellation refund";
        if (type.startsWith("REFUND:ticket-refund:")) return "Event cancellation ticket refund";
        if (type.startsWith("CREDIT:ticket-cancellation-fee:")) return "Ticket cancellation fee received";
        if (type.startsWith("PAYMENT:venue:")) return "Venue booking payment";
        if (type.startsWith("REFUND:venue:")) return "Venue booking refund";
        return type;
    }

    public String transactionDetails(AccountStore.Transaction transaction) throws IOException {
        String type = transaction.type();
        String purchaseId = null;
        String action = null;
        if (type.startsWith("PAYMENT:ticket:")) { purchaseId = type.substring("PAYMENT:ticket:".length()); action = "Purchase"; }
        else if (type.startsWith("REFUND:ticket-cancellation:")) { purchaseId = type.substring("REFUND:ticket-cancellation:".length()); action = "Customer cancellation"; }
        else if (type.startsWith("REFUND:ticket-refund:")) { purchaseId = type.substring("REFUND:ticket-refund:".length()); action = "Event cancellation"; }
        else if (type.startsWith("CREDIT:ticket-cancellation-fee:")) { purchaseId = type.substring("CREDIT:ticket-cancellation-fee:".length()); action = "Organizer share of cancellation fee"; }
        if (purchaseId != null) {
            String ticketPurchaseId = purchaseId;
            TicketRepository.Purchase purchase = ticketRepository.allPurchaseRecords().stream()
                    .filter(candidate -> candidate.id().equals(ticketPurchaseId)).findFirst().orElse(null);
            if (purchase == null) return action + " for a ticket purchase.";
            Event event = eventRepository.findAll().stream().filter(candidate -> candidate.id().equals(purchase.eventId()))
                    .findFirst().orElse(null);
            String category = ticketRepository.types(purchase.eventId()).stream()
                    .filter(candidate -> candidate.id().equals(purchase.ticketTypeId())).map(TicketType::name)
                    .findFirst().orElse("Ticket");
            return action + " — " + (event == null ? "Event" : event.name()) + " | " + category + " | "
                    + purchase.quantity() + " ticket(s).";
        }
        String reservationId = null;
        if (type.startsWith("PAYMENT:venue:")) reservationId = type.substring("PAYMENT:venue:".length());
        else if (type.startsWith("REFUND:venue:")) reservationId = type.substring("REFUND:venue:".length());
        if (reservationId != null) {
            String venueReservationId = reservationId;
            VenueReservation reservation = reservationRepository.findAll().stream()
                    .filter(candidate -> candidate.id().equals(venueReservationId)).findFirst().orElse(null);
            if (reservation == null) return "Venue reservation transaction.";
            return reservation.venueName() + " | " + reservation.date() + " | " + reservation.startTime()
                    + "-" + reservation.endTime() + ".";
        }
        return "Event-related wallet transaction.";
    }
}
