package drawn.gui;

import drawn.gui.views.Ui;
import drawn.model.event.EventPurpose;
import drawn.model.event.Event;
import drawn.model.event.EventType;
import drawn.model.event.EventVisibility;
import drawn.model.event.ReservationPricing;
import drawn.model.event.TicketCategory;
import drawn.model.event.VenueReservation;
import drawn.model.event.VenueCatalog;
import drawn.service.customer.CustomerWorkflowService;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputControl;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Drives every GUI page against a throwaway data folder: fills fields, presses buttons and checks that
 * the existing services saved the result. Optional argument: a folder to save page screenshots into.
 */
public final class GuiSmokeTest {
    private static final List<String> notices = new ArrayList<>();
    private static Stage stage;
    private static Path screenshots;
    private static Throwable dialogAutomationFailure;

    public static void main(String[] args) throws Exception {
        Path data = Files.createTempDirectory("drawn-gui-test");
        System.setProperty("drawn.data", data.toString());
        System.setProperty("drawn.gui.smoke", "true");
        screenshots = args.length > 0 ? Path.of(args[0]) : null;
        if (screenshots != null) Files.createDirectories(screenshots);
        Ui.useDialogs(new Ui.Dialogs() {
            @Override public boolean confirm(String header, String body) { notices.add("CONFIRM " + header); return true; }
            @Override public void info(String header, String body) { notices.add("INFO " + header + " | " + body); }
            @Override public void error(String header, String body) { notices.add("ERROR " + header + " | " + body); }
        });

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.startup(() -> {
            try { run(); } catch (Throwable t) { failure.set(t); } finally { done.countDown(); }
        });
        if (!done.await(120, TimeUnit.SECONDS)) throw new AssertionError("GUI smoke test timed out");
        Platform.runLater(() -> stage.close());
        Platform.exit();
        if (failure.get() != null) {
            System.err.println("Dialogs since last check:\n  " + String.join("\n  ", notices));
            if (failure.get() instanceof Exception e) throw e;
            throw new AssertionError(failure.get());
        }
        System.out.println("GuiSmokeTest passed.");
    }

    private static void run() throws Exception {
        DrawNApplication app = new DrawNApplication();
        stage = new Stage();
        app.start(stage);
        stage.setMaximized(false);
        stage.setWidth(1366);
        stage.setHeight(860);
        AppServices services = servicesOf(app);
        shot("01-login");

        // ---- Registration: validation message, then success ----
        fire("login-register");
        awaitNode("reg-create");
        register("Ana", "Reyes", "ana@example.com", "100");
        expect(label(".form-error").getText().contains("500"), "minimum initial funding message shown");
        type("reg-funding", "20000");
        shot("02-register");
        fire("reg-create");
        awaitNode("nav-Home");
        expect(node("nav-Home") != null, "customer shell opened after registration");
        expect(node("home-wallet-balance") != null, "home shows the wallet shortcut");
        shot("03-home");

        // ---- Wallet: rejected top-up, accepted top-up, history ----
        fire("nav-Wallet-&-Transactions");
        fire("topup-button");
        type("topup-amount", "abc");
        fire("topup-confirm");
        expect(!label(".form-error").getText().isBlank(), "invalid top-up stays in the dialog: " + label(".form-error").getText());
        type("topup-amount", "1000");
        fire("topup-confirm");
        expectNotice("INFO Top-up successful.");
        awaitNode("wallet-balance");
        expect(label("#wallet-balance").getText().equals("₱21,000.00"), "balance after top-up");
        expect(services.walletTransactions(services.customerAccess().authenticate("ana@example.com", "secret123").account())
                .size() == 2, "initial funding + top-up persisted");
        shot("04-wallet");

        // ---- Venue request: invalid hourly time, then a paid morning booking ----
        fire("nav-Venue-Reservations");
        fire("open-request-venue");
        int commercialVenueIndex = VenueCatalog.all().indexOf(VenueCatalog.all().stream()
                .filter(v -> v.supports(EventPurpose.TICKETED_EVENT)).findFirst().orElseThrow());
        fire("venue-select-" + commercialVenueIndex);
        select("venue-purpose", EventPurpose.TICKETED_EVENT);
        ((DatePicker) node("venue-date")).setValue(LocalDate.now().plusDays(30));
        type("venue-start", "05:00");
        type("venue-end", "07:00");
        select("venue-pricing", ReservationPricing.HOURLY);
        fire("venue-pay");
        expectNotice("ERROR Reservation request failed | Hourly reservations must fall between 06:00 and 23:00.");
        type("venue-start", "08:00");
        type("venue-end", "12:00");
        select("venue-pricing", ReservationPricing.HALF_DAY_MORNING);
        shot("05-request-venue");
        fire("venue-pay");
        expectNotice("CONFIRM Confirm full reservation payment?");
        VenueReservation booking = services.reservations().forCustomer(services.customerAccess()
                .authenticate("ana@example.com", "secret123").customer()).get(0);
        expect(booking.status().name().equals("PENDING") && booking.paymentStatus().name().equals("PAID"), "booking is paid and pending");
        shot("06-reservations");

        // The quick-create page explains that venue approval is required.
        fire("quick-actions-toggle");
        fire("quick-create-event");
        awaitNode("create-request-venue");
        expect(node("create-request-venue") != null, "create event asks for an approved venue reservation");

        // The admin approves the already paid request through the real admin review screen.
        logOut();
        type("login-contact", "admin@drawn.com");
        type("login-password", "admin");
        fire("login-button");
        awaitNode("admin-dashboard");
        selectAdminTab("admin-tab-venue-requests");
        expect(table("admin-venue-requests").getItems().size() == 1, "paid venue request reaches admin review");
        table("admin-venue-requests").getSelectionModel().select(0);
        fire("admin-approve-venue");
        expect(((Label) node("admin-status")).getText().startsWith("Approved "), "admin approved the paid venue booking");
        expect(services.reservations().find(booking.id()).status().name().equals("APPROVED"), "venue approval persisted");
        fire("admin-logout");
        awaitNode("login-button");
        type("login-contact", "ana@example.com");
        type("login-password", "secret123");
        fire("login-button");
        awaitNode("nav-Home");

        // ---- Create & publish a ticketed event ----
        fire("nav-My-Events");
        fire("create-event-from-reservation-" + booking.id().replaceAll("[^A-Za-z0-9_-]", "-"));
        awaitNode("create-publish");
        select("create-purpose", EventPurpose.TICKETED_EVENT);
        select("create-type", EventType.CONCERT);
        type("create-name", "Iloilo Jazz Night");
        type("create-description", "An evening of live jazz by the river.");
        type("create-attendance", "80");
        type("category-name", "VIP");
        type("category-price", "500");
        type("category-quantity", "20");
        type("category-sales-start", java.time.LocalDateTime.now().minusMinutes(1).withSecond(0).withNano(0).toString().replace('T', ' '));
        type("category-sales-deadline", booking.date().atTime(booking.startTime()).minusHours(1).toString().replace('T', ' '));
        fire("category-add");
        shot("07-create-event");
        fire("create-publish");
        expectNotice("INFO Event published.");
        Event published = services.events().eventsFor(services.customerAccess()
                .authenticate("ana@example.com", "secret123").customer()).get(0);
        awaitNode("event-details-" + published.id().replaceAll("[^A-Za-z0-9_-]", "-"));
        expect(node("event-details-" + published.id().replaceAll("[^A-Za-z0-9_-]", "-")) != null, "published event card is listed under My Events");
        expect(node("organizer-summary-grid") != null, "organizer overview is present");
        shot("08-my-events");

        // ---- A second customer buys tickets, then cancels them ----
        logOut();
        fire("login-register");
        awaitNode("reg-create");
        register("Ben", "Cruz", "ben@example.com", "5000");
        awaitNode("nav-Explore-Events");
        fire("nav-Explore-Events");
        type("explore-event-search", published.name());
        expect(root().lookup("#public-event-grid").lookupAll(".event-card").size() == 1,
                "published event is discoverable by another customer");
        var buyer = services.customerAccess().authenticate("ben@example.com", "secret123");
        scheduleDialogButton("Buy tickets", false, 40);
        scheduleDialogButton("Review purchase", true, 40);
        scheduleDialogButton("OK", false, 40);
        scheduleDialogButton("OK", false, 40);
        fireText(node("public-event-grid"), "View Details");
        expect(dialogAutomationFailure == null, "ticket checkout dialogs were available: " + dialogAutomationFailure);
        expect(services.accounts().findAccount(buyer.account().getId()).getWalletBalance()
                .compareTo(new BigDecimal("4000.00")) == 0, "2 tickets charged PHP 1000 to the wallet file");
        shot("09-explore");
        fire("nav-My-Tickets");
        awaitNode("digital-ticket-grid");
        expect(((javafx.scene.layout.Pane) node("digital-ticket-grid")).getChildren().size() == 1, "purchase listed in My Tickets");
        var purchase = services.publishing().purchases(buyer.customer()).get(0);
        fire("view-ticket-" + purchase.id());
        shot("10-tickets");
        BigDecimal balanceBeforeCancel = services.accounts().findAccount(buyer.account().getId()).getWalletBalance();
        var cancellationQuote = services.publishing().cancellationQuote(buyer.customer(), purchase.id());
        fire("cancel-ticket");
        expectNotice("CONFIRM Confirm ticket cancellation?");
        expect(services.publishing().purchases(buyer.customer()).get(0).status().name().equals("CANCELLED"),
                "ticket cancellation persisted");
        expect(services.accounts().findAccount(buyer.account().getId()).getWalletBalance()
                .compareTo(balanceBeforeCancel.add(cancellationQuote.refundAmount())) == 0,
                "ticket cancellation credits the quoted refund to the wallet");
        fire("nav-Explore-Events");
        type("explore-keyword", "wrong-keyword");
        fire("explore-find");
        expect(root().lookup("#private-event-results").lookupAll(".label").stream()
                .filter(Label.class::isInstance).map(Label.class::cast)
                .anyMatch(item -> item.getText().contains("No private events matched")), "private event empty result is displayed");
        fire("nav-Wallet-&-Transactions");
        fire("wallet-filter-event-transactions");
        expect(((javafx.scene.layout.Pane) node("transaction-history-rows")).getChildren().size() >= 2,
                "ticket payment and refund in event transaction history");
        shot("11-wallet-history");

        // ---- Organizer logs back in and cancels the event ----
        logOut();
        type("login-contact", "ana@example.com");
        type("login-password", "wrong");
        fire("login-button");
        expect(!label(".form-error").getText().isBlank(), "wrong password rejected");
        type("login-password", "secret123");
        fire("login-button");
        fire("nav-My-Events");
        Event ownedEvent = services.events().eventsFor(services.customerAccess()
                .authenticate("ana@example.com", "secret123").customer()).get(0);
        fire("event-details-" + ownedEvent.id().replaceAll("[^A-Za-z0-9_-]", "-"));
        fire("cancel-event");
        expectNotice("INFO Event cancelled |");
        expect(node("event-details-" + ownedEvent.id().replaceAll("[^A-Za-z0-9_-]", "-")) != null, "cancelled event remains visible with its status");
        expect(node("event-status-" + ownedEvent.id().replaceAll("[^A-Za-z0-9_-]", "-")) instanceof Label,
                "cancelled event shows its current status");
        expect(((Label) node("event-status-" + ownedEvent.id().replaceAll("[^A-Za-z0-9_-]", "-"))).getText().equals("Cancelled"),
                "cancelled event status is accurate");

        // ---- Venue request cancellation (social gathering) ----
        fire("nav-Venue-Reservations");
        fire("open-request-venue");
        int socialVenueIndex = VenueCatalog.all().indexOf(CustomerWorkflowService.venueNamed("The Veranda at MO2 Westown Hotel").orElseThrow());
        fire("venue-select-" + socialVenueIndex);
        select("venue-purpose", EventPurpose.SOCIAL_GATHERING);
        ((DatePicker) node("venue-date")).setValue(LocalDate.now().plusDays(40));
        type("venue-start", "13:00");
        type("venue-end", "17:00");
        select("venue-pricing", ReservationPricing.HALF_DAY_AFTERNOON);
        fire("venue-pay");
        expectNotice("CONFIRM Confirm full reservation payment?");
        fire("confirmation-view-reservations");
        fire("cancel-reservation");
        expectNotice("CONFIRM Cancel this reservation?");
        var socialBooking = services.reservations().forCustomer(services.customerAccess()
                .authenticate("ana@example.com", "secret123").customer()).stream()
                .filter(r -> r.venueName().equals("The Veranda at MO2 Westown Hotel")).findFirst().orElseThrow();
        expect(socialBooking.status().name().equals("CANCELLED"), "venue cancellation persisted");
        logOut();

        // ---- Admin: the admin login opens the admin dashboard; logging out returns to login ----
        // shortcut: "Add event" is not clicked because the admin view pops real blocking alerts; click it by hand.
        type("login-contact", "admin@drawn.com");
        type("login-password", "admin");
        fire("login-button");
        expect(node("admin-dashboard") != null, "admin dashboard opened");
        expect(node("admin-all-events-table") != null, "persisted events loaded for admin");
        shot("12-admin");
        fire("admin-logout");
        expect(node("login-button") != null, "admin logged out");
    }

    // ---- helpers ----

    private static void register(String first, String last, String email, String funding) {
        type("reg-first", first);
        type("reg-last", last);
        ((DatePicker) node("reg-dob")).setValue(LocalDate.of(1995, 5, 20));
        type("reg-contact", email);
        type("reg-password", "secret123");
        type("reg-funding", funding);
        fire("reg-create");
    }

    private static void selectAdminTab(String id) {
        javafx.scene.control.TabPane tabs = (javafx.scene.control.TabPane) node("admin-tabs");
        tabs.getTabs().stream().filter(tab -> id.equals(tab.getId())).findFirst()
                .ifPresentOrElse(tabs.getSelectionModel()::select,
                        () -> { throw new AssertionError("missing admin tab " + id); });
    }

    /** Opens My Profile from the sidebar menu, then logs out through the same menu. */
    private static void logOut() throws Exception {
        ((ButtonBase) root().lookup(".profile-section")).fire();
        ContextMenu menu = profileMenu();
        menu.getItems().get(0).fire();
        menu.hide();
        expect(root().lookupAll(".profile-section-label").size() >= 1, "profile section labels are static text");
        shot("13-profile");
        ((ButtonBase) root().lookup(".profile-section")).fire();
        profileMenu().getItems().get(1).fire();
        awaitNode("login-button");
        expect(node("login-button") != null, "logged out to the login screen");
    }

    private static ContextMenu profileMenu() {
        return Window.getWindows().stream().filter(ContextMenu.class::isInstance).map(ContextMenu.class::cast)
                .filter(ContextMenu::isShowing).findFirst().orElseThrow(() -> new AssertionError("profile menu did not open"));
    }

    /** The current screen, laid out so controls inside scroll panes and tables can be looked up. */
    private static Parent root() {
        Parent root = stage.getScene().getRoot();
        root.applyCss();
        root.layout();
        return root;
    }
    private static Node node(String id) {
        Node found = root().lookup("#" + id);
        if (found != null) return found;
        return Window.getWindows().stream().filter(window -> window.getScene() != null)
                .map(window -> window.getScene().getRoot().lookup("#" + id)).filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
    }

    private static void awaitNode(String id) {
        root().applyCss();
        root().layout();
        expect(node(id) != null, "timed out waiting for #" + id);
    }
    private static Label label(String selector) {
        Node found = root().lookup(selector);
        if (found == null) found = Window.getWindows().stream().filter(window -> window.getScene() != null)
                .map(window -> window.getScene().getRoot().lookup(selector)).filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
        return found instanceof Label result ? result : null;
    }

    private static TableView<?> table(String id) {
        Node table = node(id);
        expect(table instanceof TableView<?>, "table #" + id + " present");
        return (TableView<?>) table;
    }

    private static void fire(String id) {
        Node target = node(id);
        expect(target instanceof ButtonBase, "button #" + id + " present");
        expect(!target.isDisabled(), "button #" + id + " enabled");
        ((ButtonBase) target).fire();
    }

    private static void fireText(Node parent, String text) {
        Node target = parent.lookupAll(".button").stream().filter(ButtonBase.class::isInstance)
                .map(ButtonBase.class::cast).filter(button -> text.equals(button.getText())).findFirst()
                .orElseThrow(() -> new AssertionError("button with text '" + text + "' not found"));
        ((ButtonBase) target).fire();
    }

    private static void scheduleDialogButton(String text, boolean setQuantity, int retries) {
        Platform.runLater(() -> {
            for (Window window : Window.getWindows()) {
                if (!window.isShowing() || window.getScene() == null) continue;
                Node windowRoot = window.getScene().getRoot();
                ButtonBase match = windowRoot.lookupAll(".button").stream().filter(ButtonBase.class::isInstance)
                        .map(ButtonBase.class::cast).filter(button -> text.equals(button.getText())).findFirst().orElse(null);
                if (match == null) continue;
                if (setQuantity) windowRoot.lookupAll(".spinner").stream().filter(Spinner.class::isInstance)
                        .map(Spinner.class::cast).findFirst().ifPresent(spinner -> spinner.getValueFactory().setValue(2));
                match.fire();
                return;
            }
            if (retries > 0) scheduleDialogButton(text, setQuantity, retries - 1);
            else {
                dialogAutomationFailure = new AssertionError("dialog button not found: " + text);
                Window.getWindows().stream().filter(Window::isShowing).forEach(Window::hide);
            }
        });
    }

    private static void type(String id, String text) {
        Node target = node(id);
        expect(target instanceof TextInputControl, "field #" + id + " present");
        ((TextInputControl) target).setText(text);
    }

    @SuppressWarnings("unchecked")
    private static <T> void select(String id, T value) {
        Node target = node(id);
        expect(target instanceof ComboBox<?>, "dropdown #" + id + " present");
        ((ComboBox<T>) target).setValue(value);
    }

    private static void expectNotice(String prefix) {
        expect(notices.stream().anyMatch(notice -> notice.startsWith(prefix)), "dialog \"" + prefix + "\" (got " + notices + ")");
        notices.clear();
    }

    private static void expect(boolean condition, String what) {
        if (!condition) throw new AssertionError("Expected: " + what);
    }

    private static AppServices servicesOf(DrawNApplication app) throws ReflectiveOperationException {
        var field = DrawNApplication.class.getDeclaredField("services");
        field.setAccessible(true);
        return (AppServices) field.get(app);
    }

    private static void shot(String name) throws Exception {
        if (screenshots == null) return;
        Scene scene = stage.getScene();
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        WritableImage image = scene.snapshot(null);
        PixelReader pixels = image.getPixelReader();
        BufferedImage out = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < out.getHeight(); y++) for (int x = 0; x < out.getWidth(); x++) out.setRGB(x, y, pixels.getArgb(x, y));
        ImageIO.write(out, "png", new File(screenshots.toFile(), name + ".png"));
    }
}
