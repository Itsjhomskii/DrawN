package drawn.model.event;

import drawn.model.payment.PaymentStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

public record VenueReservation(String id, String eventId, String organizerId, String venueName,
                              LocalDate date, LocalTime startTime, LocalTime endTime,
                              ReservationPricing pricing, BigDecimal cost, BookingStatus status,
                              PaymentStatus paymentStatus, String declineReason) {
    public VenueReservation {
        id = required(id, "Reservation ID");
        eventId = required(eventId, "Event ID");
        organizerId = required(organizerId, "Organizer ID");
        venueName = required(venueName, "Venue name");
        Objects.requireNonNull(date, "Date cannot be null");
        Objects.requireNonNull(startTime, "Start time cannot be null");
        Objects.requireNonNull(endTime, "End time cannot be null");
        if (!endTime.isAfter(startTime)) throw new IllegalArgumentException("End time must be later than start time.");
        Objects.requireNonNull(pricing, "Pricing option cannot be null");
        Objects.requireNonNull(cost, "Reservation cost cannot be null");
        if (cost.signum() < 0 || cost.scale() > 2) throw new IllegalArgumentException("Reservation cost is invalid.");
        Objects.requireNonNull(status, "Reservation status cannot be null");
        Objects.requireNonNull(paymentStatus, "Payment status cannot be null");
        declineReason = declineReason == null ? "" : declineReason.trim();
    }

    private static String required(String value, String label) {
        Objects.requireNonNull(value, label + " cannot be null");
        String result = value.trim();
        if (result.isEmpty()) throw new IllegalArgumentException(label + " cannot be blank.");
        return result;
    }

    public VenueReservation withStatus(BookingStatus next, String reason) {
        return new VenueReservation(id, eventId, organizerId, venueName, date, startTime, endTime,
                pricing, cost, next, paymentStatus, reason);
    }

    public VenueReservation withPayment(PaymentStatus next) {
        return new VenueReservation(id, eventId, organizerId, venueName, date, startTime, endTime,
                pricing, cost, status, next, declineReason);
    }
}
