package drawn.model.event;

import java.util.List;
import java.math.BigDecimal;

/** Curated Iloilo venue catalog and published reservation rates. */
public final class VenueCatalog {
    private static final List<Entry> ENTRIES = List.of(
            venue("Seda Atria", "Hotel", VenueType.NON_COMMERCIAL, 180, "4000.00", "14000.00"),
            venue("Richmonde Hotel Iloilo", "Hotel", VenueType.BOTH, 100, "3500.00", "12000.00"),
            venue("Park Inn by Radisson Iloilo", "Hotel", VenueType.BOTH, 120, "3000.00", "10000.00"),
            venue("Courtyard by Marriott Iloilo", "Hotel", VenueType.BOTH, 250, "5000.00", "18000.00"),
            venue("Protagonist Cafe and Cocktail Lounge", "Bar", VenueType.COMMERCIAL, 60, "2000.00", "7000.00"),
            venue("Muelle Deli and Restaurant, Iloilo City", "Restaurant", VenueType.COMMERCIAL, 70, "1800.00", "6000.00"),
            venue("The Veranda at MO2 Westown Hotel", "Restaurant", VenueType.NON_COMMERCIAL, 80, "2000.00", "7000.00"),
            venue("Sol y Mar Family Beach Resort", "Beach resort", VenueType.BOTH, 150, "2500.00", "9000.00"),
            venue("Solina Beach and Nature Resort", "Beach resort", VenueType.BOTH, 250, "4500.00", "16000.00"),
            venue("Iloilo Convention Center (iCon)", "Convention", VenueType.BOTH, 3000, "12000.00", "45000.00"));

    private VenueCatalog() { }

    private static Entry venue(String name, String category, VenueType type, int capacity, String hourly, String halfDay) {
        Venue venue = new Venue(name, type, capacity, new BigDecimal(hourly),
                new BigDecimal(halfDay), new BigDecimal(halfDay));
        return new Entry(venue, category);
    }

    public static List<Venue> all() {
        return ENTRIES.stream().map(Entry::venue).toList();
    }

    public static String categoryOf(Venue venue) {
        return ENTRIES.stream().filter(entry -> entry.venue().name().equals(venue.name()))
                .map(Entry::category).findFirst().orElse("Venue");
    }

    private record Entry(Venue venue, String category) { }
}
