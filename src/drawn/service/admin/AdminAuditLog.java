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

    private static String quote(String value) {
        return "\"" + (value == null ? "" : value.replace("\"", "\"\"")) + "\"";
    }
}
