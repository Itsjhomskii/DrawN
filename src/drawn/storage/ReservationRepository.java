package drawn.storage;

import drawn.model.event.BookingStatus;
import drawn.model.event.ReservationPricing;
import drawn.model.event.VenueReservation;
import drawn.model.payment.PaymentStatus;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Additive CSV storage for reservation workflow; event-submission CSV remains unchanged. */
public final class ReservationRepository {
    private static final String HEADER = "id,eventId,organizerId,venueName,date,startTime,endTime,pricing,cost,status,paymentStatus,declineReason";
    private final Path file;

    public ReservationRepository(Path dataDirectory) { file = dataDirectory.resolve("venue-reservations.csv"); }

    public synchronized void save(VenueReservation reservation) throws IOException {
        List<String> rows = read();
        if (rows.isEmpty()) rows.add(HEADER);
        if (find(rows, reservation.id()) >= 0) throw new IllegalArgumentException("Duplicate reservation ID.");
        rows.add(encode(reservation));
        write(rows);
    }

    public synchronized List<VenueReservation> findAll() throws IOException {
        List<String> rows = read();
        List<VenueReservation> result = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            if (rows.get(i).isBlank()) continue;
            List<String> f = parse(rows.get(i));
            if (f.size() != 12) throw new IOException("Invalid venue reservation record.");
            try {
                result.add(new VenueReservation(f.get(0), f.get(1), f.get(2), f.get(3), LocalDate.parse(f.get(4)),
                        LocalTime.parse(f.get(5)), LocalTime.parse(f.get(6)), ReservationPricing.valueOf(f.get(7)),
                        new BigDecimal(f.get(8)), BookingStatus.valueOf(f.get(9)), PaymentStatus.valueOf(f.get(10)), f.get(11)));
            } catch (RuntimeException ex) { throw new IOException("Invalid venue reservation record.", ex); }
        }
        return List.copyOf(result);
    }

    public synchronized VenueReservation findById(String id) throws IOException {
        return findAll().stream().filter(r -> r.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Reservation not found."));
    }

    public synchronized void update(VenueReservation reservation) throws IOException {
        List<String> rows = read();
        int index = find(rows, reservation.id());
        if (index < 0) throw new IllegalArgumentException("Reservation not found.");
        rows.set(index, encode(reservation));
        write(rows);
    }

    private List<String> read() throws IOException {
        if (!Files.exists(file)) return new ArrayList<>();
        List<String> rows = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (!rows.isEmpty() && !rows.get(0).equals(HEADER)) throw new IOException("Unrecognized CSV header in " + file);
        return rows;
    }
    private void write(List<String> rows) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, rows, StandardCharsets.UTF_8);
    }
    private static int find(List<String> rows, String id) throws IOException {
        for (int i = 1; i < rows.size(); i++) if (!rows.get(i).isBlank() && parse(rows.get(i)).get(0).equals(id)) return i;
        return -1;
    }
    private static String encode(VenueReservation r) {
        return csv(r.id(), r.eventId(), r.organizerId(), r.venueName(), r.date().toString(), r.startTime().toString(),
                r.endTime().toString(), r.pricing().name(), r.cost().toPlainString(), r.status().name(),
                r.paymentStatus().name(), r.declineReason());
    }
    private static String csv(String... values) {
        return java.util.Arrays.stream(values).map(v -> "\"" + v.replace("\"", "\"\"") + "\"")
                .collect(Collectors.joining(","));
    }
    private static List<String> parse(String line) throws IOException {
        List<String> out = new ArrayList<>(); StringBuilder value = new StringBuilder(); boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted && c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { value.append('"'); i++; }
            else if (c == '"') quoted = !quoted;
            else if (c == ',' && !quoted) { out.add(value.toString()); value.setLength(0); }
            else value.append(c);
        }
        if (quoted) throw new IOException("Malformed CSV quoting.");
        out.add(value.toString()); return out;
    }
}
