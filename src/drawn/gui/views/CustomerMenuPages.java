package drawn.gui.views;

import drawn.gui.AppServices;
import drawn.gui.AppSession;
import drawn.model.event.*;
import drawn.model.payment.PaymentStatus;
import drawn.model.ticket.TicketType;
import drawn.service.event.ReservationService;
import drawn.storage.AccountStore;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.shape.Rectangle;
import javafx.util.StringConverter;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Builds customer workflow pages. Mutations are delegated to the established services. */
final class CustomerMenuPages {
    private static final List<DemoReview> DEMO_REVIEWS = List.of(
            new DemoReview("Demo Guest 01", 5, "A welcoming setting made it easy for our group to relax and enjoy the occasion."),
            new DemoReview("Demo Guest 02", 4, "The evening felt well paced, and the space gave everyone room to connect."),
            new DemoReview("Demo Guest 03", 3, "A pleasant example of the kind of venue details customers might share."),
            new DemoReview("Demo Guest 04", 5, "A polished atmosphere and a memorable backdrop for a special gathering."));
    private final AppServices services;
    private final AppSession session;
    private final Consumer<String> navigate;
    private final Consumer<Venue> venueRequest;
    private final Runnable backNavigation;
    private Venue detailVenue;
    private String venueSearchText = "";
    private String venueCategoryFilter = "All categories";
    private String venueEligibilityFilter = "Any event type";
    private String eventSearchText = "";
    private String eventCategoryFilter = "All Events";
    private boolean ownerEventsCollapsed;
    private TextField joinKeywordField;

    CustomerMenuPages(AppServices services, AppSession session, Consumer<String> navigate,
                      Consumer<Venue> venueRequest, Runnable backNavigation) {
        this.services = services; this.session = session; this.navigate = navigate;
        this.venueRequest = venueRequest; this.backNavigation = backNavigation;
    }

    Node page(String name) {
        try {
            return switch (name) {
                case "Explore Events" -> exploreEvents();
                case "Explore Venues" -> exploreVenues();
                case "Venue Details" -> venueDetails();
                case "My Events" -> myEvents();
                case "Venue Reservations" -> reservations();
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
        List<Event> owned = services.eventService().eventsFor(session.customer()).stream()
                .filter(event -> event.approvalStatus() == EventApprovalStatus.PUBLISHED).toList();
        java.util.Set<String> ownedIds = owned.stream().map(Event::id).collect(java.util.stream.Collectors.toSet());
        List<Event> publicEvents = services.publishing().publishedEvents().stream()
                .filter(event -> !ownedIds.contains(event.id())).toList();

        TextField search = new TextField();
        search.setId("explore-event-search");
        search.setPromptText("Search events by name or type");
        search.setAccessibleText("Search public events by name, type, description, or venue");
        search.setText(eventSearchText);
        search.getStyleClass().add("event-search");
        HBox searchRow = new HBox(search);
        searchRow.getStyleClass().add("explore-event-search-row");
        HBox.setHgrow(search, Priority.ALWAYS);
        search.setMaxWidth(Double.MAX_VALUE);

        ToggleGroup eventFilters = new ToggleGroup();
        HBox filterRow = new HBox(8);
        filterRow.getStyleClass().add("event-category-filters");
        for (String category : List.of("All Events", "Upcoming", "Concerts", "Parties", "Seminars", "Other")) {
            ToggleButton filter = new ToggleButton(category);
            filter.setToggleGroup(eventFilters);
            filter.setId("event-filter-" + category.toLowerCase(java.util.Locale.ROOT).replace(' ', '-'));
            filter.getStyleClass().add("event-filter");
            filter.setUserData(category);
            if (category.equals(eventCategoryFilter)) eventFilters.selectToggle(filter);
            filterRow.getChildren().add(filter);
        }
        joinKeywordField = new TextField();
        joinKeywordField.setId("explore-keyword");
        joinKeywordField.setPromptText("Enter invitation keyword");
        joinKeywordField.setAccessibleText("Join a private event with its invitation keyword");
        joinKeywordField.setOnAction(event -> loadPrivateEvents(joinKeywordField.getText(), privateResults));

        FlowPane ownedCards = new FlowPane(16, 16);
        ownedCards.setId("owned-event-grid");
        ownedCards.getStyleClass().add("event-grid");
        ownedCards.setMaxWidth(Double.MAX_VALUE);
        ownedCards.getChildren().setAll(owned.stream().map(event -> eventCard(event, true)).toList());
        resizeCards(ownedCards);
        if (owned.isEmpty()) ownedCards.getChildren().add(body("You haven't published any events yet."));
        ownedCards.setVisible(!ownerEventsCollapsed);
        ownedCards.setManaged(!ownerEventsCollapsed);
        Button collapseOwned = secondary(ownerEventsCollapsed ? "Show my events" : "Hide my events", () -> { });
        collapseOwned.setOnAction(event -> {
            ownerEventsCollapsed = !ownerEventsCollapsed;
            collapseOwned.setText(ownerEventsCollapsed ? "Show my events" : "Hide my events");
            ownedCards.setVisible(!ownerEventsCollapsed);
            ownedCards.setManaged(!ownerEventsCollapsed);
        });
        collapseOwned.setId("toggle-my-events");
        Label ownedCount = new Label(Integer.toString(owned.size()));
        ownedCount.getStyleClass().add("event-count-pill");
        HBox ownedHeading = new HBox(10, title("Your Published Events"), ownedCount, new Region(), collapseOwned);
        HBox.setHgrow(ownedHeading.getChildren().get(2), Priority.ALWAYS);
        VBox ownedSection = new VBox(6, ownedHeading,
                body("Events you're organizing. Manage them here or in My Events."), ownedCards);
        ownedSection.getStyleClass().add("event-owner-section");

        FlowPane publicCards = new FlowPane(16, 16);
        publicCards.setId("public-event-grid");
        publicCards.getStyleClass().add("event-grid");
        publicCards.setMaxWidth(Double.MAX_VALUE);
        Runnable refreshPublic = () -> {
            String query = search.getText() == null ? "" : search.getText().trim().toLowerCase(java.util.Locale.ROOT);
            String category = eventFilters.getSelectedToggle() == null ? "All Events"
                    : (String) eventFilters.getSelectedToggle().getUserData();
            eventSearchText = search.getText() == null ? "" : search.getText().trim();
            eventCategoryFilter = category;
            List<Event> matches = publicEvents.stream().filter(event -> {
                boolean matchesQuery = query.isBlank()
                        || event.name().toLowerCase(java.util.Locale.ROOT).contains(query)
                        || CustomerText.words(event.type()).toLowerCase(java.util.Locale.ROOT).contains(query)
                        || event.description().toLowerCase(java.util.Locale.ROOT).contains(query)
                        || event.venue().name().toLowerCase(java.util.Locale.ROOT).contains(query);
                boolean matchesCategory = switch (category) {
                    case "Upcoming" -> !event.date().isBefore(LocalDate.now());
                    case "Concerts" -> event.type() == EventType.CONCERT;
                    case "Parties" -> event.type() == EventType.PARTY;
                    case "Seminars" -> event.type() == EventType.SEMINAR;
                    case "Other" -> event.type() == EventType.OTHER;
                    default -> true;
                };
                return matchesQuery && matchesCategory;
            }).toList();
            publicCards.getChildren().setAll(matches.stream().map(event -> eventCard(event, false)).toList());
            resizeCards(publicCards);
            if (matches.isEmpty()) {
                boolean filtered = !query.isBlank() || !category.equals("All Events");
                VBox empty = new VBox(8,
                        title(filtered ? "No events match your search." : "No public events available right now."),
                        body(filtered ? "Try changing your search or category." : "Check back soon for upcoming experiences."));
                empty.setAlignment(Pos.CENTER);
                empty.setPadding(new Insets(32));
                empty.getStyleClass().add("empty-state");
                publicCards.getChildren().add(empty);
                if (filtered) {
                    Button clear = secondary("Clear Filters", () -> {
                        search.clear();
                        for (Toggle toggle : eventFilters.getToggles()) {
                            if ("All Events".equals(toggle.getUserData())) { eventFilters.selectToggle(toggle); break; }
                        }
                    });
                    clear.setId("clear-event-filters");
                    publicCards.getChildren().add(clear);
                }
            }
        };
        publicCards.widthProperty().addListener((o, old, width) -> resizeCards(publicCards));
        ownedCards.widthProperty().addListener((o, old, width) -> resizeCards(ownedCards));
        search.textProperty().addListener((observable, oldValue, newValue) -> refreshPublic.run());
        eventFilters.selectedToggleProperty().addListener((observable, oldValue, newValue) -> refreshPublic.run());
        refreshPublic.run();
        privateResults = new VBox(12);
        privateResults.setId("private-event-results");
        Button joinPrivate = primary("Join Event", () -> loadPrivateEvents(joinKeywordField.getText(), privateResults));
        joinPrivate.setId("explore-find");
        HBox inviteControls = new HBox(10, joinKeywordField, joinPrivate);
        inviteControls.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(joinKeywordField, Priority.ALWAYS);
        joinKeywordField.setMaxWidth(Double.MAX_VALUE);
        VBox invitePanel = new VBox(8, title("Join a Private Event"),
                body("Enter your invitation keyword to access a private event."), inviteControls);
        invitePanel.getStyleClass().add("private-invitation-panel");
        VBox discoverHeading = new VBox(4, title("Discover Events"),
                body("Explore and get tickets to upcoming events from other organizers."));
        VBox content = new VBox(14, title("Explore Events"), body("Browse public events or join a private event with an invitation code."),
                searchRow, filterRow, ownedSection, invitePanel, privateResults, discoverHeading, publicCards);
        return pageFrame("Explore Events", content);
    }

    private VBox privateResults;

    void focusJoinField() {
        if (joinKeywordField != null) javafx.application.Platform.runLater(joinKeywordField::requestFocus);
    }

    private void loadPrivateEvents(String keyword, VBox results) {
        results.getChildren().clear();
        try {
            String code = Ui.required(keyword, "Invitation keyword");
            List<Event> matches = services.workflow().privateEvents(code);
            results.getChildren().add(title("Private events"));
            if (matches.isEmpty()) results.getChildren().add(body("No private events matched that keyword."));
            else {
                FlowPane cards = new FlowPane(16, 16);
                cards.getStyleClass().add("event-grid");
                cards.setMaxWidth(Double.MAX_VALUE);
                cards.getChildren().setAll(matches.stream().map(event -> eventCard(event, false)).toList());
                cards.widthProperty().addListener((o, old, width) -> resizeCards(cards));
                resizeCards(cards);
                results.getChildren().add(cards);
            }
        } catch (Exception ex) {
            results.getChildren().setAll(title("Private events"), body(message(ex)));
        }
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
        search.setId("venue-search");
        category.setId("venue-category-filter");
        eligibility.setId("venue-event-filter");
        FlowPane cards = new FlowPane(16, 16);
        cards.getStyleClass().add("venue-grid");
        cards.setPrefWrapLength(1050);
        cards.setMaxWidth(Double.MAX_VALUE);
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
            cards.getChildren().setAll(matches.stream().map(this::venueCard).toList());
            resizeVenueCards(cards);
            if (matches.isEmpty()) cards.getChildren().add(emptyState("No venues match those filters."));
        };
        cards.widthProperty().addListener((o, old, width) -> resizeVenueCards(cards));
        search.textProperty().addListener((o,a,b) -> { venueSearchText = b == null ? "" : b; refresh.run(); });
        category.valueProperty().addListener((o,a,b) -> { venueCategoryFilter = b == null ? "All categories" : b; refresh.run(); });
        eligibility.valueProperty().addListener((o,a,b) -> { venueEligibilityFilter = b == null ? "Any event type" : b; refresh.run(); });
        refresh.run();
        VBox content = new VBox(20, title("Explore Venues"),
                body("Find the perfect setting for your next event."), filters, cards);
        cards.prefWidthProperty().bind(content.widthProperty());
        return pageFrame("Explore Venues", content);
    }

    private VBox venueCard(Venue venue) {
        VBox card = new VBox(12);
        card.getStyleClass().addAll("venue-card", "editorial-card", "event-card");
        card.setMinWidth(220);
        card.setMaxWidth(Double.MAX_VALUE);
        card.getChildren().addAll(venuePlaceholder(venue, "venue-image"),
                new Label(CustomerText.words(VenueCatalog.categoryOf(venue))), title(venue.name()),
                body(supportedEvents(venue)),
                body((venue.capacity() == Integer.MAX_VALUE ? "Capacity not specified" : "Up to " + venue.capacity() + " guests")
                + "  •  Starts at " + startingRate(venue).label()));
        card.getChildren().get(1).getStyleClass().add("venue-category");
        Button details = secondary("View Details", () -> showVenueDetails(venue));
        details.setId("venue-details-" + VenueCatalog.all().indexOf(venue));
        Button select = primary("Request a Venue", () -> venueRequest.accept(venue));
        select.setId("venue-select-" + VenueCatalog.all().indexOf(venue));
        HBox actions = new HBox(8, select, details);
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.getStyleClass().add("venue-card-actions");
        equalActionWidths(actions);
        card.getChildren().add(actions);
        card.setOnMouseClicked(event -> {
            if (!(event.getTarget() instanceof Button)) showVenueDetails(venue);
        });
        card.setOnMouseEntered(event -> card.setCursor(javafx.scene.Cursor.HAND));
        card.setOnMouseExited(event -> card.setCursor(javafx.scene.Cursor.DEFAULT));
        return card;
    }

    private void showVenueDetails(Venue venue) {
        detailVenue = venue;
        navigate.accept("Venue Details");
    }

    private Node venueDetails() {
        if (detailVenue == null) return exploreVenues();
        Venue v = detailVenue;
        StackPane photo = venueDetailPhoto(v);
        Label eyebrow = new Label("VENUE COLLECTION  /  "
                + CustomerText.words(VenueCatalog.categoryOf(v)).toUpperCase(java.util.Locale.ROOT));
        eyebrow.getStyleClass().add("venue-category");
        VBox left = new VBox(14, photo, eyebrow, title(v.name()),
                body("Supported events  •  " + supportedEvents(v)),
                body("Guest capacity  •  " + (v.capacity() == Integer.MAX_VALUE ? "Not specified" : v.capacity() + " guests")),
                body("Venue description, facilities, and operating hours have not been provided in the catalog."));
        left.getStyleClass().add("venue-detail-left");

        Button reserve = primary("Reserve This Venue", () -> venueRequest.accept(v));
        reserve.setId("venue-detail-reserve");
        reserve.setMaxWidth(Double.MAX_VALUE);
        String googleMapsUrl = googleMapsSearchUrl(v);
        Hyperlink mapsLink = new Hyperlink("Open Google Maps");
        mapsLink.setOnAction(event -> openExternalLink(googleMapsUrl));
        Label demoStars = new Label("★★★★★  4.6 / 5  ·  Demo preview");
        demoStars.getStyleClass().add("demo-rating-stars");
        VBox ratingBlock = new VBox(5, demoStars,
                body("Fictional preview only. This is not a Google rating or venue customer data."), mapsLink);
        ratingBlock.getStyleClass().add("google-rating-block");
        VBox rates = new VBox(8, sectionHeading("Reservation rates"),
                body("Hourly  •  ₱" + money(v.hourlyRate()) + " per hour (06:00–23:00)"),
                body("Morning half-day  •  ₱" + money(v.morningRate()) + " (06:00–12:00)"),
                body("Afternoon half-day  •  ₱" + money(v.afternoonRate()) + " (12:00–18:00)"));
        VBox booking = new VBox(9, reserve,
                body("Full wallet payment is required. The reservation remains pending until an administrator reviews it."));
        booking.getStyleClass().add("venue-detail-booking");
        Hyperlink reviewsLink = new Hyperlink("View reviews on Google Maps");
        reviewsLink.setOnAction(event -> openExternalLink(googleMapsUrl));
        VBox reviewCards = new VBox(10);
        for (DemoReview review : DEMO_REVIEWS) reviewCards.getChildren().add(demoReviewCard(review));
        VBox reviews = new VBox(10, sectionHeading("Customer Reviews"),
                body("Fictional demo reviews for layout preview. They are not from Google and are not reviews of this venue."),
                reviewCards, reviewsLink);
        reviews.getStyleClass().add("venue-reviews");
        VBox right = new VBox(20, sectionHeading("A setting for your next gathering"), ratingBlock, rates, booking, reviews);
        right.getStyleClass().add("venue-detail-right");
        left.setMinWidth(0); right.setMinWidth(0);
        left.setMaxWidth(Double.MAX_VALUE); right.setMaxWidth(Double.MAX_VALUE);
        FlowPane layout = new FlowPane(24, 20, left, right);
        layout.getStyleClass().add("venue-detail-layout");
        layout.setMaxWidth(Double.MAX_VALUE);
        HBox breadcrumb = new HBox(8, BackNavigation.button(backNavigation),
                Ui.muted("Explore Venues  ›  Venue Details"));
        breadcrumb.setAlignment(Pos.CENTER_LEFT);
        VBox content = new VBox(16, breadcrumb, layout);
        content.getStyleClass().add("venue-detail-page");
        layout.prefWidthProperty().bind(content.widthProperty());
        layout.widthProperty().addListener((o, oldWidth, width) -> resizeDetailColumns(layout, left, right));
        resizeDetailColumns(layout, left, right);
        return pageFrame("Venue Details", content);
    }

    private static void resizeDetailColumns(FlowPane layout, VBox left, VBox right) {
        double width = layout.getWidth();
        if (width <= 0) return;
        double columnWidth = width < 760 ? width : (width - layout.getHgap()) / 2;
        left.setPrefWidth(columnWidth);
        right.setPrefWidth(columnWidth);
    }

    private static VBox demoReviewCard(DemoReview review) {
        Label stars = new Label("★".repeat(review.rating()) + "☆".repeat(5 - review.rating()));
        stars.getStyleClass().add("demo-review-stars");
        Label author = new Label(review.author() + "  ·  Sample reviewer");
        author.getStyleClass().add("demo-review-author");
        VBox card = new VBox(7, stars, author, body(review.text()), body("Illustrative sample · no real posting date"));
        card.getStyleClass().add("demo-review-card");
        return card;
    }

    private static StackPane venueDetailPhoto(Venue venue) {
        Label placeholder = new Label(CustomerText.words(VenueCatalog.categoryOf(venue)).toUpperCase(java.util.Locale.ROOT));
        placeholder.getStyleClass().add("venue-placeholder-label");
        StackPane frame = new StackPane(placeholder);
        frame.setAlignment(Pos.CENTER);
        frame.getStyleClass().addAll("venue-image", "venue-detail-image");
        frame.setMinHeight(240);
        frame.prefHeightProperty().bind(frame.widthProperty().multiply(0.72));

        String slug = venue.name().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        java.net.URL asset = CustomerMenuPages.class.getResource("/drawn/gui/images/venues/" + slug + ".jpg");
        if (asset == null) asset = CustomerMenuPages.class.getResource("/drawn/gui/images/venues/" + slug + ".png");
        if (asset != null) {
            Image image = new Image(asset.toExternalForm(), true);
            ImageView photo = new ImageView(image);
            photo.setPreserveRatio(true);
            photo.setSmooth(true);
            photo.fitWidthProperty().bind(frame.widthProperty());
            photo.fitHeightProperty().bind(frame.heightProperty());
            image.progressProperty().addListener((o, previousProgress, progress) -> {
                if (progress.doubleValue() >= 1 && !image.isError()) {
                    frame.getChildren().add(0, photo);
                    placeholder.setVisible(false);
                }
            });
            if (image.getProgress() >= 1 && !image.isError()) {
                frame.getChildren().add(0, photo);
                placeholder.setVisible(false);
            }
        }
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(frame.widthProperty());
        clip.heightProperty().bind(frame.heightProperty());
        clip.setArcWidth(18); clip.setArcHeight(18);
        frame.setClip(clip);
        return frame;
    }

    private static String googleMapsSearchUrl(Venue venue) {
        String query = URLEncoder.encode(venue.name() + ", Iloilo, Philippines", StandardCharsets.UTF_8);
        return "https://www.google.com/maps/search/?api=1&query=" + query;
    }

    private record DemoReview(String author, int rating, String text) { }

    private static void openExternalLink(String url) {
        try { java.awt.Desktop.getDesktop().browse(java.net.URI.create(url)); }
        catch (Exception ex) { Ui.error("Could not open Google Maps", "Open this link in your browser: " + url); }
    }

    private static VBox venuePlaceholder(Venue venue, String style) {
        Label mark = new Label(CustomerText.words(VenueCatalog.categoryOf(venue)).toUpperCase(java.util.Locale.ROOT));
        mark.getStyleClass().add("venue-placeholder-label");
        VBox image = new VBox(mark);
        image.setAlignment(Pos.CENTER);
        image.getStyleClass().addAll("venue-image", style);
        return image;
    }

    private static String supportedEvents(Venue venue) {
        return venue.type() == VenueType.BOTH ? "Social gatherings  •  Ticketed events"
                : venue.type() == VenueType.COMMERCIAL ? "Ticketed events" : "Social gatherings";
    }

    private static Label sectionHeading(String text) {
        Label label = new Label(text); label.getStyleClass().add("editorial-section"); return label;
    }

    private static void resizeVenueCards(FlowPane cards) {
        double width = cards.getWidth();
        if (width <= 0) return;
        int columns = width >= 560 ? 2 : 1;
        double cardWidth = Math.max(220, (width - (columns - 1) * cards.getHgap()) / columns - 8);
        cards.getChildren().stream().filter(VBox.class::isInstance).map(VBox.class::cast)
                .forEach(card -> card.setPrefWidth(cardWidth));
    }

    private static void resizeCards(FlowPane cards) {
        double width = cards.getWidth();
        if (width <= 0) return;
        int columns = width >= 560 ? 2 : 1;
        double cardWidth = Math.max(220, (width - (columns - 1) * cards.getHgap() - 8) / columns);
        cards.getChildren().stream().filter(VBox.class::isInstance).map(VBox.class::cast)
                .forEach(card -> card.setPrefWidth(cardWidth));
    }

    private static StartingRate startingRate(Venue venue) {
        return List.of(new StartingRate(venue.hourlyRate(), "per hour"),
                        new StartingRate(venue.morningRate(), "morning half-day"),
                        new StartingRate(venue.afternoonRate(), "afternoon half-day"))
                .stream().min(java.util.Comparator.comparing(StartingRate::amount)).orElseThrow();
    }

    private record StartingRate(BigDecimal amount, String basis) {
        String label() { return "₱" + money(amount) + " (" + basis + ")"; }
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
        for (VenueReservation r : records) {
            VBox card = dataCard();
            card.getChildren().addAll(title(r.venueName()), body(CustomerText.reservationSchedule(r)),
                    body("Fee: ₱" + money(r.cost()) + " • Reservation: " + CustomerText.bookingStatus(r.status()) + " • Payment: " + CustomerText.words(r.paymentStatus())));
            if (!r.eventId().isBlank()) card.getChildren().add(body("Linked event: " + r.eventId()));
            if (!r.declineReason().isBlank()) card.getChildren().add(body("Reason: " + r.declineReason()));
            if (r.status() != BookingStatus.CANCELLED && r.status() != BookingStatus.DECLINED && r.status() != BookingStatus.EXPIRED)
                card.getChildren().add(secondary("Cancel Reservation", () -> cancelReservation(r)));
            content.getChildren().add(card);
        }
        return pageFrame("Venue Reservations", content);
    }

    private Node profile() throws IOException {
        var customer = session.customer();
        Label initials = new Label(profileInitials(customer.getName()));
        initials.getStyleClass().add("profile-avatar");
        VBox banner = new VBox();
        banner.getStyleClass().add("profile-banner");
        Label posts = new Label(String.valueOf(services.eventService().eventsFor(customer).size()));
        Label tickets = new Label(String.valueOf(services.publishing().purchases(customer).size()));
        Label organizerLevel = new Label(CustomerText.words(customer.getOrganizerLevel()));
        VBox identity = new VBox(5, title(customer.getName()), body("DrawN customer  •  " + organizerLevel.getText()));
        HBox stats = new HBox(26, profileStat("EVENTS", posts.getText()), profileStat("TICKETS", tickets.getText()),
                profileStat("ORGANIZER", organizerLevel.getText()));
        stats.setAlignment(Pos.CENTER_LEFT);
        HBox hero = new HBox(18, initials, identity, Ui.grow(), stats);
        hero.setAlignment(Pos.CENTER_LEFT);
        hero.getStyleClass().add("profile-hero-content");
        VBox heroCard = new VBox(banner, hero);
        heroCard.getStyleClass().add("profile-hero-card");
        Label profileSection = new Label("Profile details");
        profileSection.getStyleClass().add("profile-section-label");
        VBox details = new VBox(12, Ui.section("Personal information"), Ui.details("Account ID", customer.getId(),
                "Email", nullText(customer.getEmail()),
                "Phone", nullText(customer.getPhoneNumber()),
                "Date of birth", nullText(customer.getDateOfBirth()),
                "Contact preference", CustomerText.words(customer.getContactMethod())));
        details.getStyleClass().add("profile-card");
        VBox content = new VBox(14, title("My Profile"), body("Your DrawN identity, account details, and event activity."),
                heroCard, profileSection, details);
        return pageFrame("My Profile", content);
    }

    private static VBox profileStat(String label, String value) {
        Label amount = new Label(value);
        amount.getStyleClass().add("profile-stat-value");
        Label caption = new Label(label);
        caption.getStyleClass().add("profile-stat-label");
        return new VBox(3, amount, caption);
    }

    private Node eventCard(Event event, boolean owned) {
        VBox card = dataCard();
        card.getChildren().addAll(title(event.name()), body(CustomerText.words(event.type()) + " • " + event.date() + " • " + CustomerText.time(event.startTime()) + "–" + CustomerText.time(event.endTime())),
                body("Venue: " + event.venue().name()), body(event.description()));
        if (event.purpose() == EventPurpose.TICKETED_EVENT) {
            try {
                List<TicketType> types = services.publishing().ticketTypes(event.id());
                for (TicketType type : types) card.getChildren().add(body(type.name() + " • ₱" + money(type.price()) + " • " + type.remaining() + " available"));
            } catch (IOException ex) { card.getChildren().add(body("Ticket details unavailable: " + message(ex))); }
        }
        Button details = secondary("View Details", () -> new EventDetailsView(event, services, session, () -> navigate.accept("Explore Events")).show());
        HBox actions = new HBox(8, details);
        if (owned) {
            actions.getChildren().addAll(secondary("Edit Event", () -> new EditEventDialog(services, session, event,
                            () -> navigate.accept("Explore Events")).show()),
                    danger("Cancel Event", () -> cancelEvent(event)));
        }
        actions.getStyleClass().add("event-card-actions");
        equalActionWidths(actions);
        card.getChildren().add(actions);
        return card;
    }

    private static void equalActionWidths(HBox actions) {
        for (Node node : actions.getChildren()) if (node instanceof Button button) {
            HBox.setHgrow(button, Priority.ALWAYS);
            button.setMaxWidth(Double.MAX_VALUE);
        }
    }

    private void requestVenue(Venue selectedVenue) {
        Dialog<ButtonType> dialog = new Dialog<>(); dialog.setTitle("Request a venue");
        ButtonType submit = new ButtonType("Review and Pay", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(submit, ButtonType.CANCEL);
        ComboBox<EventPurpose> purpose = new ComboBox<>();
        purpose.getItems().setAll(java.util.Arrays.stream(EventPurpose.values()).filter(selectedVenue::supports).toList());
        purpose.setConverter(converter(CustomerText::words)); purpose.getSelectionModel().selectFirst();
        VBox selectedVenueSummary = new VBox(4, title(selectedVenue.name()),
                body(CustomerText.words(VenueCatalog.categoryOf(selectedVenue)) + " • "
                        + (selectedVenue.type() == VenueType.BOTH ? "Social and ticketed events"
                        : selectedVenue.type() == VenueType.COMMERCIAL ? "Ticketed events" : "Social gatherings")),
                body("Capacity: " + (selectedVenue.capacity() == Integer.MAX_VALUE ? "Not specified" : selectedVenue.capacity() + " guests")));
        DatePicker date = new DatePicker(LocalDate.now().plusDays(1)); date.setDayCellFactory(picker -> new DateCell() { @Override public void updateItem(LocalDate d, boolean empty) { super.updateItem(d,empty); setDisable(empty || d.isBefore(LocalDate.now())); }});
        TimeInput start = new TimeInput("10:00", "AM"), end = new TimeInput("12:00", "PM");
        HBox startRow = new HBox(12, label("Start time"), start);
        HBox endRow = new HBox(12, label("End time"), end);
        startRow.setAlignment(Pos.CENTER_LEFT);
        endRow.setAlignment(Pos.CENTER_LEFT);
        ComboBox<ReservationPricing> pricing = new ComboBox<>(); pricing.getItems().setAll(ReservationPricing.values()); pricing.setConverter(converter(CustomerText::words)); pricing.getSelectionModel().selectFirst();
        GridPane fields = formGrid();
        fields.addRow(0,label("Event purpose"),purpose); fields.addRow(1,label("Selected venue"),selectedVenueSummary); fields.addRow(2,label("Date"),date);
        Label pricingLabel = label("Pricing");
        Label total = title(""); Label error = AuthenticationFormSupport.errorLabel();
        Runnable updateTimeRows = () -> {
            boolean hourly = pricing.getValue() == ReservationPricing.HOURLY;
            // Remove the complete time rows for half-day bookings, including their labels
            // and GridPane row gaps. Merely hiding the inputs leaves empty-looking rows.
            fields.getChildren().removeAll(startRow, endRow, pricingLabel, pricing, total, error);
            int pricingRow = 3;
            if (hourly) {
                fields.add(startRow, 0, 3, 2, 1);
                fields.add(endRow, 0, 4, 2, 1);
                pricingRow = 5;
            }
            fields.add(pricingLabel, 0, pricingRow);
            fields.add(pricing, 1, pricingRow);
            fields.add(total, 0, pricingRow + 1, 2, 1);
            fields.add(error, 0, pricingRow + 2, 2, 1);
        };
        Runnable quote = () -> {
            try {
                LocalTime[] times = selectedTimes(pricing.getValue(), start, end);
                total.setText("Fee: ₱" + money(ReservationService.price(selectedVenue, times[0], times[1], pricing.getValue())));
                error.setText("");
            } catch (RuntimeException ex) { total.setText("Fee: —"); error.setText(message(ex)); }
        };
        start.onChange(quote); end.onChange(quote);
        pricing.valueProperty().addListener((o,a,b)->{updateTimeRows.run();quote.run();});
        updateTimeRows.run(); quote.run();
        VBox formContent = new VBox(12, new HBox(8,
                BackNavigation.button(() -> { dialog.close(); navigate.accept("Explore Venues"); }),
                body("Explore Venues  ›  Request a Venue")), fields);
        dialog.getDialogPane().setContent(formContent); styleDialog(dialog);
        dialog.getDialogPane().lookupButton(submit).addEventFilter(ActionEvent.ACTION, action -> {
            action.consume();
            try {
                Venue validatedVenue = VenueCatalog.all().stream().filter(v -> v.equals(selectedVenue)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("The selected venue is no longer available."));
                if (purpose.getValue() == null || !validatedVenue.supports(purpose.getValue()) || date.getValue()==null)
                    throw new IllegalArgumentException("Choose an eligible event purpose and date.");
                LocalTime[] times = selectedTimes(pricing.getValue(), start, end);
                BigDecimal amount=ReservationService.price(validatedVenue,times[0],times[1],pricing.getValue());
                if(session.account().getWalletBalance().compareTo(amount)<0) throw new IllegalArgumentException("Insufficient wallet balance.");
                if(confirm("Pay ₱" + money(amount) + " from your wallet?")) {
                    var button=dialog.getDialogPane().lookupButton(submit);button.setDisable(true);
                    services.venueWorkflow().requestAndPay(session.customer(),session.account(),validatedVenue,date.getValue(),times[0],times[1],pricing.getValue());
                    dialog.setResult(submit);dialog.close();info("Payment complete. Reservation status: PENDING • Payment status: PAID.");navigate.accept("Venue Reservations");
                }
            } catch(Exception ex){error.setText(message(ex));}
        });
        dialog.showAndWait();
    }

    private void showEventForm(List<VenueReservation> eligible) {
        Dialog<ButtonType> dialog=new Dialog<>();dialog.setTitle("Create Event from Approved Reservation");
        ButtonType submit=new ButtonType("Create Event",ButtonBar.ButtonData.OK_DONE);dialog.getDialogPane().getButtonTypes().addAll(submit,ButtonType.CANCEL);
        ComboBox<VenueReservation> reservation=new ComboBox<>();reservation.getItems().setAll(eligible);reservation.setMaxWidth(Double.MAX_VALUE);
        reservation.setConverter(converter(r->r.venueName()+" • "+r.date()+" • "+CustomerText.bookingStatus(r.status())));reservation.getSelectionModel().selectFirst();
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
        Label error=AuthenticationFormSupport.errorLabel();VBox form=new VBox(10,
                new HBox(8, BackNavigation.button(() -> { dialog.close(); navigate.accept("My Events"); }), body("My Events  ›  Create Event")),
                label("Approved reservation"),reservation,reserved,
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
        TextField n=new TextField();n.setPromptText("Category name");TextField price=new TextField();price.setPromptText("Price ₱");TextField qty=new TextField();qty.setPromptText("Quantity");
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
    private Venue venueFor(VenueReservation r) { return VenueCatalog.all().stream().filter(v->v.name().equals(r.venueName())).findFirst().orElseThrow(()->new IllegalArgumentException("Reserved venue details are unavailable.")); }
    private Node pageFrame(String page,Node content){ScrollPane scroll=new ScrollPane(content);scroll.setFitToWidth(true);scroll.getStyleClass().add("content-scroll");scroll.setPadding(new Insets(0));BorderPane frame=new BorderPane(scroll);frame.getStyleClass().add("content-page");return frame;}
    private VBox dataCard(){VBox box=new VBox(10);box.getStyleClass().add("event-card");box.setMaxWidth(Double.MAX_VALUE);return box;}
    private VBox emptyState(String message){VBox box=new VBox(8,title("Nothing here yet"),body(message));box.setAlignment(Pos.CENTER);box.setPadding(new Insets(44));box.getStyleClass().add("empty-state");return box;}
    private static VBox row(String label,Node field){return new VBox(5,new Label(label),field);}
    private static GridPane formGrid(){GridPane p=new GridPane();p.setHgap(12);p.setVgap(10);p.setPadding(new Insets(12));ColumnConstraints a=new ColumnConstraints();a.setMinWidth(130);ColumnConstraints b=new ColumnConstraints();b.setHgrow(Priority.ALWAYS);p.getColumnConstraints().addAll(a,b);return p;}
    private static Label label(String text){return new Label(text);}
    private static Label title(String text){Label l=new Label(text);l.getStyleClass().add("event-card-title");l.setWrapText(true);return l;}
    private static Label body(String text){Label l=new Label(text==null?"—":text);l.getStyleClass().add("secondary-text");l.setWrapText(true);return l;}
    private static Button primary(String text,Runnable action){Button b=new Button(text);b.getStyleClass().add("primary-button");b.setOnAction(e->action.run());return b;}
    private static Button secondary(String text,Runnable action){Button b=new Button(text);b.getStyleClass().add("secondary-button");b.setOnAction(e->action.run());return b;}
    private static Button danger(String text,Runnable action){Button b=new Button(text);b.getStyleClass().add("danger-button");b.setOnAction(e->action.run());return b;}
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
    private static String profileInitials(String name) {
        if (name == null || name.isBlank()) return "?";
        String[] parts = name.trim().split("\\s+");
        return (parts[0].substring(0, 1) + (parts.length > 1 ? parts[parts.length - 1].substring(0, 1) : ""))
                .toUpperCase(java.util.Locale.ROOT);
    }
    private static String nullText(Object value){return value==null?"Not provided":value.toString();}
    private static String message(Exception e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
}
