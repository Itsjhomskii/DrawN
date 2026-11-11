package drawn.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Stores organizer-provided access keywords for private events. */
public final class PrivateEventAccessRepository {
    private static final String HEADER = "eventId,accessKeyword";
    private final Path file;

    public PrivateEventAccessRepository(Path dataDirectory) {
        file = dataDirectory.resolve("private-event-access.csv");
    }

    public synchronized void save(String eventId, String keyword) throws IOException {
        if (keyword == null || keyword.isBlank()) throw new IllegalArgumentException("Private event keyword is required.");
        List<String> rows = read();
        if (rows.isEmpty()) rows.add(HEADER);
        rows.removeIf(row -> row.startsWith(eventId + ","));
        rows.add(eventId + "," + keyword.trim());
        Files.createDirectories(file.getParent());
        Files.write(file, rows, StandardCharsets.UTF_8);
    }

    public synchronized boolean matches(String eventId, String keyword) throws IOException {
        if (keyword == null || keyword.isBlank()) return false;
        for (String row : read()) {
            if (row.equals(HEADER) || row.isBlank()) continue;
            String[] fields = row.split(",", 2);
            if (fields.length == 2 && fields[0].equals(eventId)) return fields[1].equals(keyword.trim());
        }
        return false;
    }

    private List<String> read() throws IOException {
        if (!Files.exists(file)) return new ArrayList<>();
        List<String> rows = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (!rows.isEmpty() && !rows.get(0).equals(HEADER)) throw new IOException("Unrecognized private event access file.");
        return new ArrayList<>(rows);
    }
}
