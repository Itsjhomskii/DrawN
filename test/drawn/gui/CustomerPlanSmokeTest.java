package drawn.gui.views;

import drawn.gui.AppServices;
import drawn.gui.AppSession;
import drawn.gui.DrawNApplication;
import drawn.gui.views.Ui;
import drawn.model.event.BookingStatus;
import drawn.model.event.EventDraft;
import drawn.model.event.Event;
import drawn.model.event.EventPurpose;
import drawn.model.event.EventType;
import drawn.model.event.EventVisibility;
import drawn.model.event.ReservationPricing;
import drawn.model.event.TicketCategory;
import drawn.model.event.Venue;
import drawn.model.event.VenueCatalog;
import drawn.model.event.VenueReservation;
import drawn.model.payment.PaymentStatus;
import drawn.model.user.ContactMethod;
import drawn.model.user.Customer;
import drawn.storage.AccountStore;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.math.BigDecimal;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Structural JavaFX smoke checks for customer routes and the requested discovery layouts. */
public final class CustomerPlanSmokeTest {
    private CustomerPlanSmokeTest() { }

    public static void main(String[] args) throws Exception {
        Path data = Files.createTempDirectory("drawn-customer-plan-smoke-");
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.startup(() -> {
            try { run(data); }
            catch (Throwable ex) { failure.set(ex); }
            finally { done.countDown(); }
        });
        if (!done.await(30, TimeUnit.SECONDS)) throw new AssertionError("Customer plan smoke test timed out.");
        Platform.runLater(Platform::exit);
        if (failure.get() != null) throw new AssertionError(failure.get());
        System.out.println("CustomerPlanSmokeTest passed.");
    }

    private static void run(Path data) {
        try {
            AppServices services = new AppServices(data);
            var registration = services.customerAccess().register("Customer", "Smoke", LocalDate.now().minusYears(30),
                    ContactMethod.EMAIL, "smoke@example.test", "not-used", new BigDecimal("20000.00"));
            Customer customer = registration.customer();
            AccountStore.Account account = registration.account();
            AppSession session = new AppSession(); session.start(account, customer);
            MainShell shell = new MainShell(new DrawNApplication(), services, session);
            Stage stage = new Stage();
            stage.setScene(new javafx.scene.Scene(shell.view(), 1280, 800));
            stage.getScene().getStylesheets().add(CustomerPlanSmokeTest.class
                    .getResource("/drawn/gui/styles/theme.css").toExternalForm());
            stage.show();
            Parent root = (Parent) stage.getScene().getRoot();

            Button toggle = button(root, "quick-actions-toggle");
            check(toggle.getLayoutX() > toggle.getParent().getLayoutBounds().getWidth() / 2,
                    "collapsed plus button is positioned on the right side");
            check(!toggle.getParent().contains(12, 12), "empty FAB overlay area allows page clicks through");
            toggle.fire();
            check(button(root, "quick-create-event").isVisible(), "create action is revealed");
            Button joinAction = button(root, "quick-join-event");
            Button requestAction = button(root, "quick-request-venue");
            Button createAction = button(root, "quick-create-event");
            check(joinAction.getLayoutY() < requestAction.getLayoutY() && requestAction.getLayoutY() < createAction.getLayoutY(),
                    "radial actions follow the top-to-bottom arc order");
            check(joinAction.getLayoutX() > requestAction.getLayoutX() && requestAction.getLayoutX() > createAction.getLayoutX(),
                    "radial actions spread toward the left of the plus button");
            button(root, "quick-create-event").fire();
            check(shell.currentPage().equals(MainShell.CREATE_EVENT), "create action route");
            refresh(root);
            check(findById(root, "create-request-venue") != null, "create page explains venue prerequisite");
            button(root, "back-navigation").fire();
            check(shell.currentPage().equals("Home"), "create form back arrow returns to its previous page");

            button(root, "home-wallet-balance").fire();
            check(shell.currentPage().equals(MainShell.WALLET), "home wallet button remains clickable");

            shell.showPage("Home");
            refresh(root);
            button(root, "quick-actions-toggle").fire();
            button(root, "quick-request-venue").fire();
            check(shell.currentPage().equals("Explore Venues"), "venue request quick action opens the venue catalog first");

            shell.showPage("Home");
            refresh(root);
            button(root, "quick-actions-toggle").fire();
            button(root, "quick-join-event").fire();
            check(shell.currentPage().equals(MainShell.EXPLORE), "join action route");
            TextField search = field(shell.currentContent(), "explore-event-search");
            TextField keyword = field(shell.currentContent(), "explore-keyword");
            check(search != keyword, "public search and private keyword fields are separate");
            check(countStyle(shell.currentContent(), "event-filter") == 6,
                    "public event discovery has the six category pills without a duplicate filter menu");
            check(findStyle(shell.currentContent(), "private-invitation-panel") != null,
                    "private invitation workflow has a separate panel");
            check(findById(shell.currentContent(), "private-event-results") != null,
                    "private invitation results remain independent from public discovery");
            check(keyword.getAccessibleText() != null && keyword.getAccessibleText().contains("invitation keyword"),
                    "private keyword field has an accessibility label");
            snapshot(stage, "explore-events");

            shell.showPage("Explore Venues");
            refresh(root);
            snapshot(stage, "venue-explore");
            check(findStyle(shell.currentContent(), "event-card-title") != null, "venue cards include a title");
            check(countStyle(shell.currentContent(), "event-card") > 0, "venue cards are presented in the catalog");
            check(text(shell.currentContent()).contains("Starts at ₱"), "venue cards display a starting rate");

            shell.showPage("Explore Venues");
            refresh(root);
            field(shell.currentContent(), "venue-search").setText("Muelle");
            button(shell.currentContent(), "venue-details-5").fire();
            refresh(root);
            snapshot(stage, "venue-details");
            check(text(shell.currentContent()).contains("Reservation rates"), "venue details show catalog-backed rates");
            check(text(shell.currentContent()).contains("Fictional preview only"), "demo rating is explicitly identified as fictional");
            check(text(shell.currentContent()).contains("not from Google"), "sample reviews are not represented as Google reviews");
            check(findStyle(shell.currentContent(), "venue-detail-image") != null, "venue detail photo region has a neutral fallback");
            javafx.scene.layout.FlowPane detailColumns = (javafx.scene.layout.FlowPane) findStyle(shell.currentContent(), "venue-detail-layout");
            javafx.scene.layout.VBox leftDetail = (javafx.scene.layout.VBox) detailColumns.getChildren().get(0);
            javafx.scene.layout.VBox rightDetail = (javafx.scene.layout.VBox) detailColumns.getChildren().get(1);
            check(Math.abs(leftDetail.getWidth() - rightDetail.getWidth()) < 50, "venue detail columns are balanced on a wide screen");
            button(shell.currentContent(), "back-navigation").fire();
            refresh(root);
            check(shell.currentPage().equals("Explore Venues"), "venue detail back arrow returns to its actual origin");
            check(field(shell.currentContent(), "venue-search").getText().equals("Muelle"), "venue search survives detail back navigation");
            button(shell.currentContent(), "venue-details-5").fire();
            refresh(root);
            button(shell.currentContent(), "venue-detail-reserve").fire();
            refresh(root);
            check(shell.currentPage().equals(MainShell.REQUEST_VENUE), "venue selection opens the reservation form");
            check(text(shell.currentContent()).contains("Muelle Deli and Restaurant"), "reservation form keeps the selected venue visible");
            button(shell.currentContent(), "back-navigation").fire();
            check(shell.currentPage().equals("Venue Details"), "venue request back arrow returns to the selected venue");
            button(shell.currentContent(), "back-navigation").fire();
            check(shell.currentPage().equals("Explore Venues"), "venue detail back arrow returns to the venue catalog");
            button(shell.currentContent(), "venue-details-5").fire();
            refresh(root);
            button(shell.currentContent(), "venue-detail-reserve").fire();
            refresh(root);
            snapshot(stage, "venue-request");

            Ui.useDialogs(new Ui.Dialogs() {
                @Override public boolean confirm(String header, String body) { return true; }
                @Override public void info(String header, String body) { }
                @Override public void error(String header, String body) { throw new AssertionError(header + ": " + body); }
            });
            combo(shell.currentContent(), "venue-purpose", EventPurpose.TICKETED_EVENT);
            ((DatePicker) findById(shell.currentContent(), "venue-date")).setValue(LocalDate.now().plusDays(20));
            field(shell.currentContent(), "venue-start").setText("08:00");
            field(shell.currentContent(), "venue-end").setText("12:00");
            combo(shell.currentContent(), "venue-pricing", ReservationPricing.HALF_DAY_MORNING);
            button(shell.currentContent(), "venue-pay").fire();
            refresh(root);
            snapshot(stage, "venue-confirmation");
            check(text(shell.currentContent()).contains("PENDING") && text(shell.currentContent()).contains("PAID"),
                    "successful booking confirmation displays PENDING / PAID");
            check(text(shell.currentContent()).contains("awaiting administrator approval"), "confirmation says approval is still required");
            VenueReservation paid = services.reservationService().forCustomer(customer).get(0);
            check(paid.status() == BookingStatus.PENDING && paid.paymentStatus() == PaymentStatus.PAID,
                    "GUI request preserves pay-first pending status");
            check(account.getWalletBalance().compareTo(new BigDecimal("14000.00")) == 0,
                    "wallet is charged once after confirmation");
            button(shell.currentContent(), "confirmation-view-reservations").fire();
            refresh(root);

            shell.showPage(MainShell.WALLET);
            refresh(root);
            button(shell.currentContent(), "wallet-filter-pending").fire();
            refresh(root);
            String pendingWalletText = text(findById(shell.currentContent(), "transaction-history-rows"));
            check(pendingWalletText.contains("Pending admin approval") && pendingWalletText.contains("PAID"),
                    "wallet groups the paid venue reservation under Pending while showing its actual statuses");

            shell.showPage(MainShell.VENUES);
            refresh(root);
            snapshot(stage, "venue-reservations");
            check(findStyle(shell.currentContent(), "reservation-card") != null, "reservation management uses a card list");
            javafx.scene.layout.FlowPane reservationGrid = (javafx.scene.layout.FlowPane) findStyle(shell.currentContent(), "reservation-list");
            javafx.scene.layout.VBox reservationCard = (javafx.scene.layout.VBox) findStyle(shell.currentContent(), "reservation-card");
            check(reservationGrid != null && reservationCard.getPrefWidth() > reservationGrid.getWidth() * .4
                            && reservationCard.getPrefWidth() < reservationGrid.getWidth() * .7,
                    "venue reservations use the same half-width cards as the two-column catalogs");
            ((ToggleButton) findById(shell.currentContent(), "reservation-filter-pending")).fire();
            refresh(root);
            check(findStyle(shell.currentContent(), "reservation-card") != null, "pending filter matches the customer's actual booking");
            button(shell.currentContent(), "cancel-reservation").fire();
            refresh(root);
            VenueReservation cancelled = services.reservationService().forCustomer(customer).get(0);
            check(cancelled.status() == BookingStatus.CANCELLED, "cancellation delegates through the existing service");
            check(account.getWalletBalance().compareTo(new BigDecimal("20000.00")) == 0,
                    "eligible cancellation refund is restored through the service");

            shell.showPage(MainShell.MY_EVENTS);
            refresh(root);
            check(findById(shell.currentContent(), "my-events-filter") instanceof ComboBox<?>, "event status filter exists");
            check(((javafx.scene.layout.FlowPane) findById(shell.currentContent(), "organizer-summary-grid"))
                    .getChildren().size() == 3, "organizer overview shows three summary cards");
            check(text(shell.currentContent()).contains("Ready to Organize"), "ready-to-organize section is shown");
            snapshot(stage, "my-events");

            shell.showPage(MainShell.TICKETS);
            refresh(root);
            check(findStyle(shell.currentContent(), "empty-state-bubble") != null, "empty ticket ledger prompts exploration");
            check(text(shell.currentContent()).contains("Explore Events"), "empty ticket ledger has its route action");
            check(countStyle(findStyle(shell.currentContent(), "ticket-filters"), "ticket-filter") == 4,
                    "digital ticket wallet exposes the four ticket lifecycle filters (found "
                            + countStyle(findStyle(shell.currentContent(), "ticket-filters"), "ticket-filter") + ")");
            check(findById(shell.currentContent(), "tickets-table") == null,
                    "digital ticket wallet replaces the table with a card layout");
            check(text(shell.currentContent()).contains("No tickets yet."),
                    "empty ticket wallet uses the requested empty-state heading");
            snapshot(stage, "my-tickets-empty");

            shell.showPage(MainShell.WALLET);
            refresh(root);
            snapshot(stage, "wallet-dashboard");
            check(findById(shell.currentContent(), "wallet-balance-card") != null,
                    "wallet dashboard shows the current balance card");
            check(findById(shell.currentContent(), "summary-total-top-ups") != null
                            && findById(shell.currentContent(), "summary-total-spending") != null
                            && findById(shell.currentContent(), "summary-refunds-received") != null,
                    "wallet dashboard shows read-only financial summaries");
            check(text(findById(shell.currentContent(), "summary-total-top-ups")).contains("₱20,000.00"),
                    "initial funding is included once in wallet top-up totals");
            check(text(findById(shell.currentContent(), "summary-total-spending")).contains("₱6,000.00"),
                    "wallet spending summary uses the recorded venue payment");
            check(text(findById(shell.currentContent(), "summary-refunds-received")).contains("₱6,000.00"),
                    "wallet refund summary uses the completed wallet refund");
            check(findById(shell.currentContent(), "wallet-filter-top-ups") != null
                            && findById(shell.currentContent(), "wallet-filter-event-transactions") != null
                            && findById(shell.currentContent(), "wallet-filter-event-records") != null
                            && findById(shell.currentContent(), "wallet-filter-pending") != null
                            && findById(shell.currentContent(), "wallet-filter-venue-reservations") == null,
                    "wallet has four compact transaction filters");
            check(button(shell.currentContent(), "wallet-filter-pending") != null,
                    "pending filter replaces the separate venue reservations tab");
            check(button(shell.currentContent(), "quick-actions-toggle").isVisible(),
                    "radial quick action remains available on the wallet page");
            button(shell.currentContent(), "wallet-filter-pending").fire();
            refresh(root);
            check(text(findById(shell.currentContent(), "transaction-history-rows")).contains("Cancelled"),
                    "pending filter includes venue reservations with their actual current status");
            button(shell.currentContent(), "topup-button").fire();
            Parent exitDialog = dialogRoot("topup-amount");
            button(exitDialog, "back-navigation").fire();
            check(!dialogShowing("topup-amount"), "Add Funds back arrow dismisses the form");
            button(shell.currentContent(), "topup-button").fire();
            Parent topUpDialog = dialogRoot("topup-amount");
            field(topUpDialog, "topup-amount").setText("0");
            button(topUpDialog, "topup-confirm").fire();
            check(account.getWalletBalance().compareTo(new BigDecimal("20000.00")) == 0,
                    "zero top-up is rejected without changing the wallet");
            field(topUpDialog, "topup-amount").setText("100000.01");
            button(topUpDialog, "topup-confirm").fire();
            check(account.getWalletBalance().compareTo(new BigDecimal("20000.00")) == 0,
                    "top-up maximum remains enforced by the wallet service");
            field(topUpDialog, "topup-amount").setText("1000");
            button(topUpDialog, "topup-confirm").fire();
            check(account.getWalletBalance().compareTo(new BigDecimal("21000.00")) == 0,
                    "confirmed top-up updates the wallet through the existing service");
            check(text(findById(shell.currentContent(), "wallet-balance")).contains("₱21,000.00"),
                    "wallet dashboard refreshes its balance after a top-up");

            seedOrganizerOverview(services, customer, account);
            shell.showPage(MainShell.MY_EVENTS);
            refresh(root);
            check(((javafx.scene.layout.FlowPane) findById(shell.currentContent(), "organizer-event-grid"))
                    .getChildren().size() == 2, "My Events shows two event cards in its responsive grid");
            check(((javafx.scene.layout.FlowPane) findById(shell.currentContent(), "organizer-summary-grid"))
                    .getChildren().size() == 3, "My Events summary cards remain visible with event data");
            check(text(shell.currentContent()).contains("Ready to Organize"), "approved unlinked reservation is shown");
            check(button(shell.currentContent(), "quick-actions-toggle").isVisible(), "radial FAB remains on My Events");
            snapshot(stage, "my-events-populated");
            Event firstEvent = services.eventService().eventsFor(customer).get(0);
            button(shell.currentContent(), "event-details-" + firstEvent.id()).fire();
            refresh(root);
            check(findById(shell.currentContent(), "event-ticket-categories") != null,
                    "event details show configured ticket categories");
            check(findStyle(shell.currentContent(), "venue-detail-layout") != null,
                    "event details use the shared editorial two-column layout");
            snapshot(stage, "my-event-details");
            button(shell.currentContent(), "back-navigation").fire();
            refresh(root);
            check(findById(shell.currentContent(), "organizer-event-grid") != null,
                    "event details back arrow returns to My Events");

            shell.showPage(MainShell.PROFILE);
            refresh(root);
            check(findStyle(shell.currentContent(), "profile-card") != null && findStyle(shell.currentContent(), "profile-avatar") != null,
                    "profile uses a standard account card");

            var buyerRegistration = services.customerAccess().register("Ticket", "Buyer", LocalDate.now().minusYears(25),
                    ContactMethod.EMAIL, "ticket-buyer@example.test", "unused", new BigDecimal("2000.00"));
            Event ticketEvent = services.eventService().eventsFor(customer).stream()
                    .filter(event -> event.name().equals("Garden Concert")).findFirst().orElseThrow();
            var ticketType = services.publishing().ticketTypes(ticketEvent.id()).get(0);
            services.publishing().buyTicket(buyerRegistration.customer(), buyerRegistration.account(),
                    ticketEvent.id(), ticketType.id(), 1);
            services.publishing().buyTicket(buyerRegistration.customer(), buyerRegistration.account(),
                    ticketEvent.id(), ticketType.id(), 2);
            session.start(buyerRegistration.account(), buyerRegistration.customer());
            shell.showPage(MainShell.TICKETS);
            refresh(root);
            javafx.scene.layout.FlowPane ticketGrid = (javafx.scene.layout.FlowPane) findById(shell.currentContent(), "digital-ticket-grid");
            var buyerPurchases = services.publishing().purchases(buyerRegistration.customer());
            check(ticketGrid != null && ticketGrid.getChildren().size() == 2,
                    "actual ticket purchases display as separate grouped digital ticket cards");
            check(text(shell.currentContent()).contains("Garden Concert") && text(shell.currentContent()).contains(ticketType.name()),
                    "digital ticket shows its actual event and category");
            snapshot(stage, "my-tickets-populated");
            ((ToggleButton) findById(shell.currentContent(), "my-tickets-filter-upcoming")).fire();
            refresh(root);
            check(((javafx.scene.layout.FlowPane) findById(shell.currentContent(), "digital-ticket-grid")).getChildren().size() == 2,
                    "Upcoming includes purchases whose event end time has not passed");
            ((ToggleButton) findById(shell.currentContent(), "my-tickets-filter-past")).fire();
            refresh(root);
            check(text(shell.currentContent()).contains("No tickets match this filter."),
                    "Past excludes tickets for future events and shows the filtered empty state");
            button(shell.currentContent(), "all-tickets-reset").fire();
            refresh(root);
            ticketGrid = (javafx.scene.layout.FlowPane) findById(shell.currentContent(), "digital-ticket-grid");
            check(ticketGrid.getChildren().size() == 2, "return to All Tickets restores every purchase card");
            button(shell.currentContent(), "view-ticket-" + buyerPurchases.get(0).id()).fire();
            refresh(root);
            check(text(shell.currentContent()).contains("Ticket Details")
                            && text(shell.currentContent()).contains(ticketType.name()),
                    "View Ticket opens details from the stored purchase");
            snapshot(stage, "my-ticket-details");
            button(shell.currentContent(), "back-navigation").fire();
            refresh(root);
            check(findById(shell.currentContent(), "digital-ticket-grid") != null,
                    "ticket details back arrow returns to the wallet with its list state");
            session.start(account, customer);
            stage.close();
        } catch (Exception ex) { throw new RuntimeException(ex); }
    }

    private static void seedOrganizerOverview(AppServices services, Customer customer, AccountStore.Account account)
            throws Exception {
        services.accounts().topUp(account, new BigDecimal("15000.00"));
        List<Venue> venues = List.of(
                VenueCatalog.all().stream().filter(venue -> venue.name().equals("Richmonde Hotel Iloilo")).findFirst().orElseThrow(),
                VenueCatalog.all().stream().filter(venue -> venue.name().equals("Park Inn by Radisson Iloilo")).findFirst().orElseThrow(),
                VenueCatalog.all().stream().filter(venue -> venue.name().startsWith("Muelle Deli")).findFirst().orElseThrow());
        List<String> eventNames = List.of("Garden Concert", "City Music Night");
        for (int index = 0; index < venues.size(); index++) {
            Venue venue = venues.get(index);
            LocalDate date = LocalDate.now().plusDays(60 + index);
            VenueReservation reservation = services.venueWorkflow().requestAndPay(customer, account, venue, date,
                    java.time.LocalTime.of(8, 0), java.time.LocalTime.NOON, ReservationPricing.HALF_DAY_MORNING);
            services.reservations().approve(reservation.id());
            if (index < eventNames.size()) {
                EventDraft draft = new EventDraft(eventNames.get(index), "A customer organized ticketed gathering.",
                        index == 0 ? EventType.CONCERT : EventType.FESTIVAL, EventPurpose.TICKETED_EVENT,
                        EventVisibility.PUBLIC, date, java.time.LocalTime.of(8, 0), java.time.LocalTime.NOON,
                        60, venue, List.of(new TicketCategory("General", new BigDecimal("500.00"), 40)));
                services.eventCreationService().create(customer, draft, reservation.id(), "");
            }
        }
    }

    private static Button button(Node root, String id) {
        Node node = findById(root, id);
        if (!(node instanceof Button button)) throw new AssertionError("Button #" + id + " is missing.");
        return button;
    }
    private static TextField field(Node root, String id) {
        Node node = findById(root, id);
        if (!(node instanceof TextField field)) throw new AssertionError("Text field #" + id + " is missing (found " + node + ", page content=" + root + ").");
        return field;
    }
    private static Parent dialogRoot(String id) {
        return Window.getWindows().stream().filter(Window::isShowing).map(Window::getScene)
                .filter(java.util.Objects::nonNull).map(javafx.scene.Scene::getRoot)
                .filter(Parent.class::isInstance).map(Parent.class::cast)
                .filter(root -> findById(root, id) != null).findFirst()
                .orElseThrow(() -> new AssertionError("Dialog with field #" + id + " is not showing."));
    }
    private static boolean dialogShowing(String id) {
        return Window.getWindows().stream().filter(Window::isShowing).map(Window::getScene)
                .filter(java.util.Objects::nonNull).map(javafx.scene.Scene::getRoot)
                .filter(Parent.class::isInstance).map(Parent.class::cast)
                .anyMatch(root -> findById(root, id) != null);
    }
    @SuppressWarnings("unchecked")
    private static <T> void combo(Node root, String id, T value) {
        Node node = findById(root, id);
        if (!(node instanceof ComboBox<?> box)) throw new AssertionError("Combo box #" + id + " is missing.");
        ((ComboBox<T>) box).setValue(value);
    }
    private static String text(Node root) {
        StringBuilder value = new StringBuilder();
        collect(root, node -> {
            if (node instanceof javafx.scene.control.Label label) value.append(label.getText()).append(' ');
            if (node instanceof javafx.scene.control.ButtonBase button) value.append(button.getText()).append(' ');
            return false;
        });
        return value.toString();
    }
    private static void refresh(Parent root) { root.applyCss(); root.layout(); }
    private static void snapshot(Stage stage, String name) throws Exception {
        if (!Boolean.getBoolean("drawn.smoke.screenshots")) return;
        WritableImage image = stage.getScene().snapshot(null);
        PixelReader pixels = image.getPixelReader();
        BufferedImage output = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < output.getHeight(); y++)
            for (int x = 0; x < output.getWidth(); x++) output.setRGB(x, y, pixels.getArgb(x, y));
        File file = new File("target/" + name + ".png");
        ImageIO.write(output, "png", file);
    }
    private static Node findById(Node root, String id) {
        return collect(root, node -> id.equals(node.getId()));
    }
    private static Node findStyle(Node root, String style) {
        return collect(root, node -> node.getStyleClass().contains(style));
    }
    private static int countStyle(Node root, String style) {
        int[] count = {0};
        collect(root, node -> { if (node.getStyleClass().contains(style)) count[0]++; return false; });
        return count[0];
    }
    private static Node collect(Node node, java.util.function.Predicate<Node> predicate) {
        if (predicate.test(node)) return node;
        if (node instanceof javafx.scene.control.ScrollPane scroll && scroll.getContent() != null) {
            Node found = collect(scroll.getContent(), predicate);
            if (found != null) return found;
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Node found = collect(child, predicate);
                if (found != null) return found;
            }
        }
        return null;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
