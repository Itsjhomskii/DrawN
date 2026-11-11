package drawn.storage;

import drawn.model.payment.PaymentPurpose;
import drawn.model.payment.PaymentStatus;
import drawn.model.payment.PaymentTransaction;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
        boolean tornTail=false;
        for(int i=1;i<rows.size();i++) if(!rows.get(i).isBlank()) {
            List<String> fields=null;
            try { fields=parse(rows.get(i));validate(fields); }
            catch(IOException ex){if(i!=rows.size()-1)throw ex;tornTail=true;fields=null;}
            catch(RuntimeException ex){if(i!=rows.size()-1)throw new IOException("Invalid payment transaction record.",ex);tornTail=true;fields=null;}
            if(fields!=null&&fields.size()==7&&fields.get(0).equals(tx.id()))throw new IllegalArgumentException("Duplicate payment transaction ID.");
        }
        String row=csv(tx.id(), tx.customerId(), tx.referenceId(), tx.purpose().name(), tx.amount().toPlainString(),
                tx.status().name(), tx.timestamp().toString())+System.lineSeparator();
        Files.createDirectories(file.getParent());
        if(!Files.exists(file))Files.writeString(file,HEADER+System.lineSeparator(),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        else if(tornTail||!Files.readString(file,StandardCharsets.UTF_8).endsWith("\n"))
            Files.writeString(file,System.lineSeparator(),StandardCharsets.UTF_8,StandardOpenOption.APPEND);
        try(var channel=java.nio.channels.FileChannel.open(file,StandardOpenOption.WRITE,StandardOpenOption.APPEND)){
            channel.write(StandardCharsets.UTF_8.encode(row));channel.force(true);
        }
    }
    public synchronized List<PaymentTransaction> findByCustomer(String customerId) throws IOException {
        List<PaymentTransaction> result = new ArrayList<>(); List<String> rows = read();
        for (int i = 1; i < rows.size(); i++) {
            if (rows.get(i).isBlank()) continue;
            List<String> f;
            try { f=parse(rows.get(i)); } catch(IOException ex){if(i==rows.size()-1)continue;throw ex;}
            if (f.size() != 7) { if(i==rows.size()-1)continue; throw new IOException("Invalid payment transaction record."); }
            if (!f.get(1).equals(customerId)) continue;
            try { result.add(new PaymentTransaction(f.get(0), f.get(1), f.get(2), PaymentPurpose.valueOf(f.get(3)),
                    new BigDecimal(f.get(4)), PaymentStatus.valueOf(f.get(5)), LocalDateTime.parse(f.get(6)))); }
            catch (RuntimeException ex) { if(i==rows.size()-1)continue; throw new IOException("Invalid payment transaction record.", ex); }
        }
        return List.copyOf(result);
    }
    public synchronized List<PaymentTransaction> findAll() throws IOException {
        List<PaymentTransaction> result = new ArrayList<>();
        List<String> rows = read();
        for (int i = 1; i < rows.size(); i++) {
            if (rows.get(i).isBlank()) continue;
            List<String> f;
            try { f=parse(rows.get(i)); } catch(IOException ex){if(i==rows.size()-1)continue;throw ex;}
            if (f.size() != 7) { if(i==rows.size()-1)continue; throw new IOException("Invalid payment transaction record."); }
            try { result.add(new PaymentTransaction(f.get(0), f.get(1), f.get(2), PaymentPurpose.valueOf(f.get(3)),
                    new BigDecimal(f.get(4)), PaymentStatus.valueOf(f.get(5)), LocalDateTime.parse(f.get(6)))); }
            catch (RuntimeException ex) { if(i==rows.size()-1)continue; throw new IOException("Invalid payment transaction record.", ex); }
        }
        return List.copyOf(result);
    }
    public synchronized boolean hasTransaction(String id) throws IOException {
        return findAll().stream().anyMatch(tx -> tx.id().equals(id));
    }
    public synchronized boolean hasTransaction(String customerId, String referenceId, PaymentPurpose purpose,
                                               PaymentStatus status) throws IOException {
        return findAll().stream().anyMatch(tx -> tx.customerId().equals(customerId)
                && tx.referenceId().equals(referenceId) && tx.purpose() == purpose && tx.status() == status);
    }
    private List<String> read() throws IOException {
        if (!Files.exists(file)) return new ArrayList<>();
        List<String> rows = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (!rows.isEmpty() && !rows.get(0).equals(HEADER)) throw new IOException("Unrecognized payment CSV header.");
        return rows;
    }
    private static void validate(List<String> f) {
        if(f.size()!=7)throw new IllegalArgumentException("Invalid payment transaction record.");
        PaymentPurpose.valueOf(f.get(3));new BigDecimal(f.get(4));PaymentStatus.valueOf(f.get(5));LocalDateTime.parse(f.get(6));
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
