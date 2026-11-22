package drawn.storage;

import drawn.model.event.Venue;
import drawn.model.event.VenueCatalog;
import drawn.model.event.VenueType;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Stores the venue catalog in venues.csv, starting from the built-in venues the first time it is used. */
public final class VenueRepository {
    private static final String HEADER = "name,category,type,capacity,hourlyRate,morningRate,afternoonRate";
    private final Path file;

    public VenueRepository(Path dataDirectory) { file = dataDirectory.resolve("venues.csv"); }

    public synchronized List<VenueCatalog.Entry> load() throws IOException {
        if (!Files.exists(file)) save(VenueCatalog.defaults());
        List<VenueCatalog.Entry> entries = new ArrayList<>();
        for (String row : Files.readAllLines(file, StandardCharsets.UTF_8).stream().skip(1).toList())
            if (!row.isBlank()) entries.add(parse(row));
        return entries;
    }

    /** Writes a temporary file first so a crash cannot leave a half-written venue list. */
    public synchronized void save(List<VenueCatalog.Entry> entries) throws IOException {
        Files.createDirectories(file.getParent());
        List<String> rows = new ArrayList<>(List.of(HEADER));
        for (VenueCatalog.Entry entry : entries) {
            Venue venue = entry.venue();
            rows.add(EventRepository.csv(venue.name(), entry.category(), venue.type().name(),
                    String.valueOf(venue.capacity()), venue.hourlyRate().toPlainString(),
                    venue.morningRate().toPlainString(), venue.afternoonRate().toPlainString()));
        }
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temp, rows, StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private static VenueCatalog.Entry parse(String row) throws IOException {
        List<String> fields = EventRepository.parseCsv(row);
        if (fields.size() != 7) throw new IOException("Invalid venue record.");
        try {
            Venue venue = new Venue(fields.get(0), VenueType.valueOf(fields.get(2)), Integer.parseInt(fields.get(3)),
                    new BigDecimal(fields.get(4)), new BigDecimal(fields.get(5)), new BigDecimal(fields.get(6)));
            return new VenueCatalog.Entry(venue, fields.get(1));
        } catch (RuntimeException ex) {
            throw new IOException("Invalid venue record.", ex);
        }
    }
}
