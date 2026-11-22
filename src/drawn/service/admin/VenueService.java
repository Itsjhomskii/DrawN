package drawn.service.admin;

import drawn.model.event.BookingStatus;
import drawn.model.event.Venue;
import drawn.model.event.VenueCatalog;
import drawn.model.event.VenueType;
import drawn.storage.ReservationRepository;
import drawn.storage.VenueRepository;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Lets the admin add venues to, and remove venues from, the catalog that event organizers book from. */
public final class VenueService {
    /** Reservation states that still depend on the venue existing. */
    private static final Set<BookingStatus> LIVE = EnumSet.of(BookingStatus.PENDING, BookingStatus.APPROVED,
            BookingStatus.PENDING_PAYMENT, BookingStatus.CONFIRMED, BookingStatus.RESERVED);

    private final VenueRepository repository;
    private final ReservationRepository reservations;
    private final Clock clock;

    /** Loads the saved venues into the shared catalog, so every screen sees them from the start. */
    public VenueService(VenueRepository repository, ReservationRepository reservations, Clock clock) throws IOException {
        this.repository = repository;
        this.reservations = reservations;
        this.clock = clock;
        VenueCatalog.replaceWith(repository.load());
    }

    public List<VenueCatalog.Entry> venues() { return VenueCatalog.entries(); }

    public synchronized Venue add(String name, String category, VenueType type, int capacity,
                                  BigDecimal hourlyRate, BigDecimal halfDayRate) throws IOException {
        Venue venue = new Venue(name, type, capacity, hourlyRate, halfDayRate, halfDayRate);
        if (category == null || category.isBlank()) throw new IllegalArgumentException("Category is required.");
        if (VenueCatalog.all().stream().anyMatch(existing -> existing.name().equalsIgnoreCase(venue.name())))
            throw new IllegalArgumentException(venue.name() + " is already in the catalog.");
        List<VenueCatalog.Entry> updated = new java.util.ArrayList<>(venues());
        updated.add(new VenueCatalog.Entry(venue, category.trim()));
        save(updated);
        return venue;
    }

    /** Removes a venue unless an organizer still has a live reservation there; past bookings are unaffected. */
    public synchronized void remove(String venueName) throws IOException {
        if (reservations.findAll().stream().anyMatch(r -> r.venueName().equals(venueName)
                && LIVE.contains(r.status()) && !r.date().isBefore(LocalDate.now(clock))))
            throw new IllegalStateException(venueName + " has active or upcoming reservations and cannot be removed.");
        List<VenueCatalog.Entry> updated = venues().stream().filter(entry -> !entry.venue().name().equals(venueName)).toList();
        if (updated.size() == venues().size()) throw new IllegalArgumentException(venueName + " is not in the catalog.");
        save(updated);
    }

    /** Saves first, so the in-memory catalog only changes once the file does. */
    private void save(List<VenueCatalog.Entry> updated) throws IOException {
        repository.save(updated);
        VenueCatalog.replaceWith(updated);
    }
}
