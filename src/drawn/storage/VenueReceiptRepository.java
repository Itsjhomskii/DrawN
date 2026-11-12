package drawn.storage;

import drawn.model.event.ReservationPricing;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.stream.Collectors;

/** Append-only CSV receipts for venue reservation quotes, payments, and cancellations. */
public final class VenueReceiptRepository {
    private static final String HEADER = "receiptId,reservationId,customerId,venueName,eventPurpose,date,startTime,endTime,pricing,durationHours,rate,venueFee,serviceFee,total,status,createdAt";
    private final Path file;

    public VenueReceiptRepository(Path dataDirectory) { file = dataDirectory.resolve("venue-receipts.csv"); }

    public synchronized void save(Receipt receipt) throws IOException {
        Files.createDirectories(file.getParent());
        if (!Files.exists(file)) Files.writeString(file, HEADER + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        else if (!Files.readString(file, StandardCharsets.UTF_8).endsWith("\n"))
            Files.writeString(file, System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        String row = csv(receipt.id(), receipt.reservationId(), receipt.customerId(), receipt.venueName(),
                receipt.eventPurpose(), receipt.date().toString(), receipt.startTime().toString(), receipt.endTime().toString(),
                receipt.pricing().name(), receipt.durationHours().toPlainString(), receipt.rate().toPlainString(), receipt.venueFee().toPlainString(),
                receipt.serviceFee().toPlainString(), receipt.total().toPlainString(), receipt.status(), receipt.createdAt().toString())
                + System.lineSeparator();
        try (var channel = java.nio.channels.FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            channel.write(StandardCharsets.UTF_8.encode(row));
            channel.force(true);
        }
    }

    private static String csv(String... values) {
        return Arrays.stream(values).map(value -> "\"" + value.replace("\"", "\"\"") + "\"")
                .collect(Collectors.joining(","));
    }

    public record Receipt(String id, String reservationId, String customerId, String venueName, String eventPurpose,
                          LocalDate date, LocalTime startTime, LocalTime endTime, ReservationPricing pricing,
                          BigDecimal durationHours, BigDecimal rate, BigDecimal venueFee, BigDecimal serviceFee, BigDecimal total,
                          String status, LocalDateTime createdAt) { }
}
