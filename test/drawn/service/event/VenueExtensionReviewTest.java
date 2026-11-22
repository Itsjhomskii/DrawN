package drawn.service.event;

import drawn.model.event.EventDraft;
import drawn.model.event.EventPurpose;
import drawn.model.event.EventType;
import drawn.model.event.EventVisibility;
import drawn.model.event.ReservationPricing;
import drawn.model.event.Venue;
import drawn.model.event.VenueCatalog;
import drawn.model.payment.PaymentStatus;
import drawn.model.user.ContactMethod;
import drawn.model.user.Customer;
import drawn.service.payment.PaymentService;
import drawn.storage.AccountStore;
import drawn.storage.EventRepository;
import drawn.storage.PaymentRepository;
import drawn.storage.ReservationRepository;
import drawn.storage.TicketRepository;
import drawn.storage.TransactionJournal;
import drawn.storage.VenueExtensionRepository;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;

public final class VenueExtensionReviewTest {
    private VenueExtensionReviewTest() { }

    public static void main(String[] args) throws Exception {
        Path data = Files.createTempDirectory("drawn-extension-review-");
        VenueCatalog.replaceWith(VenueCatalog.defaults());
        AccountStore accounts = new AccountStore(data);
        EventRepository eventRepo = new EventRepository(data);
        ReservationRepository reservationRepo = new ReservationRepository(data);
        PaymentRepository payments = new PaymentRepository(data);
        TicketRepository tickets = new TicketRepository(data);
        TransactionJournal journal = new TransactionJournal(data);
        EventService events = new EventService(eventRepo, reservationRepo, tickets, payments,
                java.time.Clock.systemDefaultZone());
        PaymentService payment = new PaymentService(accounts, reservationRepo, payments, journal, eventRepo);
        ReservationService reservations = new ReservationService(eventRepo, reservationRepo,
                java.time.Clock.systemDefaultZone(), payment);
        VenueExtensionRepository extensionRepo = new VenueExtensionRepository(data);
        VenueExtensionService extensions = new VenueExtensionService(events, reservations, extensionRepo,
                java.time.Clock.systemDefaultZone());

        Customer owner = new Customer("extension-owner", "Extension Owner", ContactMethod.EMAIL,
                "extension-owner@example.test", LocalDate.now().minusYears(30));
        AccountStore.Account wallet = new AccountStore.Account(owner.getId(), "Extension", "Owner",
                owner.getDateOfBirth().toString(), "extension-owner@example.test", "", ContactMethod.EMAIL,
                "test-password", new BigDecimal("50000.00"));
        accounts.register(wallet, wallet.getWalletBalance());
        Venue venue = VenueCatalog.all().stream().filter(candidate -> candidate.type().name().equals("BOTH"))
                .findFirst().orElseThrow();
        LocalDate firstDate = LocalDate.now().plusDays(35);

        var conflictingBooking = createPublishedEvent(owner, wallet, venue, firstDate, LocalTime.of(9, 0),
                LocalTime.of(11, 0), "Review conflict", events, reservations, payment);
        var conflictRequest = extensions.request(owner, conflictingBooking.eventId(), LocalTime.of(11, 30));
        var other = reservations.request(owner, venue, firstDate, LocalTime.of(13, 0), LocalTime.of(14, 0),
                ReservationPricing.HOURLY);
        payment.payVenue(owner, wallet, other.id());
        reservations.approve(other.id());
        expectIllegal(() -> extensions.approve(conflictRequest.id()));
        assert extensions.pendingRequests().stream().anyMatch(r -> r.id().equals(conflictRequest.id()));
        assert events.allEvents().stream().filter(e -> e.id().equals(conflictingBooking.eventId()))
                .findFirst().orElseThrow().endTime().equals(LocalTime.of(11, 0));
        var declined = extensions.decline(conflictRequest.id(), "The venue needs the original turnaround window.");
        assert declined.status().name().equals("DECLINED");
        assert extensions.requestsForEvent(owner, conflictingBooking.eventId()).get(0).decisionReason()
                .contains("original turnaround window") : "customer event history exposes the decline decision";
        expectIllegal(() -> extensions.decline(conflictRequest.id(), "Second decision"));
        assert reservationRepo.findById(conflictingBooking.reservationId()).endTime().equals(LocalTime.of(11, 0));

        LocalDate secondDate = firstDate.plusDays(2);
        var approvedBooking = createPublishedEvent(owner, wallet, venue, secondDate, LocalTime.of(9, 0),
                LocalTime.of(11, 0), "Review approval", events, reservations, payment);
        var approvalRequest = extensions.request(owner, approvedBooking.eventId(), LocalTime.of(11, 30));
        BigDecimal balanceBeforeDecision = accounts.findAccount(owner.getId()).getWalletBalance();
        var approved = extensions.approve(approvalRequest.id());
        assert approved.status().name().equals("APPROVED");
        assert events.allEvents().stream().filter(e -> e.id().equals(approvedBooking.eventId()))
                .findFirst().orElseThrow().endTime().equals(LocalTime.of(11, 30));
        assert reservationRepo.findById(approvedBooking.reservationId()).endTime().equals(LocalTime.of(11, 30));
        assert reservationRepo.findById(approvedBooking.reservationId()).paymentStatus() == PaymentStatus.PAID;
        assert accounts.findAccount(owner.getId()).getWalletBalance().compareTo(balanceBeforeDecision) == 0
                : "extension approval must not introduce an undefined wallet charge";
        expectIllegal(() -> extensions.approve(approvalRequest.id()));

        // A new repository instance confirms decisions and schedules survive restart.
        assert new VenueExtensionRepository(data).forEvent(approvedBooking.eventId()).get(0).status().name().equals("APPROVED");
        assert extensions.requestsForEvent(owner, approvedBooking.eventId()).get(0).status().name().equals("APPROVED")
                : "customer event history exposes the approval decision";
        assert new EventRepository(data).findAll().stream().filter(e -> e.id().equals(approvedBooking.eventId()))
                .findFirst().orElseThrow().endTime().equals(LocalTime.of(11, 30));
        assert new ReservationRepository(data).findById(approvedBooking.reservationId()).endTime().equals(LocalTime.of(11, 30));
        System.out.println("VenueExtensionReviewTest passed.");
    }

    private static BookingEvent createPublishedEvent(Customer owner, AccountStore.Account wallet, Venue venue,
                                                       LocalDate date, LocalTime start, LocalTime end, String name,
                                                       EventService events, ReservationService reservations,
                                                       PaymentService payment) throws Exception {
        var booking = reservations.request(owner, venue, date, start, end, ReservationPricing.HOURLY);
        payment.payVenue(owner, wallet, booking.id());
        reservations.approve(booking.id());
        var event = events.createPublishedForApprovedReservation(owner,
                new EventDraft(name, "A social gathering used by the extension review check.", EventType.OTHER,
                        EventPurpose.SOCIAL_GATHERING, EventVisibility.PUBLIC, date, start, end, 20, venue, null),
                booking.id());
        return new BookingEvent(event.id(), booking.id());
    }

    private static void expectIllegal(Throwing action) throws Exception {
        try { action.run(); throw new AssertionError("Expected the action to be rejected."); }
        catch (IllegalArgumentException expected) { }
    }

    @FunctionalInterface private interface Throwing { void run() throws Exception; }
    private record BookingEvent(String eventId, String reservationId) { }
}
