package drawn.storage;

import drawn.model.ticket.TicketType;
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
    private static final String TYPE_HEADER = "id,eventId,name,price,quantity,sold,salesDeadline";
    private static final String PUBLISH_HEADER = "eventId,organizerId,publishedAt";
    private static final String PURCHASE_HEADER = "id,eventId,ticketTypeId,purchaserId,quantity,amount,timestamp";
    private final Path types, publications, purchases;
    public TicketRepository(Path dataDirectory) {
        types=dataDirectory.resolve("ticket-types.csv"); publications=dataDirectory.resolve("published-events.csv"); purchases=dataDirectory.resolve("ticket-purchases.csv");
    }
    public synchronized List<TicketType> types(String eventId) throws IOException {
        List<TicketType> result=new ArrayList<>(); List<String> rows=read(types,TYPE_HEADER);
        for(int i=1;i<rows.size();i++){if(rows.get(i).isBlank())continue; List<String> f=parse(rows.get(i)); if(f.size()!=7)throw new IOException("Invalid ticket type record.");
            try {if(f.get(1).equals(eventId))result.add(new TicketType(f.get(0),f.get(1),f.get(2),new BigDecimal(f.get(3)),Integer.parseInt(f.get(4)),Integer.parseInt(f.get(5)),LocalDateTime.parse(f.get(6))));}
            catch(RuntimeException ex){throw new IOException("Invalid ticket type record.",ex);}}
        return List.copyOf(result);
    }
    public synchronized void saveTypes(String eventId,List<TicketType> newTypes)throws IOException{
        List<String> rows=read(types,TYPE_HEADER); if(rows.isEmpty())rows.add(TYPE_HEADER);
        rows.removeIf(line->{if(line.equals(TYPE_HEADER)||line.isBlank())return false; try{return parse(line).get(1).equals(eventId);}catch(IOException ex){throw new IllegalStateException(ex);}});
        newTypes.forEach(t->rows.add(csv(t.id(),t.eventId(),t.name(),t.price().toPlainString(),Integer.toString(t.quantity()),Integer.toString(t.sold()),t.salesDeadline().toString())));
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
        List<String> rows=read(purchases,PURCHASE_HEADER);if(rows.isEmpty())rows.add(PURCHASE_HEADER);
        rows.add(csv(id,eventId,typeId,purchaser,Integer.toString(quantity),amount.toPlainString(),LocalDateTime.now().toString()));write(purchases,rows);
    }
    public synchronized List<String> purchaseHistory(String purchaserId)throws IOException{
        List<String> result=new ArrayList<>();List<String> rows=read(purchases,PURCHASE_HEADER);
        for(int i=1;i<rows.size();i++){if(rows.get(i).isBlank())continue;List<String> f=parse(rows.get(i));if(f.size()!=7)throw new IOException("Invalid ticket purchase record.");if(f.get(3).equals(purchaserId))result.add(rows.get(i));}return List.copyOf(result);
    }
    private static List<String> read(Path p,String header)throws IOException{if(!Files.exists(p))return new ArrayList<>();List<String> r=Files.readAllLines(p,StandardCharsets.UTF_8);if(!r.isEmpty()&&!r.get(0).equals(header))throw new IOException("Unrecognized CSV header in "+p);return r;}
    private static void write(Path p,List<String> rows)throws IOException{Files.createDirectories(p.getParent());Files.write(p,rows,StandardCharsets.UTF_8);}
    private static String csv(String...f){return java.util.Arrays.stream(f).map(v->"\""+v.replace("\"","\"\"")+"\"").collect(Collectors.joining(","));}
    private static List<String> parse(String s)throws IOException{List<String> f=new ArrayList<>();StringBuilder b=new StringBuilder();boolean q=false;for(int i=0;i<s.length();i++){char c=s.charAt(i);if(q&&c=='"'&&i+1<s.length()&&s.charAt(i+1)=='"'){b.append('"');i++;}else if(c=='"')q=!q;else if(c==','&&!q){f.add(b.toString());b.setLength(0);}else b.append(c);}if(q)throw new IOException("Malformed CSV.");f.add(b.toString());return f;}
}
