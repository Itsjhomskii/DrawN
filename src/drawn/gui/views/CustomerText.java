package drawn.gui.views;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import drawn.model.event.ReservationPricing;
import drawn.model.event.Event;
import drawn.model.event.EventApprovalStatus;
import drawn.model.event.BookingStatus;
import drawn.model.event.VenueReservation;
import drawn.model.payment.PaymentStatus;

/** Small presentation helpers for customer-facing enum and time values. */
final class CustomerText {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH);

    private CustomerText() { }

    static String words(Object value) {
        if (value == null) return "Not provided";
        String text = value.toString().replace('_', ' ').trim().toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(text.length());
        boolean capitalize = true;
        for (char ch : text.toCharArray()) {
            result.append(capitalize && Character.isLetter(ch) ? Character.toUpperCase(ch) : ch);
            capitalize = Character.isWhitespace(ch);
        }
        return result.toString();
    }

    static String time(LocalTime time) { return TIME.format(time); }

    static String bookingStatus(BookingStatus status) {
        return status == BookingStatus.RESERVED || status == BookingStatus.PENDING_PAYMENT
                ? "Pending" : words(status);
    }

    static String reservationSchedule(VenueReservation reservation) {
        if (reservation.pricing() == ReservationPricing.HOURLY)
            return reservation.date() + " • " + time(reservation.startTime()) + "–" + time(reservation.endTime())
                    + " • " + words(reservation.pricing());
        return reservation.date() + " • Half day • " + words(reservation.pricing()).replaceFirst("(?i)^half day ", "");
    }

    static String reservationStatus(VenueReservation reservation, Event linkedEvent) {
        if (reservation.status() == BookingStatus.CANCELLED || reservation.status() == BookingStatus.EXPIRED)
            return "Cancelled";
        if (reservation.status() == BookingStatus.DECLINED) return "Declined";
        if (linkedEvent != null) {
            if (linkedEvent.approvalStatus() == EventApprovalStatus.PUBLISHED) return "Published";
            if (linkedEvent.approvalStatus() == EventApprovalStatus.CANCELLED
                    || linkedEvent.approvalStatus() == EventApprovalStatus.CANCELLATION_PENDING) return "Cancelled";
            if (linkedEvent.approvalStatus() == EventApprovalStatus.REJECTED) return "Declined";
        }
        if (reservation.status() == BookingStatus.APPROVED || reservation.status() == BookingStatus.CONFIRMED) return "Approved";
        if (reservation.paymentStatus() == PaymentStatus.PAID || reservation.paymentStatus() == PaymentStatus.VERIFIED
                || reservation.paymentStatus() == PaymentStatus.PENDING_VERIFICATION) return "Pending";
        return "Pending";
    }

    static String eventStatus(Event event) {
        return switch (event.approvalStatus()) {
            case PUBLISHED -> "Published";
            case APPROVED -> "Approved";
            case CANCELLED -> "Cancelled";
            case CANCELLATION_PENDING -> "Cancellation Pending";
            case REJECTED -> "Declined";
            default -> "Pending";
        };
    }
}
