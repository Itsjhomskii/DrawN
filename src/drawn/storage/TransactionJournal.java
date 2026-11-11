package drawn.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

/** Write-ahead journal for recoverable customer financial operations. */
public final class TransactionJournal {
    private static final String HEADER = "id,kind,payload,state,updatedAt";
    private final Path file;

    public TransactionJournal(Path dataDirectory) { file = dataDirectory.resolve("transaction-journal.csv"); }

    public synchronized Entry begin(String id, String kind, List<String> payload) throws IOException {
        for (Entry entry : all()) if (entry.id().equals(id)) return entry;
        append(csv(id, kind, encode(payload), "PENDING", LocalDateTime.now().toString()));
        return new Entry(id, kind, List.copyOf(payload), "PENDING");
    }

    public synchronized void state(String id, String state) throws IOException {
        Entry current = all().stream().filter(entry -> entry.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Transaction journal entry not found."));
        append(csv(current.id(), current.kind(), encode(current.payload()), state, LocalDateTime.now().toString()));
    }

    public synchronized List<Entry> unfinished() throws IOException {
        return all().stream().filter(entry -> !entry.state().equals("COMPLETED") && !entry.state().equals("FAILED")).toList();
    }

    public synchronized List<Entry> all() throws IOException {
        java.util.LinkedHashMap<String, Entry> latest = new java.util.LinkedHashMap<>();
        List<String> rows = read();
        for (int i = 1; i < rows.size(); i++) {
            if (rows.get(i).isBlank()) continue;
            try {
                List<String> f = parse(rows.get(i));
                if (f.size() != 5) throw new IOException("Invalid transaction journal record.");
                latest.put(f.get(0), new Entry(f.get(0), f.get(1), decode(f.get(2)), f.get(3)));
            } catch (RuntimeException | IOException ex) {
                // Ignore a torn log row; the previous state row for that operation remains usable.
            }
        }
        return List.copyOf(latest.values());
    }

    private List<String> read() throws IOException {
        if (!Files.exists(file)) return new ArrayList<>();
        List<String> rows = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (!rows.isEmpty() && !rows.get(0).equals(HEADER)) throw new IOException("Unrecognized transaction journal header.");
        return rows;
    }
    private void append(String row) throws IOException {
        Files.createDirectories(file.getParent());
        if (!Files.exists(file)) {
            Files.writeString(file, HEADER + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            try(var channel=java.nio.channels.FileChannel.open(file,StandardOpenOption.WRITE,StandardOpenOption.APPEND)){
                channel.write(StandardCharsets.UTF_8.encode(row+System.lineSeparator()));channel.force(true);
            }
            return;
        }
        if(!Files.readString(file,StandardCharsets.UTF_8).endsWith("\n"))
            Files.writeString(file, System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        try(var channel=java.nio.channels.FileChannel.open(file,StandardOpenOption.WRITE,StandardOpenOption.APPEND)){
            channel.write(StandardCharsets.UTF_8.encode(row+System.lineSeparator()));channel.force(true);
        }
    }
    private static String encode(List<String> fields) {
        return Base64.getEncoder().encodeToString(String.join("\t", fields).getBytes(StandardCharsets.UTF_8));
    }
    private static List<String> decode(String value) {
        return List.of(new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8).split("\t", -1));
    }
    private static String csv(String... values) {
        return java.util.Arrays.stream(values).map(v -> "\"" + v.replace("\"", "\"\"") + "\"").collect(Collectors.joining(","));
    }
    private static List<String> parse(String line) throws IOException {
        List<String> fields = new ArrayList<>(); StringBuilder b = new StringBuilder(); boolean quoted = false;
        for (int i = 0; i < line.length(); i++) { char c = line.charAt(i);
            if (quoted && c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { b.append('"'); i++; }
            else if (c == '"') quoted = !quoted;
            else if (c == ',' && !quoted) { fields.add(b.toString()); b.setLength(0); }
            else b.append(c);
        }
        if (quoted) throw new IOException("Malformed transaction journal CSV."); fields.add(b.toString()); return fields;
    }
    public record Entry(String id, String kind, List<String> payload, String state) { }
}
