package drawn.service.admin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only record of admin decisions (who approved or declined what, and when).
 * Stored in data/admin-audit.csv, same CSV style as the other repositories.
 */
public final class AdminAuditLog {
    private static final String HEADER = "timestamp,admin,action,targetId,detail";
    private final Path file;
    private final Clock clock;

    public AdminAuditLog(Path dataDirectory, Clock clock) {
        this.file = dataDirectory.resolve("admin-audit.csv");
        this.clock = clock;
    }

    public synchronized void record(String admin, String action, String targetId, String detail) throws IOException {
        Files.createDirectories(file.getParent());
        if (!Files.exists(file)) Files.writeString(file, HEADER + System.lineSeparator(), StandardCharsets.UTF_8);
        String row = String.join(",", quote(LocalDateTime.now(clock).toString()), quote(admin), quote(action),
                quote(targetId), quote(detail)) + System.lineSeparator();
        Files.writeString(file, row, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    /** Returns raw rows (without the header), oldest first. */
    public synchronized List<String> entries() throws IOException {
        if (!Files.exists(file)) return List.of();
        List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        if (!lines.isEmpty()) lines.remove(0);
        return lines;
    }

    public record Decision(String timestamp, String admin, String action, String targetId, String detail) { }

    /** Parsed decisions, newest first; rows that are not five quoted fields are skipped. */
    public synchronized List<Decision> decisions() throws IOException {
        List<Decision> result = new ArrayList<>();
        for (String line : entries()) {
            List<String> f = parse(line);
            if (f.size() == 5) result.add(new Decision(f.get(0), f.get(1), f.get(2), f.get(3), f.get(4)));
        }
        java.util.Collections.reverse(result);
        return result;
    }

    /** Reads one row written by {@link #quote}: every field quoted, inner quotes doubled. */
    private static List<String> parse(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted && c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { field.append('"'); i++; }
            else if (c == '"') quoted = !quoted;
            else if (c == ',' && !quoted) { fields.add(field.toString()); field.setLength(0); }
            else field.append(c);
        }
        fields.add(field.toString());
        return fields;
    }

    private static String quote(String value) {
        return "\"" + (value == null ? "" : value.replace("\"", "\"\"")) + "\"";
    }
}
