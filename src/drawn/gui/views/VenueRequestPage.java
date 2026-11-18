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
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/** Venue request form with quote, wallet payment and confirmation (console requestVenueReservation). */
final class VenueRequestPage {
    private final MainShell shell;
    private final Venue selectedVenue;
    private final ComboBox<EventPurpose> purpose = new ComboBox<>();
    private final ComboBox<Venue> venue = new ComboBox<>();
    private final DatePicker date = new DatePicker();
    private final TextField start = Ui.field("HH:mm, e.g. 08:00");
    private final TextField end = Ui.field("HH:mm, e.g. 12:00");
    private final ComboBox<ReservationPricing> pricing = new ComboBox<>();
    private final Label amountDue = Ui.muted("Amount due: —");
    private final Label venueInfo = Ui.muted("");

    VenueRequestPage(MainShell shell, Venue selectedVenue) { this.shell = shell; this.selectedVenue = selectedVenue; }

    Node view() {
        purpose.setId("venue-purpose");
        venue.setId("venue-choice");
        date.setId("venue-date");
        start.setId("venue-start");
        end.setId("venue-end");
        pricing.setId("venue-pricing");

        purpose.getItems().setAll(java.util.Arrays.stream(EventPurpose.values()).filter(selectedVenue::supports).toList());
        purpose.setConverter(converter(CustomerWorkflowService::displayPurpose));
        venue.setConverter(converter(Venue::name));
        venue.getItems().setAll(selectedVenue);
        venue.getSelectionModel().select(selectedVenue);
        venue.setDisable(true);
        pricing.getItems().setAll(ReservationPricing.values());
        pricing.setConverter(converter(Ui::label));
        date.getStyleClass().add("text-input");
        date.setDayCellFactory(picker -> new DateCell() {
            @Override public void updateItem(LocalDate item, boolean empty) {
                super.updateItem(item, empty);
                setDisable(empty || item.isBefore(LocalDate.now()));
            }
        });

        venue.valueProperty().addListener((o, a, chosen) -> { describeVenue(chosen); updateQuote(); });
        pricing.valueProperty().addListener((o, a, b) -> updateQuote());
        start.textProperty().addListener((o, a, b) -> updateQuote());
        end.textProperty().addListener((o, a, b) -> updateQuote());
        purpose.getSelectionModel().selectFirst();
        pricing.getSelectionModel().selectFirst();
        describeVenue(selectedVenue);
        updateQuote();

        GridPane form = Ui.form();
        Ui.row(form, "Event purpose", purpose);
        Ui.row(form, "Event date", date);
        Ui.row(form, "Start time", start);
        Ui.row(form, "End time", end);
        Ui.row(form, "Pricing option", pricing);
        VBox selected = Ui.card(Ui.section("Selected venue"), Ui.title(selectedVenue.name()), venueInfo);
        selected.getStyleClass().add("booking-summary");
        VBox schedule = Ui.card(Ui.section("Reservation schedule"), form);
        schedule.getStyleClass().add("editorial-section-card");
        Label balance = Ui.value(Ui.php(shell.session().account().getWalletBalance()));
        balance.getStyleClass().add("booking-balance");
        VBox priceSummary = Ui.card(Ui.section("Pricing summary"), amountDue,
                Ui.muted("Hourly: 06:00–23:00 • Morning: 06:00–12:00 • Afternoon: 12:00–18:00"));
        priceSummary.getStyleClass().add("booking-summary");
        VBox payment = Ui.card(Ui.section("Payment confirmation"), Ui.muted("Available wallet balance"), balance,
                Ui.muted("Your wallet is charged only after you review and confirm the full payment."));
        payment.getStyleClass().add("booking-summary");

        var pay = Ui.primary("Review & Pay", this::submit);
        pay.setId("venue-pay");
        HBox backCrumb = new HBox(8, BackNavigation.button(() -> shell.goBack("Explore Venues")), Ui.muted("Explore Venues  ›  Request a Venue"));
        backCrumb.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return Ui.page("Request a Venue", "Choose your schedule, review the full cost, and confirm wallet payment.",
                backCrumb, selected, schedule, priceSummary, payment, Ui.actions(pay));
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
            shell.showReservationConfirmation(submitted);
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
