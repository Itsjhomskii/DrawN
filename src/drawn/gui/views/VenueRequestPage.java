package drawn.gui.views;

import drawn.model.event.EventPurpose;
import drawn.model.event.ReservationPricing;
import drawn.model.event.Venue;
import drawn.model.event.VenueCatalog;
import drawn.model.event.VenueReservation;
import drawn.service.customer.CustomerWorkflowService;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DateCell;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.util.StringConverter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/** Venue request form with quote, wallet payment and confirmation (console requestVenueReservation). */
final class VenueRequestPage {
    private final MainShell shell;
    private final ComboBox<EventPurpose> purpose = new ComboBox<>();
    private final ComboBox<Venue> venue = new ComboBox<>();
    private final DatePicker date = new DatePicker();
    private final TextField start = Ui.field("HH:mm, e.g. 08:00");
    private final TextField end = Ui.field("HH:mm, e.g. 12:00");
    private final ComboBox<ReservationPricing> pricing = new ComboBox<>();
    private final Label amountDue = Ui.muted("Amount due: —");
    private final Label venueInfo = Ui.muted("");

    VenueRequestPage(MainShell shell) { this.shell = shell; }

    Node view() {
        purpose.setId("venue-purpose");
        venue.setId("venue-choice");
        date.setId("venue-date");
        start.setId("venue-start");
        end.setId("venue-end");
        pricing.setId("venue-pricing");

        purpose.getItems().setAll(EventPurpose.values());
        purpose.setConverter(converter(CustomerWorkflowService::displayPurpose));
        venue.setConverter(converter(Venue::name));
        pricing.getItems().setAll(ReservationPricing.values());
        pricing.setConverter(converter(Ui::label));
        date.getStyleClass().add("text-input");
        date.setDayCellFactory(picker -> new DateCell() {
            @Override public void updateItem(LocalDate item, boolean empty) {
                super.updateItem(item, empty);
                setDisable(empty || item.isBefore(LocalDate.now()));
            }
        });

        purpose.valueProperty().addListener((o, a, chosen) -> {
            venue.getItems().setAll(chosen == null ? java.util.List.of() : CustomerWorkflowService.compatibleVenues(chosen));
            venue.getSelectionModel().selectFirst();
        });
        venue.valueProperty().addListener((o, a, chosen) -> { describeVenue(chosen); updateQuote(); });
        pricing.valueProperty().addListener((o, a, b) -> updateQuote());
        start.textProperty().addListener((o, a, b) -> updateQuote());
        end.textProperty().addListener((o, a, b) -> updateQuote());
        purpose.getSelectionModel().selectFirst();
        pricing.getSelectionModel().selectFirst();

        GridPane form = Ui.form();
        Ui.row(form, "Event purpose", purpose);
        Ui.row(form, "Venue", venue);
        Ui.row(form, "", venueInfo);
        Ui.row(form, "Event date", date);
        Ui.row(form, "Start time", start);
        Ui.row(form, "End time", end);
        Ui.row(form, "Reservation pricing", pricing);

        var pay = Ui.primary("Review & Pay", this::submit);
        pay.setId("venue-pay");
        var back = Ui.secondary("Back to Reservations", () -> shell.showPage(MainShell.VENUES));
        return Ui.page("Request a Venue", "Hourly: 06:00–23:00 • Morning half-day: 06:00–12:00 • Afternoon half-day: 12:00–18:00. "
                        + "The full amount is paid from your wallet.",
                Ui.card(form, amountDue, Ui.muted("Wallet balance: " + Ui.php(shell.session().account().getWalletBalance()))),
                Ui.actions(pay, back));
    }

    private void describeVenue(Venue chosen) {
        venueInfo.setText(chosen == null ? "" : VenueCatalog.categoryOf(chosen) + " • capacity " + chosen.capacity() + " • "
                + Ui.php(chosen.hourlyRate()) + "/hour • half-day " + Ui.php(chosen.morningRate()) + " AM / "
                + Ui.php(chosen.afternoonRate()) + " PM");
    }

    private void updateQuote() {
        try {
            BigDecimal quote = CustomerWorkflowService.reservationQuote(venue.getValue(), Ui.time(start.getText(), "Start"),
                    Ui.time(end.getText(), "End"), pricing.getValue());
            amountDue.setText("Amount due: " + Ui.php(quote));
        } catch (RuntimeException ex) {
            amountDue.setText("Amount due: —");
        }
    }

    private void submit() {
        try {
            Venue chosen = venue.getValue();
            if (chosen == null) throw new IllegalArgumentException("Choose a venue.");
            LocalDate day = date.getValue();
            if (day == null) throw new IllegalArgumentException("Event date is required.");
            if (day.isBefore(LocalDate.now())) throw new IllegalArgumentException("Event date cannot be in the past.");
            LocalTime from = Ui.time(start.getText(), "Start time");
            LocalTime to = Ui.time(end.getText(), "End time");
            ReservationPricing rate = pricing.getValue();
            BigDecimal quote = shell.services().workflow().quoteVenueRequest(shell.session().account(), chosen, from, to, rate);
            String review = "Venue: " + chosen.name() + "\nDate: " + day + "\nTime: " + from + "-" + to + "\nAmount due: " + Ui.php(quote);
            if (!Ui.confirm("Confirm full reservation payment?", review)) {
                Ui.info("Reservation request cancelled.", "No payment was taken.");
                return;
            }
            VenueReservation submitted = shell.services().workflow().requestAndPayVenue(shell.session().customer(),
                    shell.session().account(), chosen, day, from, to, rate);
            Ui.info("Reservation payment successful!", "Amount Paid: " + Ui.php(submitted.cost()) + "\nPayment Status: "
                    + submitted.paymentStatus() + "\nReservation Status: " + submitted.status()
                    + "\n\nYour reservation has been submitted for administrator approval.\n"
                    + "You may create an event using this reservation once it has been approved.");
            shell.showPage(MainShell.VENUES);
        } catch (Exception ex) {
            Ui.error("Reservation request failed", Ui.message(ex));
        }
    }

    private static <T> StringConverter<T> converter(java.util.function.Function<T, String> text) {
        return new StringConverter<>() {
            @Override public String toString(T value) { return value == null ? "" : text.apply(value); }
            @Override public T fromString(String string) { return null; }
        };
    }
}
