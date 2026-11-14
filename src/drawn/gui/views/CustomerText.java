package drawn.gui.views;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import drawn.model.event.ReservationPricing;
import drawn.model.event.VenueReservation;

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

    static String reservationSchedule(VenueReservation reservation) {
        if (reservation.pricing() == ReservationPricing.HOURLY)
            return reservation.date() + " • " + time(reservation.startTime()) + "–" + time(reservation.endTime())
                    + " • " + words(reservation.pricing());
        return reservation.date() + " • Half day • " + words(reservation.pricing()).replaceFirst("(?i)^half day ", "");
    }
}
