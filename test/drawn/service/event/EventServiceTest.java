package drawn.service.event;

import drawn.model.event.Event;
import drawn.model.event.EventApprovalStatus;
import drawn.model.event.EventDraft;
import drawn.model.event.EventPurpose;
import drawn.model.event.EventType;
import drawn.model.event.EventVisibility;
import drawn.model.event.TicketCategory;
import drawn.model.event.Venue;
import drawn.model.event.VenueCatalog;
import drawn.model.event.VenueType;
import drawn.model.user.ContactMethod;
import drawn.model.user.Customer;
import drawn.storage.EventRepository;

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

/** Dependency-free checks for event submission rules and CSV persistence. */
public final class EventServiceTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);

    private EventServiceTest() { }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("drawn-event-test-");
        try {
            EventRepository repository = new EventRepository(directory);
            EventService service = new EventService(repository,
                    Clock.fixed(Instant.parse("2026-11-04T12:00:00Z"), ZoneOffset.UTC));
            Customer owner = customer("owner@example.test", "Event Owner", TODAY.minusYears(21));
            Customer other = customer("other@example.test", "Other Owner", TODAY.minusYears(21));

            for (EventPurpose purpose : EventPurpose.values()) {
                for (EventVisibility visibility : EventVisibility.values()) {
                    Event saved = service.saveDraft(owner, draft(purpose, visibility));
                    assert saved.approvalStatus() == EventApprovalStatus.DRAFT;
                    assert saved.organizerId().equals(owner.getId());
                }
            }

            assert service.eventsFor(owner).size() == 4;
            assert service.pendingCountFor(owner) == 0;
            assert service.eventsFor(other).isEmpty();
            assert service.publicApprovedEvents().isEmpty();
            assert VenueCatalog.all().size() == 10;
            assert VenueCatalog.all().stream().filter(venue -> venue.type() == VenueType.BOTH).count() == 6;
            assert VenueCatalog.all().stream().filter(venue -> venue.type() == VenueType.COMMERCIAL).count() == 2;
            assert VenueCatalog.all().stream().filter(venue -> venue.type() == VenueType.NON_COMMERCIAL).count() == 2;
            assert VenueCatalog.all().stream().allMatch(venue -> venue.capacity() > 0
                    && venue.hourlyRate().signum() > 0 && venue.morningRate().signum() > 0
                    && venue.afternoonRate().signum() > 0);

            Event publicApproved = new Event("approved-public", owner.getId(), "Public", "Approved event",
                    EventType.CONCERT, EventPurpose.TICKETED_EVENT, EventVisibility.PUBLIC, TODAY,
                    LocalTime.of(18, 0), LocalTime.of(20, 0), 100, VenueCatalog.all().get(5),
                    EventApprovalStatus.APPROVED, LocalDateTime.now(),
                    List.of(new TicketCategory("Regular", new BigDecimal("25.50"), 50)));
            Event privateApproved = new Event("approved-private", owner.getId(), "Private", "Hidden event",
                    EventType.BIRTHDAY, EventPurpose.SOCIAL_GATHERING, EventVisibility.PRIVATE, TODAY,
                    LocalTime.of(18, 0), LocalTime.of(20, 0), 20, VenueCatalog.all().get(0),
                    EventApprovalStatus.APPROVED, LocalDateTime.now(), List.of());
            repository.save(publicApproved);
            repository.save(privateApproved);
            assert repository.findPublicApproved().equals(List.of(publicApproved));

            Event updatedPublic = new Event(publicApproved.id(), owner.getId(), "Public Updated", "Approved event",
                    EventType.CONCERT, EventPurpose.TICKETED_EVENT, EventVisibility.PUBLIC, TODAY,
                    LocalTime.of(18, 0), LocalTime.of(20, 0), 100, VenueCatalog.all().get(5),
                    EventApprovalStatus.APPROVED, LocalDateTime.now(), publicApproved.ticketCategories());
            repository.save(updatedPublic);
            repository.updateStatus(updatedPublic.id(), EventApprovalStatus.PENDING);
            EventRepository refreshed = new EventRepository(directory);
            assert refreshed.findByOrganizer(owner.getId()).stream().filter(e->e.id().equals(updatedPublic.id())).count()==1;
            assert refreshed.findByOrganizer(owner.getId()).stream().filter(e->e.id().equals(updatedPublic.id())).findFirst().orElseThrow()
                    .approvalStatus()==EventApprovalStatus.PENDING;

            EventRepository reloaded = new EventRepository(directory);
            Event persisted = reloaded.findByOrganizer(owner.getId()).stream()
                    .filter(event -> event.id().equals("approved-public")).findFirst().orElseThrow();
            assert persisted.ticketCategories().size() == 1;
            assert persisted.ticketCategories().get(0).price().compareTo(new BigDecimal("25.50")) == 0;
            assert persisted.description().equals("Approved event");

            expectIllegal(() -> service.saveDraft(owner, draftNamed(" ", EventPurpose.SOCIAL_GATHERING,
                    EventVisibility.PUBLIC, List.of())));
            expectIllegal(() -> service.saveDraft(owner, draftNamed("12345", EventPurpose.SOCIAL_GATHERING,
                    EventVisibility.PUBLIC, List.of())));
            expectIllegal(() -> service.saveDraft(owner, new EventDraft("Numeric description", "123456", EventType.BIRTHDAY,
                    EventPurpose.SOCIAL_GATHERING, EventVisibility.PUBLIC, TODAY, LocalTime.NOON,
                    LocalTime.of(13,0),10,VenueCatalog.all().get(0),List.of())));
            expectIllegal(() -> service.saveDraft(owner, new EventDraft("Past", "Description", EventType.BIRTHDAY,
                    EventPurpose.SOCIAL_GATHERING, EventVisibility.PUBLIC, TODAY.minusDays(1),
                    LocalTime.NOON, LocalTime.of(13, 0), 10, VenueCatalog.all().get(0), List.of())));
            expectIllegal(() -> service.saveDraft(owner, new EventDraft("Bad time", "Description", EventType.BIRTHDAY,
                    EventPurpose.SOCIAL_GATHERING, EventVisibility.PUBLIC, TODAY,
                    LocalTime.of(14, 0), LocalTime.of(13, 0), 10, VenueCatalog.all().get(0), List.of())));
            expectIllegal(() -> service.saveDraft(owner, new EventDraft("Bad attendance", "Description",
                    EventType.BIRTHDAY, EventPurpose.SOCIAL_GATHERING, EventVisibility.PUBLIC, TODAY,
                    LocalTime.NOON, LocalTime.of(13, 0), 0, VenueCatalog.all().get(0), List.of())));
            expectIllegal(() -> service.saveDraft(owner, new EventDraft("Too many guests", "Description",
                    EventType.BIRTHDAY, EventPurpose.SOCIAL_GATHERING, EventVisibility.PUBLIC, TODAY,
                    LocalTime.NOON, LocalTime.of(13,0), VenueCatalog.all().get(0).capacity() + 1,
                    VenueCatalog.all().get(0), List.of())));
            expectIllegal(() -> service.saveDraft(owner, new EventDraft("Bad venue", "Description", EventType.CONCERT,
                    EventPurpose.SOCIAL_GATHERING, EventVisibility.PUBLIC, TODAY, LocalTime.NOON,
                    LocalTime.of(13, 0), 10, new Venue("Commercial venue", VenueType.COMMERCIAL), List.of())));
            Event organizingWithoutTicketSetup = service.saveDraft(owner, draftNamed("No categories",
                    EventPurpose.TICKETED_EVENT, EventVisibility.PUBLIC, List.of()));
            assert organizingWithoutTicketSetup.ticketCategories().isEmpty();
            expectIllegal(() -> service.saveDraft(owner, draftNamed("Social categories", EventPurpose.SOCIAL_GATHERING,
                    EventVisibility.PUBLIC, List.of(new TicketCategory("VIP", BigDecimal.ZERO, 1)))));
            expectIllegal(() -> service.saveDraft(owner, draftNamed("Duplicate categories", EventPurpose.TICKETED_EVENT,
                    EventVisibility.PUBLIC, List.of(new TicketCategory("Regular", BigDecimal.ONE, 1),
                            new TicketCategory("regular", BigDecimal.TEN, 2)))));
            expectIllegal(() -> new TicketCategory("Negative price", new BigDecimal("-1"), 1));
            expectIllegal(() -> new TicketCategory("Zero quantity", BigDecimal.ONE, 0));
            expectIllegal(() -> service.saveDraft(owner, new EventDraft("Missing", "Description", EventType.BIRTHDAY,
                    EventPurpose.SOCIAL_GATHERING, EventVisibility.PUBLIC, null, LocalTime.NOON,
                    LocalTime.of(13, 0), 10, VenueCatalog.all().get(0), List.of())));

            assert !EventService.isAgeEligible(TODAY.minusYears(15), TODAY);
            assert EventService.isAgeEligible(TODAY.minusYears(16), TODAY);
            assert !EventService.isAgeEligible(TODAY.plusDays(1), TODAY);
        } finally {
            try (var paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); }
                    catch (Exception ex) { throw new RuntimeException(ex); }
                });
            }
        }
        System.out.println("EventServiceTest passed.");
    }

    private static Customer customer(String id, String name, LocalDate dob) {
        return new Customer(id, name, ContactMethod.EMAIL, id, dob);
    }

    private static EventDraft draft(EventPurpose purpose, EventVisibility visibility) {
        List<TicketCategory> categories = purpose == EventPurpose.TICKETED_EVENT
                ? List.of(new TicketCategory("Regular", new BigDecimal("15.00"), 25)) : List.of();
        return draftNamed("Event " + purpose + " " + visibility, purpose, visibility, categories);
    }

    private static EventDraft draftNamed(String name, EventPurpose purpose, EventVisibility visibility,
                                         List<TicketCategory> categories) {
        Venue venue = VenueCatalog.all().stream().filter(candidate -> candidate.supports(purpose)).findFirst().orElseThrow();
        return new EventDraft(name, "Description, with commas", EventType.WORKSHOP, purpose, visibility,
                TODAY, LocalTime.of(9, 0), LocalTime.of(11, 0), 25, venue, categories);
    }

    private static void expectIllegal(ThrowingRunnable action) throws Exception {
        try {
            action.run();
            throw new AssertionError("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected validation failure.
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
