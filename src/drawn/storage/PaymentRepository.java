package drawn.storage;

import drawn.model.payment.PaymentPurpose;
import drawn.model.payment.PaymentStatus;
import drawn.model.payment.PaymentTransaction;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Append-only payment ledger; reversals are additional records. */
public final class PaymentRepository {
    private static final String HEADER = "id,customerId,referenceId,purpose,amount,status,timestamp";
    private final Path file;
    public PaymentRepository(Path dataDirectory) { file = dataDirectory.resolve("payment-transactions.csv"); }
    public synchronized void append(PaymentTransaction tx) throws IOException {
        List<String> rows = read();
        if (rows.isEmpty()) rows.add(HEADER);
        if (rows.stream().skip(1).anyMatch(line -> { try { return parse(line).get(0).equals(tx.id()); }
            catch (IOException ex) { throw new IllegalStateException(ex); } }))
            throw new IllegalArgumentException("Duplicate payment transaction ID.");
        rows.add(csv(tx.id(), tx.customerId(), tx.referenceId(), tx.purpose().name(), tx.amount().toPlainString(),
                tx.status().name(), tx.timestamp().toString()));
        Files.createDirectories(file.getParent()); Files.write(file, rows, StandardCharsets.UTF_8);
    }
    public synchronized List<PaymentTransaction> findByCustomer(String customerId) throws IOException {
        List<PaymentTransaction> result = new ArrayList<>(); List<String> rows = read();
        for (int i = 1; i < rows.size(); i++) {
            if (rows.get(i).isBlank()) continue;
            List<String> f = parse(rows.get(i));
            if (f.size() != 7) throw new IOException("Invalid payment transaction record.");
            if (!f.get(1).equals(customerId)) continue;
            try { result.add(new PaymentTransaction(f.get(0), f.get(1), f.get(2), PaymentPurpose.valueOf(f.get(3)),
                    new BigDecimal(f.get(4)), PaymentStatus.valueOf(f.get(5)), LocalDateTime.parse(f.get(6)))); }
            catch (RuntimeException ex) { throw new IOException("Invalid payment transaction record.", ex); }
        }
        return List.copyOf(result);
    }
    private List<String> read() throws IOException {
        if (!Files.exists(file)) return new ArrayList<>();
        List<String> rows = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (!rows.isEmpty() && !rows.get(0).equals(HEADER)) throw new IOException("Unrecognized payment CSV header.");
        return rows;
    }
    private static String csv(String... f) { return java.util.Arrays.stream(f).map(v -> "\"" + v.replace("\"", "\"\"") + "\"").collect(Collectors.joining(",")); }
    private static List<String> parse(String s) throws IOException {
        List<String> f = new ArrayList<>(); StringBuilder b = new StringBuilder(); boolean q = false;
        for (int i = 0; i < s.length(); i++) { char c = s.charAt(i);
            if (q && c == '"' && i + 1 < s.length() && s.charAt(i + 1) == '"') { b.append('"'); i++; }
            else if (c == '"') q = !q; else if (c == ',' && !q) { f.add(b.toString()); b.setLength(0); }
            else b.append(c);
        }
        if (q) throw new IOException("Malformed payment CSV."); f.add(b.toString()); return f;
    }
}
