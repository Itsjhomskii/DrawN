package drawn.storage;

import drawn.model.event.Event;
import drawn.model.event.EventApprovalStatus;
import drawn.model.event.EventPurpose;
import drawn.model.event.EventType;
import drawn.model.event.EventVisibility;
import drawn.model.event.TicketCategory;
import drawn.model.event.Venue;
import drawn.model.event.VenueType;
import drawn.model.event.VenueCatalog;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** CSV persistence for customer event submissions and their ticket categories. */
public final class EventRepository {
    private static final String EVENT_HEADER = "id,organizerId,name,description,eventType,purpose,visibility,date,startTime,endTime,expectedAttendance,venueName,venueType,approvalStatus,submittedAt";
    private static final String CATEGORY_HEADER = "eventId,name,price,quantity";

    private final Path eventsFile;
    private final Path categoriesFile;

    public EventRepository(Path dataDirectory) {
        eventsFile = dataDirectory.resolve("event-submissions.csv");
        categoriesFile = dataDirectory.resolve("event-ticket-categories.csv");
    }

    public synchronized void save(Event event) throws IOException {
        List<String> previousEvents = readRecords(eventsFile, EVENT_HEADER);
        List<String> previousCategories = readRecords(categoriesFile, CATEGORY_HEADER);
        List<String> eventLines = new ArrayList<>(previousEvents);
        List<String> categoryLines = new ArrayList<>(previousCategories);
        if (eventLines.isEmpty()) eventLines.add(EVENT_HEADER);
        if (categoryLines.isEmpty()) categoryLines.add(CATEGORY_HEADER);
        eventLines.add(csv(event.id(), event.organizerId(), event.name(), event.description(), event.type().name(),
                event.purpose().name(), event.visibility().name(), event.date().toString(), event.startTime().toString(),
                event.endTime().toString(), Integer.toString(event.expectedAttendance()), event.venue().name(),
                event.venue().type().name(), event.approvalStatus().name(), event.submittedAt().toString()));
        for (TicketCategory category : event.ticketCategories()) {
            categoryLines.add(csv(event.id(), category.name(), category.price().toPlainString(),
                    Integer.toString(category.quantity())));
        }
        try {
            writeRecords(eventsFile, eventLines);
            writeRecords(categoriesFile, categoryLines);
        } catch (IOException ex) {
            restore(eventsFile, previousEvents);
            restore(categoriesFile, previousCategories);
            throw ex;
        }
    }

    public synchronized List<Event> findAll() throws IOException {
        Map<String, List<TicketCategory>> categoriesByEvent = new HashMap<>();
        List<String> categoryRows = readRecords(categoriesFile, CATEGORY_HEADER);
        for (int i = 1; i < categoryRows.size(); i++) {
            if (categoryRows.get(i).isBlank()) continue;
            List<String> fields = parseCsv(categoryRows.get(i));
            if (fields.size() != 4) throw new IOException("Invalid event ticket category record.");
            try {
                TicketCategory category = new TicketCategory(fields.get(1), new BigDecimal(fields.get(2)),
                        Integer.parseInt(fields.get(3)));
                categoriesByEvent.computeIfAbsent(fields.get(0), ignored -> new ArrayList<>()).add(category);
            } catch (RuntimeException ex) {
                throw new IOException("Invalid event ticket category record.", ex);
            }
        }

        List<String> eventRows = readRecords(eventsFile, EVENT_HEADER);
        List<Event> events = new ArrayList<>();
        for (int i = 1; i < eventRows.size(); i++) {
            if (eventRows.get(i).isBlank()) continue;
            List<String> fields = parseCsv(eventRows.get(i));
            if (fields.size() != 15) throw new IOException("Invalid event submission record.");
            try {
                Venue venue = VenueCatalog.all().stream().filter(candidate -> candidate.name().equals(fields.get(11))
                        && candidate.type() == VenueType.valueOf(fields.get(12))).findFirst()
                        .orElseGet(() -> new Venue(fields.get(11), VenueType.valueOf(fields.get(12))));
                events.add(new Event(fields.get(0), fields.get(1), fields.get(2), fields.get(3),
                        EventType.valueOf(fields.get(4)), EventPurpose.valueOf(fields.get(5)),
                        EventVisibility.valueOf(fields.get(6)), LocalDate.parse(fields.get(7)),
                        LocalTime.parse(fields.get(8)), LocalTime.parse(fields.get(9)),
                        Integer.parseInt(fields.get(10)), venue,
                        EventApprovalStatus.valueOf(fields.get(13)), LocalDateTime.parse(fields.get(14)),
                        categoriesByEvent.getOrDefault(fields.get(0), List.of())));
            } catch (RuntimeException ex) {
                throw new IOException("Invalid event submission record.", ex);
            }
        }
        return List.copyOf(events);
    }

    public synchronized List<Event> findByOrganizer(String organizerId) throws IOException {
        return findAll().stream().filter(event -> event.organizerId().equals(organizerId)).toList();
    }

    public synchronized List<Event> findPublicApproved() throws IOException {
        return findAll().stream().filter(event -> event.visibility() == EventVisibility.PUBLIC
                && event.approvalStatus() == EventApprovalStatus.APPROVED).toList();
    }

    private static List<String> readRecords(Path file, String expectedHeader) throws IOException {
        if (!Files.exists(file)) return new ArrayList<>();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (!lines.isEmpty() && !lines.get(0).equals(expectedHeader)) {
            throw new IOException("Unrecognized CSV header in " + file);
        }
        return lines;
    }

    private static void writeRecords(Path file, List<String> lines) throws IOException {
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    private static void restore(Path file, List<String> content) throws IOException {
        if (content.isEmpty()) Files.deleteIfExists(file);
        else writeRecords(file, content);
    }

    private static String csv(String... fields) {
        return java.util.Arrays.stream(fields)
                .map(value -> "\"" + value.replace("\"", "\"\"") + "\"")
                .collect(Collectors.joining(","));
    }

    private static List<String> parseCsv(String line) throws IOException {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted && ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                field.append('"');
                i++;
            } else if (ch == '"') {
                quoted = !quoted;
            } else if (ch == ',' && !quoted) {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(ch);
            }
        }
        if (quoted) throw new IOException("Malformed CSV quoting.");
        fields.add(field.toString());
        return fields;
    }
}
