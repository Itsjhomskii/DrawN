package drawn.storage;

import drawn.model.ticket.TicketType;
import drawn.model.ticket.TicketPurchaseStatus;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** CSV persistence for organizer ticket types, publication flags, and purchased tickets. */
public final class TicketRepository {
    private static final String TYPE_HEADER = "id,eventId,name,description,price,quantity,sold,salesStart,salesDeadline";
    private static final String LEGACY_TYPE_HEADER = "id,eventId,name,price,quantity,sold,salesDeadline";
    private static final String PUBLISH_HEADER = "eventId,organizerId,publishedAt";
    private static final String PURCHASE_HEADER = "id,eventId,ticketTypeId,purchaserId,quantity,amount,timestamp,status";
    private static final String LEGACY_PURCHASE_HEADER = "id,eventId,ticketTypeId,purchaserId,quantity,amount,timestamp";
    private final Path types, publications, purchases;
    public TicketRepository(Path dataDirectory) {
        types=dataDirectory.resolve("ticket-types.csv"); publications=dataDirectory.resolve("published-events.csv"); purchases=dataDirectory.resolve("ticket-purchases.csv");
    }
    public synchronized List<TicketType> types(String eventId) throws IOException {
        List<TicketType> result=new ArrayList<>(); List<String> rows=read(types,TYPE_HEADER);
        boolean legacy = !rows.isEmpty() && rows.get(0).equals(LEGACY_TYPE_HEADER);
        for(int i=1;i<rows.size();i++){if(rows.get(i).isBlank())continue; List<String> f=parse(rows.get(i)); if(f.size()!=(legacy?7:9))throw new IOException("Invalid ticket type record.");
            try {if(f.get(1).equals(eventId))result.add(legacy
                    ? new TicketType(f.get(0),f.get(1),f.get(2),new BigDecimal(f.get(3)),Integer.parseInt(f.get(4)),Integer.parseInt(f.get(5)),safeDate(f.get(6)))
                    : new TicketType(f.get(0),f.get(1),f.get(2),f.get(3),new BigDecimal(f.get(4)),Integer.parseInt(f.get(5)),Integer.parseInt(f.get(6)),safeDate(f.get(7)),safeDate(f.get(8))));}
            catch(RuntimeException ex){throw new IOException("Invalid ticket type record.",ex);}}
        return List.copyOf(result);
    }
    public synchronized void saveTypes(String eventId,List<TicketType> newTypes)throws IOException{
        List<String> rows=read(types,TYPE_HEADER); if(rows.isEmpty())rows.add(TYPE_HEADER);
        if(rows.get(0).equals(LEGACY_TYPE_HEADER)) {
            List<String> migrated=new ArrayList<>(); migrated.add(TYPE_HEADER);
            for(int i=1;i<rows.size();i++) if(!rows.get(i).isBlank()) {
                List<String> f=parse(rows.get(i));
                migrated.add(csv(f.get(0),f.get(1),f.get(2),"",f.get(3),f.get(4),f.get(5),"",f.get(6)));
            }
            rows=migrated;
        }
        rows.removeIf(line->{if(line.equals(TYPE_HEADER)||line.isBlank())return false; try{return parse(line).get(1).equals(eventId);}catch(IOException ex){throw new IllegalStateException(ex);}});
        for(TicketType t:newTypes) rows.add(csv(t.id(),t.eventId(),t.name(),t.description(),t.price().toPlainString(),Integer.toString(t.quantity()),Integer.toString(t.sold()),dateText(t.salesStart()),dateText(t.salesDeadline())));
        write(types,rows);
    }
    public synchronized void updateType(TicketType type)throws IOException{
        List<TicketType> all=types(type.eventId()); List<TicketType> changed=all.stream().map(t->t.id().equals(type.id())?type:t).toList();
        if(changed.stream().noneMatch(t->t.id().equals(type.id())))throw new IllegalArgumentException("Ticket type not found."); saveTypes(type.eventId(),changed);
    }
    public synchronized void publish(String eventId,String organizerId)throws IOException{
        List<String> rows=read(publications,PUBLISH_HEADER); if(rows.isEmpty())rows.add(PUBLISH_HEADER);
        for(int i=1;i<rows.size();i++){List<String> f=parse(rows.get(i));if(f.size()==3&&f.get(0).equals(eventId))return;}
        rows.add(csv(eventId,organizerId,LocalDateTime.now().toString()));write(publications,rows);
    }
    public synchronized boolean isPublished(String eventId)throws IOException{
        List<String> rows=read(publications,PUBLISH_HEADER);for(int i=1;i<rows.size();i++){if(rows.get(i).isBlank())continue;List<String> f=parse(rows.get(i));if(f.size()==3&&f.get(0).equals(eventId))return true;}return false;
    }
    public synchronized List<String> publishedEventIds()throws IOException{
        List<String> ids=new ArrayList<>();List<String> rows=read(publications,PUBLISH_HEADER);for(int i=1;i<rows.size();i++){if(rows.get(i).isBlank())continue;List<String> f=parse(rows.get(i));if(f.size()!=3)throw new IOException("Invalid publication record.");ids.add(f.get(0));}return List.copyOf(ids);
    }
    public synchronized void purchase(String id,String eventId,String typeId,String purchaser,int quantity,BigDecimal amount)throws IOException{
        List<String> rows=readPurchases();if(rows.isEmpty())rows.add(PURCHASE_HEADER);
        if (rows.stream().skip(1).anyMatch(row -> { try { return !row.isBlank() && parse(row).get(0).equals(id); } catch(IOException ex) { throw new IllegalStateException(ex); } })) return;
        rows.add(csv(id,eventId,typeId,purchaser,Integer.toString(quantity),amount.toPlainString(),LocalDateTime.now().toString(),TicketPurchaseStatus.ACTIVE.name()));write(purchases,rows);
    }
    public synchronized List<String> purchaseHistory(String purchaserId)throws IOException{
        List<String> result=new ArrayList<>();List<String> rows=readPurchases();
        for(int i=1;i<rows.size();i++){if(rows.get(i).isBlank())continue;List<String> f=parse(rows.get(i));if(f.get(3).equals(purchaserId))result.add(rows.get(i));}return List.copyOf(result);
    }
    public synchronized List<String> allPurchases() throws IOException {
        List<String> result = new ArrayList<>();
        List<String> rows = readPurchases();
        for (int i = 1; i < rows.size(); i++) {
            if (rows.get(i).isBlank()) continue;
            List<String> f = parse(rows.get(i));
            result.add(rows.get(i));
        }
        return List.copyOf(result);
    }
    public synchronized List<Purchase> allPurchaseRecords() throws IOException {
        List<Purchase> result = new ArrayList<>();
        for (String row : allPurchases()) {
            List<String> f = parse(row);
            try { result.add(new Purchase(f.get(0), f.get(1), f.get(2), f.get(3), Integer.parseInt(f.get(4)),
                    new BigDecimal(f.get(5)), LocalDateTime.parse(f.get(6)), TicketPurchaseStatus.valueOf(f.get(7)))); }
            catch (RuntimeException ex) { throw new IOException("Invalid ticket purchase record.", ex); }
        }
        return List.copyOf(result);
    }
    public synchronized boolean hasPurchase(String purchaseId) throws IOException {
        return allPurchases().stream().anyMatch(row -> {
            try { return parse(row).get(0).equals(purchaseId); }
            catch (IOException ex) { throw new IllegalStateException(ex); }
        });
    }
    public synchronized boolean hasSales(String eventId) throws IOException {
        return allPurchaseRecords().stream().anyMatch(p -> p.eventId().equals(eventId) && p.quantity() > 0
                && p.status() != TicketPurchaseStatus.REFUNDED && p.status() != TicketPurchaseStatus.CANCELLED
                && p.status() != TicketPurchaseStatus.FAILED);
    }
    /** Removes setup data for a failed event creation before any customer can buy a ticket. */
    public synchronized void discardUnpurchasedEvent(String eventId) throws IOException {
        if (allPurchaseRecords().stream().anyMatch(p -> p.eventId().equals(eventId)))
            throw new IllegalStateException("Cannot discard an event that has ticket purchases.");
        List<String> typeRows = read(types, TYPE_HEADER);
        if (!typeRows.isEmpty()) {
            typeRows.removeIf(row -> {
                if (row.isBlank() || row.equals(TYPE_HEADER) || row.equals(LEGACY_TYPE_HEADER)) return false;
                try { return parse(row).get(1).equals(eventId); }
                catch (IOException ex) { throw new IllegalStateException(ex); }
            });
            write(types, typeRows);
        }
        List<String> publicationRows = read(publications, PUBLISH_HEADER);
        if (!publicationRows.isEmpty()) {
            publicationRows.removeIf(row -> {
                if (row.isBlank() || row.equals(PUBLISH_HEADER)) return false;
                try { return parse(row).get(0).equals(eventId); }
                catch (IOException ex) { throw new IllegalStateException(ex); }
            });
            write(publications, publicationRows);
        }
    }
    public synchronized void updatePurchaseStatus(String purchaseId, TicketPurchaseStatus status) throws IOException {
        List<String> rows=readPurchases(); boolean changed=false;
        for(int i=1;i<rows.size();i++){if(rows.get(i).isBlank())continue;List<String> f=parse(rows.get(i));if(f.get(0).equals(purchaseId)){f.set(7,status.name());rows.set(i,csv(f.toArray(String[]::new)));changed=true;}}
        if(!changed)throw new IllegalArgumentException("Ticket purchase not found."); write(purchases,rows);
    }
    public synchronized void reconcileSoldQuantity(String eventId, String typeId) throws IOException {
        TicketType type = types(eventId).stream().filter(t -> t.id().equals(typeId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Ticket category not found."));
        int recorded = 0;
        for (String row : allPurchases()) {
            List<String> f = parse(row);
            if (f.get(1).equals(eventId) && f.get(2).equals(typeId) && !f.get(7).equals(TicketPurchaseStatus.REFUNDED.name())
                    && !f.get(7).equals(TicketPurchaseStatus.CANCELLED.name()) && !f.get(7).equals(TicketPurchaseStatus.FAILED.name()))
                recorded = Math.addExact(recorded, Integer.parseInt(f.get(4)));
        }
        int target = recorded;
        if (target != type.sold()) updateType(new TicketType(type.id(), type.eventId(), type.name(), type.description(),
                type.price(), type.quantity(), target, type.salesStart(), type.salesDeadline()));
    }
    public synchronized int purchasedCount(String purchaserId)throws IOException {
        int count=0; List<String> rows=readPurchases();
        for(int i=1;i<rows.size();i++){if(rows.get(i).isBlank())continue;List<String> f=parse(rows.get(i));if(f.get(3).equals(purchaserId)&&f.get(7).equals(TicketPurchaseStatus.ACTIVE.name()))count=Math.addExact(count,Integer.parseInt(f.get(4)));}
        return count;
    }
    private static List<String> read(Path p,String header)throws IOException{if(!Files.exists(p))return new ArrayList<>();List<String> r=Files.readAllLines(p,StandardCharsets.UTF_8);if(!r.isEmpty()&&!r.get(0).equals(header)&&!(header.equals(TYPE_HEADER)&&r.get(0).equals(LEGACY_TYPE_HEADER)))throw new IOException("Unrecognized CSV header in "+p);return r;}
    private List<String> readPurchases() throws IOException {
        if(!Files.exists(purchases))return new ArrayList<>(); List<String> raw=Files.readAllLines(purchases,StandardCharsets.UTF_8);
        if(raw.isEmpty())return raw;
        if(raw.get(0).equals(PURCHASE_HEADER))return raw;
        if(!raw.get(0).equals(LEGACY_PURCHASE_HEADER))throw new IOException("Unrecognized CSV header in "+purchases);
        List<String> migrated=new ArrayList<>();migrated.add(PURCHASE_HEADER);
        for(int i=1;i<raw.size();i++){if(raw.get(i).isBlank())continue;List<String> f=parse(raw.get(i));if(f.size()!=7)throw new IOException("Invalid ticket purchase record.");f.add(TicketPurchaseStatus.ACTIVE.name());migrated.add(csv(f.toArray(String[]::new)));}
        return migrated;
    }
    private static void write(Path p,List<String> rows)throws IOException{Files.createDirectories(p.getParent());Files.write(p,rows,StandardCharsets.UTF_8);}
    private static String csv(String...f){return java.util.Arrays.stream(f).map(v->"\""+v.replace("\"","\"\"")+"\"").collect(Collectors.joining(","));}
    private static List<String> parse(String s)throws IOException{List<String> f=new ArrayList<>();StringBuilder b=new StringBuilder();boolean q=false;for(int i=0;i<s.length();i++){char c=s.charAt(i);if(q&&c=='"'&&i+1<s.length()&&s.charAt(i+1)=='"'){b.append('"');i++;}else if(c=='"')q=!q;else if(c==','&&!q){f.add(b.toString());b.setLength(0);}else b.append(c);}if(q)throw new IOException("Malformed CSV.");f.add(b.toString());return f;}
    private static LocalDateTime safeDate(String value) {
        // Legacy rows without a usable opening time have no opening restriction (sales are open immediately).
        if (value == null || value.isBlank()) return null;
        try { return LocalDateTime.parse(value); } catch (RuntimeException ignored) { return null; }
    }
    private static String dateText(LocalDateTime value) { return value == null ? "" : value.toString(); }
    public record Purchase(String id, String eventId, String ticketTypeId, String purchaserId, int quantity,
                           BigDecimal amount, LocalDateTime timestamp, TicketPurchaseStatus status) { }
}
