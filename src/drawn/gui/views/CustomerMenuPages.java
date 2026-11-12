package drawn.gui.views;

import drawn.gui.AppServices;
import drawn.gui.AppSession;
import drawn.model.event.*;
import drawn.model.payment.PaymentStatus;
import drawn.model.ticket.TicketType;
import drawn.service.event.ReservationService;
import drawn.storage.TicketRepository;
import drawn.storage.AccountStore;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.StringConverter;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Builds customer workflow pages. Mutations are delegated to the established services. */
final class CustomerMenuPages {
    private final AppServices services;
    private final AppSession session;
    private final Consumer<String> navigate;
    private String venueSearchText = "";
    private String venueCategoryFilter = "All categories";
    private String venueEligibilityFilter = "Any event type";

    CustomerMenuPages(AppServices services, AppSession session, Consumer<String> navigate) {
        this.services = services; this.session = session; this.navigate = navigate;
    }

    Node page(String name) {
        try {
            return switch (name) {
                case "Explore Events" -> exploreEvents();
                case "Explore Venues" -> exploreVenues();
                case "My Events" -> myEvents();
                case "Venue Reservations" -> reservations();
                case "My Tickets" -> tickets();
                case "My Profile" -> profile();
                default -> null; // Wallet & Transactions belongs to the parallel wallet work.
            };
        } catch (IOException | RuntimeException ex) {
            return pageFrame(name, new VBox(10, title("Unable to load " + name), body(ex.getMessage())));
        }
    }

    void createEvent() {
        try {
            List<VenueReservation> eligible = services.reservationService().forCustomer(session.customer()).stream()
                    .filter(r -> (r.status() == BookingStatus.APPROVED || r.status() == BookingStatus.CONFIRMED)
                            && r.paymentStatus() == PaymentStatus.PAID && r.eventId().isBlank()).toList();
            if (eligible.isEmpty()) {
                info("No approved, paid venue reservation is ready yet. Request and pay for a venue, then wait for administrator approval.");
                navigate.accept("Venue Reservations");
                return;
            }
            showEventForm(eligible);
        } catch (IOException | RuntimeException ex) { error("Could not load eligible reservations: " + message(ex)); }
    }

    private Node exploreEvents() throws IOException {
        List<Event> events = services.publishing().publishedEvents();
        VBox content = new VBox(14, title("Explore Events"), body("Browse public events and view ticket availability."));
        if (events.isEmpty()) content.getChildren().add(emptyState("No public events are available yet."));
        for (Event event : events) content.getChildren().add(eventCard(event, false));
        return pageFrame("Explore Events", content);
    }

    private Node exploreVenues() {
        List<Venue> catalog = VenueCatalog.all();
        TextField search = new TextField();
        search.setPromptText("Search venues by name or category");
        search.setAccessibleText("Search venues by name or category");
        search.setText(venueSearchText);
        ComboBox<String> category = new ComboBox<>();
        category.getItems().add("All categories");
        catalog.stream().map(VenueCatalog::categoryOf).distinct().sorted().forEach(category.getItems()::add);
        category.getSelectionModel().select(category.getItems().contains(venueCategoryFilter) ? venueCategoryFilter : "All categories");
        ComboBox<String> eligibility = new ComboBox<>();
        eligibility.getItems().setAll("Any event type", "Social gathering", "Ticketed event");
        eligibility.getSelectionModel().select(eligibility.getItems().contains(venueEligibilityFilter) ? venueEligibilityFilter : "Any event type");
        HBox filters = new HBox(10, search, category, eligibility);
        filters.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(search, Priority.ALWAYS);
        GridPane cards = twoColumnGrid();
        Runnable refresh = () -> {
            String query = search.getText() == null ? "" : search.getText().trim().toLowerCase(java.util.Locale.ROOT);
            String selectedCategory = category.getValue();
            EventPurpose selectedPurpose = switch (eligibility.getValue() == null ? "" : eligibility.getValue()) {
                case "Social gathering" -> EventPurpose.SOCIAL_GATHERING;
                case "Ticketed event" -> EventPurpose.TICKETED_EVENT;
                default -> null;
            };
            List<Venue> matches = catalog.stream()
                    .filter(v -> (query.isBlank() || v.name().toLowerCase(java.util.Locale.ROOT).contains(query)
                            || VenueCatalog.categoryOf(v).toLowerCase(java.util.Locale.ROOT).contains(query)))
                    .filter(v -> selectedCategory == null || selectedCategory.equals("All categories")
                            || VenueCatalog.categoryOf(v).equals(selectedCategory))
                    .filter(v -> selectedPurpose == null || v.supports(selectedPurpose)).toList();
            cards.getChildren().clear();
            if (matches.isEmpty()) {
                Node empty = emptyState("No venues match those filters.");
                cards.add(empty, 0, 0, 2, 1);
            } else {
                for (int i = 0; i < matches.size(); i++) {
                    VBox card = venueCard(matches.get(i));
                    cards.add(card, i % 2, i / 2);
                    GridPane.setHgrow(card, Priority.ALWAYS);
                }
            }
        };
        search.textProperty().addListener((o,a,b) -> { venueSearchText = b == null ? "" : b; refresh.run(); });
        category.valueProperty().addListener((o,a,b) -> { venueCategoryFilter = b == null ? "All categories" : b; refresh.run(); });
        eligibility.valueProperty().addListener((o,a,b) -> { venueEligibilityFilter = b == null ? "Any event type" : b; refresh.run(); });
        refresh.run();
        VBox content = new VBox(14, title("Explore Venues"),
                body("Browse the venue catalog, compare capacity and rates, then choose a venue to request."), filters, cards);
        return pageFrame("Explore Venues", content);
    }

    private VBox venueCard(Venue venue) {
        VBox card = dataCard();
        card.setMaxWidth(Double.MAX_VALUE);
        String eligibility = venue.type() == VenueType.BOTH ? "Social and ticketed events"
                : venue.type() == VenueType.COMMERCIAL ? "Ticketed events" : "Social gatherings";
        card.getChildren().addAll(title(venue.name()),
                body(CustomerText.words(VenueCatalog.categoryOf(venue)) + " • " + eligibility),
                body("Capacity: " + (venue.capacity() == Integer.MAX_VALUE ? "Not specified" : venue.capacity() + " guests")),
                body("Hourly from PHP " + money(venue.hourlyRate())),
                body("Half-day from PHP " + money(venue.morningRate())));
        HBox actions = new HBox(8,
                secondary("View Details", () -> showVenueDetails(venue)),
                primary("Select Venue", () -> requestVenue(venue)));
        actions.setAlignment(Pos.CENTER_LEFT);
        card.getChildren().add(actions);
        return card;
    }

    private void showVenueDetails(Venue venue) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(venue.name());
        dialog.getDialogPane().getButtonTypes().addAll(new ButtonType("Reserve This Venue", ButtonBar.ButtonData.OK_DONE), ButtonType.CLOSE);
        String eligibility = venue.type() == VenueType.BOTH ? "Social gatherings and ticketed events"
                : venue.type() == VenueType.COMMERCIAL ? "Ticketed events" : "Social gatherings";
        VBox details = new VBox(10, title(venue.name()),
                body("Category: " + CustomerText.words(VenueCatalog.categoryOf(venue))),
                body("Eligible events: " + eligibility),
                body("Maximum capacity: " + (venue.capacity() == Integer.MAX_VALUE ? "Not specified" : venue.capacity() + " guests")),
                body("Hourly rate: PHP " + money(venue.hourlyRate())),
                body("Half-day morning rate: PHP " + money(venue.morningRate())),
                body("Half-day afternoon rate: PHP " + money(venue.afternoonRate())));
        dialog.getDialogPane().setContent(details);
        styleDialog(dialog);
        Button reserve = (Button) dialog.getDialogPane().lookupButton(dialog.getDialogPane().getButtonTypes().get(0));
        reserve.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            dialog.close();
            requestVenue(venue);
        });
        dialog.showAndWait();
    }

    private Node myEvents() throws IOException {
        List<Event> events = services.eventService().eventsFor(session.customer()).stream()
                .filter(e -> e.approvalStatus() != EventApprovalStatus.CANCELLED).toList();
        List<VenueReservation> reservations = services.reservationService().forCustomer(session.customer());
        VBox content = new VBox(14, title("My Events"), body("Events created from your approved venue reservations."));
        Button create = primary("Create Event", () -> createEvent()); content.getChildren().add(create);
        if (events.isEmpty()) content.getChildren().add(emptyState("You have not created an event yet."));
        for (Event event : events) {
            VBox card = dataCard();
            VenueReservation booking = reservations.stream().filter(r -> r.eventId().equals(event.id())).findFirst().orElse(null);
            String schedule = booking == null ? event.date() + " • " + CustomerText.time(event.startTime()) + "–" + CustomerText.time(event.endTime())
                    : CustomerText.reservationSchedule(booking);
            card.getChildren().addAll(title(event.name()), body(CustomerText.words(event.type()) + " • " + schedule),
                    body("Venue: " + event.venue().name() + " • Status: " + CustomerText.words(event.approvalStatus())), body(event.description()));
            HBox actions = new HBox(8);
            Button details = secondary("View Details", () -> new EventDetailsView(event, services, session, () -> navigate.accept("My Events"), booking).show());
            Button cancel = secondary("Cancel Event", () -> cancelEvent(event));
            actions.getChildren().addAll(details, cancel); card.getChildren().add(actions); content.getChildren().add(card);
        }
        return pageFrame("My Events", content);
    }

    private Node reservations() throws IOException {
        List<VenueReservation> records = services.reservationService().forCustomer(session.customer());
        VBox content = new VBox(14, title("Venue Reservations"), body("Request and pay for a venue. Administrator approval remains a separate step."));
        content.getChildren().add(primary("Request a Venue", () -> navigate.accept("Explore Venues")));
        if (records.isEmpty()) content.getChildren().add(emptyState("You do not have venue reservations yet."));
        GridPane cards = twoColumnGrid();
        int index = 0;
        for (VenueReservation r : records) {
            VBox card = dataCard();
            card.getChildren().addAll(title(r.venueName()), body(CustomerText.reservationSchedule(r)),
                    body("Total: PHP " + money(r.cost())),
                    body("Reservation: " + CustomerText.words(r.status()) + " • Payment: " + CustomerText.words(r.paymentStatus())));
            if (!r.eventId().isBlank()) card.getChildren().add(body("Linked event: " + r.eventId()));
            if (!r.declineReason().isBlank()) card.getChildren().add(body("Reason: " + r.declineReason()));
            HBox actions = new HBox(8);
            actions.getChildren().add(secondary("View Details", () -> showReservationDetails(r)));
            if (isEditable(r)) actions.getChildren().add(secondary("Edit", () -> editReservation(r)));
            if (r.status() != BookingStatus.CANCELLED && r.status() != BookingStatus.DECLINED && r.status() != BookingStatus.EXPIRED)
                actions.getChildren().add(secondary("Cancel", () -> cancelReservation(r)));
            actions.setAlignment(Pos.CENTER_LEFT);
            card.getChildren().add(actions);
            cards.add(card, index % 2, index / 2);
            GridPane.setHgrow(card, Priority.ALWAYS);
            index++;
        }
        if (!records.isEmpty()) content.getChildren().add(cards);
        return pageFrame("Venue Reservations", content);
    }

    private Node tickets() throws IOException {
        List<TicketRepository.Purchase> purchases = services.publishing().purchases(session.customer());
        VBox content = new VBox(14, title("My Tickets"), body("Your purchases and their current status."));
        if (purchases.isEmpty()) {
            VBox bubble = new VBox(12, title("No tickets yet"), body("Explore events and find tickets for your next experience."),
                    primary("Explore Events", () -> navigate.accept("Explore Events")));
            bubble.setAlignment(Pos.CENTER);
            bubble.setMaxWidth(440);
            bubble.getStyleClass().add("empty-state-bubble");
            StackPane centered = new StackPane(bubble);
            centered.setMinHeight(420);
            centered.setPrefHeight(420);
            content.getChildren().add(centered);
        }
        for (TicketRepository.Purchase p : purchases) {
            Event event = services.publishing().event(p.eventId());
            TicketType type = services.publishing().ticketTypes(p.eventId()).stream()
                    .filter(t -> t.id().equals(p.ticketTypeId())).findFirst().orElse(null);
            VBox card = dataCard();
            card.getChildren().addAll(title(event.name()), body((type == null ? "Ticket" : type.name()) + " × " + p.quantity()),
                    body("Paid: PHP " + money(p.amount()) + " • Purchased: " + p.timestamp()), body("Status: " + CustomerText.words(p.status())),
                    body(event.date() + " • " + CustomerText.time(event.startTime()) + "–" + CustomerText.time(event.endTime()) + " • " + event.venue().name()));
            if (p.status() == drawn.model.ticket.TicketPurchaseStatus.ACTIVE) card.getChildren().add(secondary("Cancel Tickets", () -> cancelTickets(p)));
            content.getChildren().add(card);
        }
        return pageFrame("My Tickets", content);
    }

    private Node profile() {
        var customer = session.customer();
        VBox content = new VBox(14, title("My Profile"), body("Your customer account details."));
        VBox card = dataCard();
        card.getChildren().addAll(title(customer.getName()), body("Account: " + customer.getId()),
                body("Email: " + nullText(customer.getEmail())), body("Phone: " + nullText(customer.getPhoneNumber())),
                body("Date of birth: " + nullText(customer.getDateOfBirth())),
                body("Contact preference: " + CustomerText.words(customer.getContactMethod())));
        content.getChildren().add(card);
        return pageFrame("My Profile", content);
    }

    private Node eventCard(Event event, boolean owned) {
        VBox card = dataCard();
        card.getChildren().addAll(title(event.name()), body(CustomerText.words(event.type()) + " • " + event.date() + " • " + CustomerText.time(event.startTime()) + "–" + CustomerText.time(event.endTime())),
                body("Venue: " + event.venue().name()), body(event.description()));
        if (event.purpose() == EventPurpose.TICKETED_EVENT) {
            try {
                List<TicketType> types = services.publishing().ticketTypes(event.id());
                for (TicketType type : types) card.getChildren().add(body(type.name() + " • PHP " + money(type.price()) + " • " + type.remaining() + " available"));
            } catch (IOException ex) { card.getChildren().add(body("Ticket details unavailable: " + message(ex))); }
        }
        Button details = secondary("View Details", () -> new EventDetailsView(event, services, session, () -> navigate.accept("Explore Events")).show());
        card.getChildren().add(details);
        return card;
    }

    private void requestVenue(Venue selectedVenue) {
        Dialog<ButtonType> dialog = new Dialog<>(); dialog.setTitle("Request a venue");
        ButtonType continueButton = new ButtonType("Continue", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(continueButton, ButtonType.CANCEL);
        ComboBox<EventPurpose> purpose = new ComboBox<>();
        purpose.getItems().setAll(java.util.Arrays.stream(EventPurpose.values()).filter(selectedVenue::supports).toList());
        purpose.setMaxWidth(Double.MAX_VALUE);
        purpose.setConverter(converter(CustomerText::words)); purpose.getSelectionModel().selectFirst();
        VBox selectedVenueSummary = new VBox(4, title(selectedVenue.name()),
                body(CustomerText.words(VenueCatalog.categoryOf(selectedVenue)) + " • "
                        + (selectedVenue.type() == VenueType.BOTH ? "Social and ticketed events"
                        : selectedVenue.type() == VenueType.COMMERCIAL ? "Ticketed events" : "Social gatherings")),
                body("Capacity: " + (selectedVenue.capacity() == Integer.MAX_VALUE ? "Not specified" : selectedVenue.capacity() + " guests")));
        DatePicker date = new DatePicker(LocalDate.now().plusDays(1)); date.setMaxWidth(Double.MAX_VALUE); date.setDayCellFactory(picker -> new DateCell() { @Override public void updateItem(LocalDate d, boolean empty) { super.updateItem(d,empty); setDisable(empty || d.isBefore(LocalDate.now())); }});
        TimeInput start = new TimeInput("10:00", "AM"), end = new TimeInput("12:00", "PM");
        start.setMaxWidth(Double.MAX_VALUE); end.setMaxWidth(Double.MAX_VALUE);
        Label startLabel = label("Start time"), endLabel = label("End time");
        ComboBox<ReservationPricing> pricing = new ComboBox<>(); pricing.getItems().setAll(ReservationPricing.values()); pricing.setMaxWidth(Double.MAX_VALUE); pricing.setConverter(converter(CustomerText::words)); pricing.getSelectionModel().selectFirst();
        GridPane fields = formGrid();
        fields.addRow(0,label("Event purpose"),purpose); fields.addRow(1,label("Selected venue"),selectedVenueSummary); fields.addRow(2,label("Date"),date);
        Label pricingLabel = label("Pricing");
        Label error = AuthenticationFormSupport.errorLabel();
        GridPane costBreakdown = new GridPane();
        costBreakdown.setHgap(18); costBreakdown.setVgap(5);
        costBreakdown.getStyleClass().add("venue-cost-breakdown");
        Label venueFeeLabel = body("Venue fee");
        Label serviceFeeLabel = body("Service fee");
        Label totalLabel = title("Total due");
        Label venueFee = body("—"), serviceFee = body("PHP 0.00");
        Label total = title("—");
        costBreakdown.addRow(0, venueFeeLabel, venueFee);
        costBreakdown.addRow(1, serviceFeeLabel, serviceFee);
        costBreakdown.addRow(2, totalLabel, total);
        Runnable updateTimeRows = () -> {
            boolean hourly = pricing.getValue() == ReservationPricing.HOURLY;
            fields.getChildren().removeAll(startLabel, start, endLabel, end, pricingLabel, pricing);
            int row = 3;
            if (hourly) {
                fields.addRow(row++, startLabel, start);
                fields.addRow(row++, endLabel, end);
            }
            fields.addRow(row, pricingLabel, pricing);
        };
        Runnable quote = () -> {
            try {
                LocalTime[] times = selectedTimes(pricing.getValue(), start, end);
                BigDecimal amount = ReservationService.price(selectedVenue, times[0], times[1], pricing.getValue());
                venueFee.setText("PHP " + money(amount));
                total.setText("PHP " + money(amount));
                error.setText("");
            } catch (RuntimeException ex) { venueFee.setText("—"); total.setText("—"); error.setText(message(ex)); }
        };
        start.onChange(quote); end.onChange(quote);
        pricing.valueProperty().addListener((o,a,b)->{updateTimeRows.run();quote.run();});
        updateTimeRows.run(); quote.run();
        VBox form = new VBox(14, fields, title("Cost breakdown"), costBreakdown, error);
        form.setPadding(new Insets(4));
        dialog.getDialogPane().setContent(form); styleDialog(dialog);
        Button continueAction = (Button) dialog.getDialogPane().lookupButton(continueButton);
        continueAction.getStyleClass().add("venue-flow-primary");
        dialog.getDialogPane().lookupButton(continueButton).addEventFilter(ActionEvent.ACTION, action -> {
            action.consume();
            try {
                Venue validatedVenue = VenueCatalog.all().stream().filter(v -> v.equals(selectedVenue)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("The selected venue is no longer available."));
                if (purpose.getValue() == null || !validatedVenue.supports(purpose.getValue()) || date.getValue()==null)
                    throw new IllegalArgumentException("Choose an eligible event purpose and date.");
                LocalTime[] times = selectedTimes(pricing.getValue(), start, end);
                BigDecimal amount = ReservationService.price(validatedVenue, times[0], times[1], pricing.getValue());
                if (session.account().getWalletBalance().compareTo(amount) < 0)
                    throw new IllegalArgumentException("Insufficient wallet balance.");
                showVenueReceipt(dialog, validatedVenue, purpose.getValue(), date.getValue(), times[0], times[1], pricing.getValue(), amount);
            } catch(Exception ex){error.setText(message(ex));}
        });
        dialog.showAndWait();
    }

    private void showVenueReceipt(Dialog<ButtonType> requestDialog, Venue venue, EventPurpose purpose, LocalDate date,
                                  LocalTime start, LocalTime end, ReservationPricing pricing, BigDecimal amount) {
        Dialog<ButtonType> receiptDialog = new Dialog<>();
        receiptDialog.setTitle("Venue reservation receipt");
        ButtonType reserveAndPay = new ButtonType("Reserve and Pay", ButtonBar.ButtonData.OK_DONE);
        receiptDialog.getDialogPane().getButtonTypes().addAll(reserveAndPay, ButtonType.CANCEL);
        BigDecimal duration = BigDecimal.valueOf(java.time.Duration.between(start, end).toMinutes())
                .divide(BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP);
        String durationText = pricing == ReservationPricing.HOURLY ? duration.toPlainString() + " hours"
                : "6 hours (half day)";
        BigDecimal rate = rateFor(venue, pricing);
        String rateUnit = pricing == ReservationPricing.HOURLY ? " per hour" : " per half day";
        VBox details = new VBox(10,
                title("Receipt preview"),
                body("Customer: " + session.customer().getName()),
                body("Venue: " + venue.name()),
                body("Event purpose: " + CustomerText.words(purpose)),
                body("Date: " + date),
                body("Time: " + CustomerText.time(start) + "–" + CustomerText.time(end)),
                body("Pricing: " + CustomerText.words(pricing)),
                body("Duration: " + durationText),
                body("Rate: PHP " + money(rate) + rateUnit),
                new Separator(),
                body("Venue fee: PHP " + money(amount)),
                body("Service fee: PHP 0.00"),
                title("Total due: PHP " + money(amount)));
        Label paymentError = AuthenticationFormSupport.errorLabel();
        VBox content = new VBox(12, details, paymentError);
        content.setPadding(new Insets(6));
        receiptDialog.getDialogPane().setContent(content);
        styleDialog(receiptDialog);
        Button pay = (Button) receiptDialog.getDialogPane().lookupButton(reserveAndPay);
        pay.getStyleClass().add("venue-flow-primary");
        pay.addEventFilter(ActionEvent.ACTION, action -> {
            action.consume();
            pay.setDisable(true);
            VenueReservation paid;
            try {
                paid = services.venueWorkflow().requestAndPay(session.customer(), session.account(),
                        venue, date, start, end, pricing);
            } catch (Exception ex) {
                pay.setDisable(false);
                paymentError.setText(message(ex));
                return;
            }
            try {
                saveVenueReceipt(paid.id(), venue, purpose, date, start, end, pricing, duration, amount, "PAID");
            } catch (IOException ex) {
                receiptDialog.close(); requestDialog.close();
                error("Payment succeeded, but the receipt could not be saved: " + message(ex));
                navigate.accept("Venue Reservations");
                return;
            }
            receiptDialog.setResult(reserveAndPay);
            receiptDialog.close();
            requestDialog.close();
            info("Payment complete. Your receipt has been saved.");
            navigate.accept("Venue Reservations");
        });
        Button cancel = (Button) receiptDialog.getDialogPane().lookupButton(ButtonType.CANCEL);
        cancel.addEventFilter(ActionEvent.ACTION, action -> {
            try { saveVenueReceipt("", venue, purpose, date, start, end, pricing, duration, amount, "CANCELLED"); }
            catch (IOException ex) { error("Could not save receipt: " + message(ex)); }
            requestDialog.close();
        });
        receiptDialog.showAndWait();
    }

    private boolean isEditable(VenueReservation reservation) {
        return reservation.status() == BookingStatus.PENDING && reservation.eventId().isBlank()
                && reservation.paymentStatus() == PaymentStatus.PAID;
    }

    private void showReservationDetails(VenueReservation reservation) {
        Venue venue = venueFor(reservation);
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Reservation details");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        VBox details = new VBox(10,
                title(reservation.venueName()),
                body("Reservation ID: " + reservation.id()),
                body("Date: " + reservation.date()),
                body("Schedule: " + CustomerText.reservationSchedule(reservation)),
                body("Pricing: " + CustomerText.words(reservation.pricing())),
                body("Reservation status: " + CustomerText.words(reservation.status())),
                body("Payment status: " + CustomerText.words(reservation.paymentStatus())),
                new Separator(),
                body("Rate: PHP " + money(rateFor(venue, reservation.pricing()))
                        + (reservation.pricing() == ReservationPricing.HOURLY ? " per hour" : " per half day")),
                body("Venue fee: PHP " + money(reservation.cost())),
                body("Service fee: PHP 0.00"),
                title("Total: PHP " + money(reservation.cost())),
                body("Venue capacity: " + (venue.capacity() == Integer.MAX_VALUE ? "Not specified" : venue.capacity() + " guests")));
        if (!reservation.eventId().isBlank()) details.getChildren().add(body("Linked event: " + reservation.eventId()));
        if (!reservation.declineReason().isBlank()) details.getChildren().add(body("Reason: " + reservation.declineReason()));
        details.setPadding(new Insets(6));
        dialog.getDialogPane().setContent(details);
        styleDialog(dialog);
        dialog.showAndWait();
    }

    private void editReservation(VenueReservation reservation) {
        if (!isEditable(reservation)) {
            error("Only unapproved venue reservations can be edited.");
            return;
        }
        Venue venue = venueFor(reservation);
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Edit venue reservation");
        ButtonType save = new ButtonType("Save Changes", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        DatePicker date = new DatePicker(reservation.date());
        date.setDayCellFactory(picker -> new DateCell() {
            @Override public void updateItem(LocalDate day, boolean empty) {
                super.updateItem(day, empty);
                setDisable(empty || day.isBefore(LocalDate.now()));
            }
        });
        TimeInput start = timeInput(reservation.startTime());
        TimeInput end = timeInput(reservation.endTime());
        ComboBox<ReservationPricing> pricing = new ComboBox<>();
        pricing.getItems().setAll(ReservationPricing.values());
        pricing.setConverter(converter(CustomerText::words));
        pricing.setValue(reservation.pricing());
        GridPane fields = formGrid();
        fields.addRow(0, label("Venue"), body(venue.name()));
        fields.addRow(1, label("Date"), date);
        Label startLabel = label("Start time"), endLabel = label("End time");
        fields.addRow(2, startLabel, start);
        fields.addRow(3, endLabel, end);
        fields.addRow(4, label("Pricing"), pricing);
        Label fee = body("—"), total = title("—"), message = AuthenticationFormSupport.errorLabel();
        Runnable updateQuote = () -> {
            try {
                LocalTime[] times = selectedTimes(pricing.getValue(), start, end);
                BigDecimal amount = ReservationService.price(venue, times[0], times[1], pricing.getValue());
                fee.setText("PHP " + money(amount));
                total.setText("PHP " + money(amount));
                message.setText("");
            } catch (RuntimeException ex) { fee.setText("—"); total.setText("—"); message.setText(message(ex)); }
        };
        Runnable updatePricingRows = () -> {
            boolean hourly = pricing.getValue() == ReservationPricing.HOURLY;
            startLabel.setVisible(hourly); startLabel.setManaged(hourly);
            start.setVisible(hourly); start.setManaged(hourly);
            endLabel.setVisible(hourly); endLabel.setManaged(hourly);
            end.setVisible(hourly); end.setManaged(hourly);
            updateQuote.run();
        };
        start.onChange(updateQuote); end.onChange(updateQuote);
        pricing.valueProperty().addListener((o, a, b) -> updatePricingRows.run());
        GridPane breakdown = new GridPane(); breakdown.setHgap(18); breakdown.setVgap(5);
        breakdown.addRow(0, body("Venue fee"), fee);
        breakdown.addRow(1, body("Service fee"), body("PHP 0.00"));
        breakdown.addRow(2, title("Total due"), total);
        updatePricingRows.run();
        VBox content = new VBox(12, fields, title("Cost breakdown"), breakdown, message);
        content.setPadding(new Insets(6));
        dialog.getDialogPane().setContent(content);
        styleDialog(dialog);
        Button saveButton = (Button) dialog.getDialogPane().lookupButton(save);
        saveButton.getStyleClass().add("venue-flow-primary");
        saveButton.addEventFilter(ActionEvent.ACTION, action -> {
            action.consume();
            try {
                if (date.getValue() == null) throw new IllegalArgumentException("Choose a reservation date.");
                LocalTime[] times = selectedTimes(pricing.getValue(), start, end);
                BigDecimal newCost = ReservationService.price(venue, times[0], times[1], pricing.getValue());
                BigDecimal adjustment = newCost.subtract(reservation.cost());
                String paymentChange = adjustment.signum() > 0 ? " An additional PHP " + money(adjustment) + " will be charged."
                        : adjustment.signum() < 0 ? " PHP " + money(adjustment.abs()) + " will be refunded to your wallet." : "";
                if (!confirm("Save these changes?" + paymentChange)) return;
                VenueReservation edited = services.paymentService().editPendingVenue(session.customer(), session.account(), reservation.id(), venue,
                        date.getValue(), times[0], times[1], pricing.getValue());
                dialog.close();
                try {
                    saveEditedVenueReceipt(edited);
                    info("Reservation updated and the revised receipt has been saved.");
                } catch (IOException ex) {
                    error("Reservation updated, but the revised receipt could not be saved: " + message(ex));
                }
                navigate.accept("Venue Reservations");
            } catch (Exception ex) { message.setText(message(ex)); }
        });
        dialog.showAndWait();
    }

    private static TimeInput timeInput(LocalTime time) {
        int hour = time.getHour() % 12;
        if (hour == 0) hour = 12;
        return new TimeInput(String.format(java.util.Locale.ROOT, "%02d:%02d", hour, time.getMinute()),
                time.getHour() < 12 ? "AM" : "PM");
    }

    private void saveEditedVenueReceipt(VenueReservation reservation) throws IOException {
        BigDecimal duration = BigDecimal.valueOf(java.time.Duration.between(reservation.startTime(), reservation.endTime()).toMinutes())
                .divide(BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP);
        services.venueReceipts().save(new drawn.storage.VenueReceiptRepository.Receipt(
                java.util.UUID.randomUUID().toString(), reservation.id(), session.customer().getId(), reservation.venueName(),
                "Not specified", reservation.date(), reservation.startTime(), reservation.endTime(), reservation.pricing(),
                duration, rateFor(venueFor(reservation), reservation.pricing()), reservation.cost(), BigDecimal.ZERO.setScale(2),
                reservation.cost(), "UPDATED", java.time.LocalDateTime.now()));
    }

    private void saveVenueReceipt(String reservationId, Venue venue, EventPurpose purpose, LocalDate date,
                                  LocalTime start, LocalTime end, ReservationPricing pricing, BigDecimal duration,
                                  BigDecimal amount, String status) throws IOException {
        services.venueReceipts().save(new drawn.storage.VenueReceiptRepository.Receipt(
                java.util.UUID.randomUUID().toString(), reservationId, session.customer().getId(), venue.name(),
                purpose.name(), date, start, end, pricing, duration, rateFor(venue, pricing), amount, BigDecimal.ZERO.setScale(2), amount,
                status, java.time.LocalDateTime.now()));
    }

    private static BigDecimal rateFor(Venue venue, ReservationPricing pricing) {
        return switch (pricing) {
            case HOURLY -> venue.hourlyRate();
            case HALF_DAY_MORNING -> venue.morningRate();
            case HALF_DAY_AFTERNOON -> venue.afternoonRate();
        };
    }

    private void showEventForm(List<VenueReservation> eligible) {
        Dialog<ButtonType> dialog=new Dialog<>();dialog.setTitle("Create Event from Approved Reservation");
        ButtonType submit=new ButtonType("Create Event",ButtonBar.ButtonData.OK_DONE);dialog.getDialogPane().getButtonTypes().addAll(submit,ButtonType.CANCEL);
        ComboBox<VenueReservation> reservation=new ComboBox<>();reservation.getItems().setAll(eligible);reservation.setMaxWidth(Double.MAX_VALUE);
        reservation.setConverter(converter(r->r.venueName()+" • "+r.date()+" • "+CustomerText.words(r.status())));reservation.getSelectionModel().selectFirst();
        TextField name=new TextField();TextArea description=new TextArea();description.setPromptText("Event description (up to 500 characters)");description.setPrefRowCount(3);description.setWrapText(true);
        ComboBox<EventType> type=new ComboBox<>();type.getItems().setAll(EventType.values());type.setConverter(converter(CustomerText::words));type.getSelectionModel().selectFirst();
        ComboBox<EventPurpose> purpose=new ComboBox<>();purpose.getItems().setAll(EventPurpose.values());purpose.setConverter(converter(CustomerText::words));purpose.getSelectionModel().selectFirst();
        ComboBox<EventVisibility> visibility=new ComboBox<>();visibility.getItems().setAll(EventVisibility.values());visibility.setConverter(converter(CustomerText::words));visibility.getSelectionModel().selectFirst();
        TextField keyword=new TextField();keyword.setPromptText("Private event access keyword");
        Spinner<Integer> attendance=new Spinner<>(1,VenueCatalog.all().stream().mapToInt(Venue::capacity).max().orElse(3000),1);attendance.setEditable(true);
        VBox ticketRows=new VBox(8);Button addCategory=secondary("Add Ticket Category",()->addTicketRow(ticketRows));addTicketRow(ticketRows);
        purpose.valueProperty().addListener((o,a,b)->{boolean ticketed=b==EventPurpose.TICKETED_EVENT;ticketRows.setVisible(ticketed);ticketRows.setManaged(ticketed);});
        visibility.valueProperty().addListener((o,a,b)->{boolean priv=b==EventVisibility.PRIVATE;keyword.setVisible(priv);keyword.setManaged(priv);});keyword.setVisible(false);keyword.setManaged(false);
        Label reserved=new Label();reserved.getStyleClass().add("secondary-text");Runnable updateReservation=()->{VenueReservation r=reservation.getValue();if(r==null)return;reserved.setText("Reserved: "+CustomerText.reservationSchedule(r));Venue v=venueFor(r);((SpinnerValueFactory.IntegerSpinnerValueFactory)attendance.getValueFactory()).setMax(v.capacity());};reservation.valueProperty().addListener((o,a,b)->updateReservation.run());updateReservation.run();
        Label error=AuthenticationFormSupport.errorLabel();VBox form=new VBox(10,label("Approved reservation"),reservation,reserved,
                row("Event name",name),row("Description",description),row("Event type",type),row("Purpose",purpose),row("Visibility",visibility),keyword,
                row("Expected attendance",attendance),ticketRows,addCategory,error);form.setPadding(new Insets(12));
        ScrollPane scroller=new ScrollPane(form);scroller.setFitToWidth(true);scroller.setPrefViewportHeight(540);scroller.getStyleClass().add("content-scroll");dialog.getDialogPane().setContent(scroller);styleDialog(dialog);
        dialog.getDialogPane().lookupButton(submit).addEventFilter(ActionEvent.ACTION,action->{action.consume();
            try {
                VenueReservation r=reservation.getValue();Venue v=venueFor(r);EventPurpose p=purpose.getValue();
                if(!v.supports(p))throw new IllegalArgumentException("This venue does not support the selected event purpose.");
                String eventName=name.getText().trim(),desc=description.getText().trim();if(eventName.isEmpty()||desc.isEmpty())throw new IllegalArgumentException("Event name and description are required.");
                if(desc.length()>500)throw new IllegalArgumentException("Description must be 500 characters or fewer.");
                String access=visibility.getValue()==EventVisibility.PRIVATE?keyword.getText().trim():"";if(visibility.getValue()==EventVisibility.PRIVATE&&access.isEmpty())throw new IllegalArgumentException("Private events need an access keyword.");
                List<TicketCategory> categories=p==EventPurpose.TICKETED_EVENT?readTicketRows(ticketRows):List.of();
                if(categories.stream().mapToInt(TicketCategory::quantity).sum()>v.capacity())throw new IllegalArgumentException("Ticket quantity exceeds venue capacity.");
                EventDraft draft=new EventDraft(eventName,desc,type.getValue(),p,visibility.getValue(),r.date(),r.startTime(),r.endTime(),attendance.getValue(),v,categories);
                if(!confirm("Create and publish “"+eventName+"” using this approved reservation?"))return;
                dialog.getDialogPane().lookupButton(submit).setDisable(true);
                services.eventCreationService().create(session.customer(),draft,r.id(),access);
                dialog.setResult(submit);dialog.close();info("Event created and published.");navigate.accept("My Events");
            }catch(Exception ex){error.setText(message(ex));}
        });
        dialog.showAndWait();
    }

    private void addTicketRow(VBox rows) {
        TextField n=new TextField();n.setPromptText("Category name");TextField price=new TextField();price.setPromptText("Price PHP");TextField qty=new TextField();qty.setPromptText("Quantity");
        HBox row=new HBox(8);Button remove=secondary("Remove",()->rows.getChildren().remove(row));
        row.getChildren().addAll(n,price,qty,remove);row.getProperties().put("ticket-row",Boolean.TRUE);for(Node node:List.of(n,price,qty))HBox.setHgrow(node,Priority.ALWAYS);rows.getChildren().add(row);
    }

    private List<TicketCategory> readTicketRows(VBox rows) {
        List<TicketCategory> result=new ArrayList<>();
        for(Node node:rows.getChildren())if(node instanceof HBox row&&Boolean.TRUE.equals(row.getProperties().get("ticket-row"))){
            String n=((TextField)row.getChildren().get(0)).getText().trim();BigDecimal p=new BigDecimal(((TextField)row.getChildren().get(1)).getText().trim());int q=Integer.parseInt(((TextField)row.getChildren().get(2)).getText().trim());result.add(new TicketCategory(n,p,q));}
        if(result.isEmpty())throw new IllegalArgumentException("Add at least one ticket category for a ticketed event.");return result;
    }

    private void cancelReservation(VenueReservation r) {
        if(!confirm("Cancel this reservation? Any eligible refund follows the existing reservation rules."))return;
        try{services.paymentService().cancelReservation(session.customer(),session.account(),r.id());navigate.accept("Venue Reservations");}
        catch(Exception ex){error("Reservation cancellation failed: "+message(ex));}
    }
    private void cancelEvent(Event event) {
        if(!confirm("Cancel “"+event.name()+"” and process eligible refunds?"))return;
        try{services.cancellationService().cancel(session.customer(),event.id());navigate.accept("My Events");}
        catch(Exception ex){error("Event cancellation failed: "+message(ex));}
    }
    private void cancelTickets(TicketRepository.Purchase p) {
        try {
            var quote=services.publishing().cancellationQuote(session.customer(),p.id());
            if(!confirm("Cancel "+p.quantity()+" ticket(s)? Refund after fee: PHP "+money(quote.refundAmount())+"."))return;
            services.publishing().cancelTicket(session.customer(),session.account(),p.id());navigate.accept("My Tickets");
        }catch(Exception ex){error("Ticket cancellation failed: "+message(ex));}
    }

    private Venue venueFor(VenueReservation r) { return VenueCatalog.all().stream().filter(v->v.name().equals(r.venueName())).findFirst().orElseThrow(()->new IllegalArgumentException("Reserved venue details are unavailable.")); }
    private Node pageFrame(String page,Node content){ScrollPane scroll=new ScrollPane(content);scroll.setFitToWidth(true);scroll.getStyleClass().add("content-scroll");scroll.setPadding(new Insets(0));BorderPane frame=new BorderPane(scroll);frame.getStyleClass().add("content-page");return frame;}
    private VBox dataCard(){VBox box=new VBox(10);box.getStyleClass().add("event-card");box.setMaxWidth(Double.MAX_VALUE);return box;}
    private VBox emptyState(String message){VBox box=new VBox(8,title("Nothing here yet"),body(message));box.setAlignment(Pos.CENTER);box.setPadding(new Insets(44));box.getStyleClass().add("empty-state");return box;}
    private static VBox row(String label,Node field){return new VBox(5,new Label(label),field);}
    private static GridPane formGrid(){GridPane p=new GridPane();p.setHgap(16);p.setVgap(12);p.setPadding(new Insets(12));ColumnConstraints a=new ColumnConstraints();a.setMinWidth(155);a.setPrefWidth(160);ColumnConstraints b=new ColumnConstraints();b.setHgrow(Priority.ALWAYS);b.setFillWidth(true);p.getColumnConstraints().addAll(a,b);return p;}
    private static GridPane twoColumnGrid(){GridPane grid=new GridPane();grid.setHgap(16);grid.setVgap(16);ColumnConstraints left=new ColumnConstraints();left.setPercentWidth(50);left.setHgrow(Priority.ALWAYS);left.setFillWidth(true);ColumnConstraints right=new ColumnConstraints();right.setPercentWidth(50);right.setHgrow(Priority.ALWAYS);right.setFillWidth(true);grid.getColumnConstraints().setAll(left,right);return grid;}
    private static Label label(String text){return new Label(text);}
    private static Label title(String text){Label l=new Label(text);l.getStyleClass().add("event-card-title");l.setWrapText(true);return l;}
    private static Label body(String text){Label l=new Label(text==null?"—":text);l.getStyleClass().add("secondary-text");l.setWrapText(true);return l;}
    private static Button primary(String text,Runnable action){Button b=new Button(text);b.getStyleClass().add("primary-button");b.setOnAction(e->action.run());return b;}
    private static Button secondary(String text,Runnable action){Button b=new Button(text);b.getStyleClass().add("secondary-button");b.setOnAction(e->action.run());return b;}
    private static boolean confirm(String text){Alert a=new Alert(Alert.AlertType.CONFIRMATION,text,ButtonType.OK,ButtonType.CANCEL);a.setHeaderText("Please confirm");styleDialog(a);return a.showAndWait().orElse(ButtonType.CANCEL)==ButtonType.OK;}
    private static void info(String text){Alert a=new Alert(Alert.AlertType.INFORMATION,text);a.setHeaderText(null);styleDialog(a);a.showAndWait();}
    private static void error(String text){Alert a=new Alert(Alert.AlertType.ERROR,text);a.setHeaderText(null);styleDialog(a);a.showAndWait();}
    private static void styleDialog(Dialog<?> dialog){dialog.getDialogPane().getStyleClass().add("customer-dialog");dialog.getDialogPane().getStylesheets().add(CustomerMenuPages.class.getResource("/drawn/gui/styles/theme.css").toExternalForm());}
    private static <T> StringConverter<T> converter(java.util.function.Function<T,String> text){return new StringConverter<>(){@Override public String toString(T item){return item==null?"":text.apply(item);}@Override public T fromString(String value){return null;}};}
    private static LocalTime[] selectedTimes(ReservationPricing pricing, TimeInput start, TimeInput end) {
        return switch (pricing) {
            case HOURLY -> new LocalTime[]{start.value(), end.value()};
            case HALF_DAY_MORNING -> new LocalTime[]{LocalTime.of(6, 0), LocalTime.NOON};
            case HALF_DAY_AFTERNOON -> new LocalTime[]{LocalTime.NOON, LocalTime.of(18, 0)};
        };
    }
    private static final class TimeInput extends HBox {
        private final TextField time = new TextField();
        private final ToggleGroup period = new ToggleGroup();
        private final ToggleButton am = new ToggleButton("AM");
        private final ToggleButton pm = new ToggleButton("PM");

        private TimeInput(String initialTime, String initialPeriod) {
            super(6);
            time.setPromptText("HH:MM");
            time.setText(initialTime);
            time.setPrefColumnCount(5);
            time.focusedProperty().addListener((observable, wasFocused, focused) -> {
                if (!focused) normalizeTime();
            });
            am.setToggleGroup(period); pm.setToggleGroup(period);
            ("PM".equals(initialPeriod) ? pm : am).setSelected(true);
            am.setOnAction(event -> { if (!am.isSelected()) am.setSelected(true); });
            pm.setOnAction(event -> { if (!pm.isSelected()) pm.setSelected(true); });
            am.getStyleClass().add("time-period-button"); pm.getStyleClass().add("time-period-button");
            getChildren().addAll(time, am, pm);
            setAlignment(Pos.CENTER_LEFT);
            getStyleClass().add("time-input-row");
        }

        private LocalTime value() {
            String input = time.getText() == null ? "" : time.getText().trim();
            if (!input.matches("(?:0?[1-9]|1[0-2]):[0-5][0-9]"))
                throw new IllegalArgumentException("Enter time as HH:MM, then choose AM or PM.");
            String[] parts = input.split(":");
            int hour = Integer.parseInt(parts[0]) % 12;
            if (pm.isSelected()) hour += 12;
            return LocalTime.of(hour, Integer.parseInt(parts[1]));
        }

        private void normalizeTime() {
            String input = time.getText() == null ? "" : time.getText().trim();
            if (!input.matches("(?:0?[1-9]|1[0-2]):[0-5][0-9]")) return;
            String[] parts = input.split(":");
            time.setText(String.format(java.util.Locale.ROOT, "%02d:%02d",
                    Integer.parseInt(parts[0]), Integer.parseInt(parts[1])));
        }

        private void onChange(Runnable action) {
            time.textProperty().addListener((observable, oldValue, newValue) -> action.run());
            period.selectedToggleProperty().addListener((observable, oldValue, newValue) -> action.run());
        }
    }
    private static String money(BigDecimal n){return n.setScale(2,java.math.RoundingMode.HALF_UP).toPlainString();}
    private static String nullText(Object value){return value==null?"Not provided":value.toString();}
    private static String message(Exception e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
}
