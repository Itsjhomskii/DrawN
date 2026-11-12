package drawn.service.customer;

import drawn.gui.AppServices;
import drawn.model.event.*;
import drawn.model.payment.PaymentStatus;
import drawn.model.ticket.TicketType;
import drawn.model.user.ContactMethod;
import drawn.storage.TicketRepository;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** Disposable integration check for customer workflows wired into the JavaFX shell. */
public final class CustomerGuiWorkflowTest {
    private CustomerGuiWorkflowTest() { }

    public static void main(String[] args) throws Exception {
        Path data = Files.createTempDirectory("drawn-customer-gui-");
        try {
            AppServices services = new AppServices(data);
            var owner = services.customerAccess().register("Jamie", "Organizer", LocalDate.now().minusYears(30),
                    ContactMethod.EMAIL, "gui-owner@example.test", "pass", new BigDecimal("100000.00"));
            var login = services.customerAccess().authenticate("gui-owner@example.test", "pass");
            assert login.account().getWalletBalance().compareTo(new BigDecimal("100000.00")) == 0;

            Venue venue = VenueCatalog.all().stream().filter(v -> v.type() == VenueType.BOTH).findFirst().orElseThrow();
            LocalDate eventDate = LocalDate.now().plusDays(45);
            var paid = services.venueWorkflow().requestAndPay(owner.customer(), owner.account(), venue, eventDate,
                    LocalTime.of(14, 0), LocalTime.of(16, 0), ReservationPricing.HOURLY);
            assert paid.status() == BookingStatus.PENDING && paid.paymentStatus() == PaymentStatus.PAID;
            var draft = new EventDraft("GUI Integration Concert", "Disposable customer workflow check",
                    EventType.CONCERT, EventPurpose.TICKETED_EVENT, EventVisibility.PUBLIC, eventDate,
                    LocalTime.of(14, 0), LocalTime.of(16, 0), 20, venue,
                    List.of(new TicketCategory("General", new BigDecimal("100.00"), 10)));
            expectIllegal(() -> services.eventCreationService().create(owner.customer(), draft, paid.id(), ""));

            services.reservations().approve(paid.id());
            Event event = services.eventCreationService().create(owner.customer(), draft, paid.id(), "");
            assert event.approvalStatus() == EventApprovalStatus.PUBLISHED;
            assert services.events().eventsFor(owner.customer()).stream().anyMatch(e -> e.id().equals(event.id()));
            assert services.publishing().publishedEvents().stream().anyMatch(e -> e.id().equals(event.id()));

            var buyer = services.customerAccess().register("Taylor", "Buyer", LocalDate.now().minusYears(25),
                    ContactMethod.EMAIL, "gui-buyer@example.test", "pass", new BigDecimal("1000.00"));
            TicketType category = services.publishing().ticketTypes(event.id()).get(0);
            services.publishing().buyTicket(buyer.customer(), buyer.account(), event.id(), category.id(), 2);
            assert buyer.account().getWalletBalance().compareTo(new BigDecimal("800.00")) == 0;
            assert services.publishing().purchases(buyer.customer()).size() == 1;
            assert services.publishing().ticketTypes(event.id()).get(0).remaining() == 8;

            BigDecimal ownerBeforeOwnBuy = owner.account().getWalletBalance();
            expectIllegal(() -> services.publishing().buyTicket(owner.customer(), owner.account(), event.id(), category.id(), 1));
            assert owner.account().getWalletBalance().compareTo(ownerBeforeOwnBuy) == 0;
            assert services.publishing().ticketTypes(event.id()).get(0).remaining() == 8;

            var lowFunds = services.customerAccess().register("Casey", "Lowfunds", LocalDate.now().minusYears(22),
                    ContactMethod.EMAIL, "gui-lowfunds@example.test", "pass", new BigDecimal("500.00"));
            BigDecimal balanceBefore = lowFunds.account().getWalletBalance();
            expectIllegal(() -> services.publishing().buyTicket(lowFunds.customer(), lowFunds.account(), event.id(), category.id(), 6));
            assert lowFunds.account().getWalletBalance().compareTo(balanceBefore) == 0;
            assert services.publishing().ticketTypes(event.id()).get(0).remaining() == 8;
            assert services.publishing().purchases(lowFunds.customer()).stream()
                    .noneMatch(p -> p.status() == drawn.model.ticket.TicketPurchaseStatus.ACTIVE);
        } finally {
            try (var paths = Files.walk(data)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ex) { throw new RuntimeException(ex); }
                });
            }
        }
        System.out.println("CustomerGuiWorkflowTest passed.");
    }

    @FunctionalInterface private interface Throwing { void run() throws Exception; }
    private static void expectIllegal(Throwing action) throws Exception {
        try { action.run(); throw new AssertionError("Expected IllegalArgumentException"); }
        catch (IllegalArgumentException expected) { }
    }
}
