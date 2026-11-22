package drawn.storage;

import drawn.model.event.ExtensionRequestStatus;
import drawn.model.event.VenueExtensionRequest;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** CSV persistence for venue extension requests. */
public final class VenueExtensionRepository {
    private static final String HEADER = "id,reservationId,eventId,organizerId,requestedEndTime,additionalCost,requestedAt,status,decisionReason";
    private final Path file;

    public VenueExtensionRepository(Path dataDirectory) {
        file = dataDirectory.resolve("venue-extension-requests.csv");
    }

    public synchronized void save(VenueExtensionRequest request) throws IOException {
        List<String> rows = read();
        if (rows.isEmpty()) rows.add(HEADER);
        if (rows.stream().skip(1).filter(row -> !row.isBlank()).anyMatch(row -> {
            try { return parse(row).get(0).equals(request.id()); }
            catch (IOException ex) { throw new IllegalStateException(ex); }
        })) throw new IllegalArgumentException("Duplicate extension request ID.");
        rows.add(encode(request));
        write(rows);
    }

    public synchronized List<VenueExtensionRequest> findAll() throws IOException {
        List<String> rows = read();
        List<VenueExtensionRequest> result = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            if (rows.get(i).isBlank()) continue;
            List<String> f = parse(rows.get(i));
            if (f.size() != 9) throw new IOException("Invalid venue extension request record.");
            try {
                result.add(new VenueExtensionRequest(f.get(0), f.get(1), f.get(2), f.get(3),
                        LocalTime.parse(f.get(4)), new BigDecimal(f.get(5)), LocalDateTime.parse(f.get(6)),
                        ExtensionRequestStatus.valueOf(f.get(7)), f.get(8)));
            } catch (RuntimeException ex) {
                throw new IOException("Invalid venue extension request record.", ex);
            }
        }
        return List.copyOf(result);
    }

    public synchronized void update(VenueExtensionRequest request) throws IOException {
        List<String> rows = read();
        boolean found = false;
        for (int i = 1; i < rows.size(); i++) {
            if (rows.get(i).isBlank()) continue;
            if (parse(rows.get(i)).get(0).equals(request.id())) {
                rows.set(i, encode(request));
                found = true;
                break;
            }
        }
        if (!found) throw new IllegalArgumentException("Venue extension request not found.");
        write(rows);
    }

    public synchronized boolean hasPendingForEvent(String eventId) throws IOException {
        return findAll().stream().anyMatch(request -> request.eventId().equals(eventId)
                && request.status() == ExtensionRequestStatus.PENDING);
    }

    public synchronized List<VenueExtensionRequest> forEvent(String eventId) throws IOException {
        return findAll().stream().filter(request -> request.eventId().equals(eventId)).toList();
    }

    public synchronized void cancelPendingForEvent(String eventId) throws IOException {
        List<String> rows = read();
        boolean changed = false;
        for (int i = 1; i < rows.size(); i++) {
            if (rows.get(i).isBlank()) continue;
            List<String> f = parse(rows.get(i));
            if (f.size() == 9 && f.get(2).equals(eventId) && f.get(7).equals(ExtensionRequestStatus.PENDING.name())) {
                f.set(7, ExtensionRequestStatus.CANCELLED.name());
                f.set(8, "Event cancelled by organizer");
                rows.set(i, csv(f.toArray(String[]::new)));
                changed = true;
            }
        }
        if (changed) write(rows);
    }

    private List<String> read() throws IOException {
        if (!Files.exists(file)) return new ArrayList<>();
        List<String> rows = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (!rows.isEmpty() && !rows.get(0).equals(HEADER))
            throw new IOException("Unrecognized CSV header in " + file);
        return rows;
    }

    private void write(List<String> rows) throws IOException {
        AtomicCsvFile.write(file, rows);
    }

    private static String encode(VenueExtensionRequest request) {
        return csv(request.id(), request.reservationId(), request.eventId(), request.organizerId(),
                request.requestedEndTime().toString(), request.additionalCost().toPlainString(),
                request.requestedAt().toString(), request.status().name(), request.decisionReason());
    }

    private static String csv(String... values) {
        return java.util.Arrays.stream(values).map(v -> "\"" + v.replace("\"", "\"\"") + "\"")
                .collect(Collectors.joining(","));
    }

    private static List<String> parse(String line) throws IOException {
        List<String> out = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted && c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { value.append('"'); i++; }
            else if (c == '"') quoted = !quoted;
            else if (c == ',' && !quoted) { out.add(value.toString()); value.setLength(0); }
            else value.append(c);
        }
        if (quoted) throw new IOException("Malformed CSV quoting.");
        out.add(value.toString());
        return out;
    }
}
