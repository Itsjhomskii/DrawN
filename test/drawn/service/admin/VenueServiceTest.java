package drawn.service.admin;

import drawn.model.event.BookingStatus;
import drawn.model.event.ReservationPricing;
import drawn.model.event.VenueCatalog;
import drawn.model.event.VenueReservation;
import drawn.model.event.VenueType;
import drawn.model.payment.PaymentStatus;
import drawn.storage.ReservationRepository;
import drawn.storage.VenueRepository;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;

/** Checks that admin-added venues are validated, saved across restarts, and protected while reserved. */
public final class VenueServiceTest {
    private static final Clock CLOCK = Clock.systemDefaultZone();

    private VenueServiceTest() { }

    public static void main(String[] args) throws Exception {
        Path data = Files.createTempDirectory("drawn-venues-");
        ReservationRepository reservations = new ReservationRepository(data);
        VenueService service = open(data, reservations);

        assert service.venues().size() == 10 : "built-in venues are used the first time";
        assert Files.exists(data.resolve("venues.csv")) : "venue list is saved";

        service.add("  Test Garden  ", "Garden", VenueType.COMMERCIAL, 50, new BigDecimal("1500.00"), new BigDecimal("5000.00"));
        service.add("Cafe \"Uno\", Iloilo", "Cafe", VenueType.BOTH, 30, new BigDecimal("800"), new BigDecimal("2500"));
        assert service.venues().size() == 12;
        assert VenueCatalog.all().stream().anyMatch(v -> v.name().equals("Test Garden")) : "name is trimmed";

        VenueService restarted = open(data, reservations);
        assert restarted.venues().size() == 12 : "added venues survive a restart";
        assert VenueCatalog.all().stream().anyMatch(v -> v.name().equals("Cafe \"Uno\", Iloilo")) : "commas and quotes survive";

        expect(IllegalArgumentException.class, () -> service.add("test garden", "Garden", VenueType.BOTH, 10, BigDecimal.ONE, BigDecimal.ONE));
        expect(IllegalArgumentException.class, () -> service.add("No Category", " ", VenueType.BOTH, 10, BigDecimal.ONE, BigDecimal.ONE));
        expect(IllegalArgumentException.class, () -> service.add("Bad Rate", "Hall", VenueType.BOTH, 10, new BigDecimal("1.999"), BigDecimal.ONE));
        expect(IllegalArgumentException.class, () -> service.add("No Seats", "Hall", VenueType.BOTH, 0, BigDecimal.ONE, BigDecimal.ONE));
        assert service.venues().size() == 12 : "rejected venues are not saved";

        LocalDate future = LocalDate.now(CLOCK).plusDays(10);
        reservations.save(new VenueReservation("r1", "", "owner@example.test", "Test Garden", future,
                LocalTime.of(9, 0), LocalTime.of(11, 0), ReservationPricing.HOURLY, new BigDecimal("3000.00"),
                BookingStatus.RESERVED, PaymentStatus.PAID, ""));
        expect(IllegalStateException.class, () -> service.remove("Test Garden"));
        assert service.venues().size() == 12 : "a reserved venue stays";

        reservations.update(reservations.findById("r1").withStatus(BookingStatus.CANCELLED, ""));
        service.remove("Test Garden");
        assert open(data, reservations).venues().size() == 11 : "removal survives a restart";
        expect(IllegalArgumentException.class, () -> service.remove("Test Garden"));

        VenueCatalog.replaceWith(VenueCatalog.defaults());
        System.out.println("VenueServiceTest passed.");
    }

    private static VenueService open(Path data, ReservationRepository reservations) throws IOException {
        return new VenueService(new VenueRepository(data), reservations, CLOCK);
    }

    private interface Action { void run() throws Exception; }

    private static void expect(Class<? extends Exception> type, Action action) throws Exception {
        try { action.run(); }
        catch (Exception ex) { if (type.isInstance(ex)) return; throw ex; }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
}
