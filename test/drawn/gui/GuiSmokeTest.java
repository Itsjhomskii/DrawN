package drawn.gui;

import drawn.gui.views.Ui;
import drawn.model.event.EventPurpose;
import drawn.model.event.ReservationPricing;
import drawn.model.event.VenueReservation;
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
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputControl;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.stage.Window;

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

    public static void main(String[] args) throws Exception {
        Path data = Files.createTempDirectory("drawn-gui-test");
        System.setProperty("drawn.data", data.toString());
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
        register("Ana", "Reyes", "ana@example.com", "100");
        expect(label(".form-error").getText().contains("500"), "minimum initial funding message shown");
        type("reg-funding", "20000");
        shot("02-register");
        fire("reg-create");
        expectNotice("INFO WALLET SETUP COMPLETE!");
        expect(node("nav-Home") != null, "customer shell opened after registration");
        expect(root().lookupAll(".stat-tile").size() == 7, "home shows the 7 dashboard figures");
        shot("03-home");

        // ---- Wallet: rejected top-up, accepted top-up, history ----
        fire("nav-Wallet-Transactions");
        type("topup-amount", "abc");
        fire("topup-button");
        expectNotice("ERROR Could not save wallet top-up");
        type("topup-amount", "1000");
        fire("topup-button");
        expectNotice("INFO Top-up successful.");
        expect(label("#wallet-balance").getText().equals("PHP 21000.00"), "balance after top-up");
        expect(table("funding-table").getItems().size() == 2, "initial funding + top-up listed");
        shot("04-wallet");

        // ---- Venue request: invalid hourly time, then a paid morning booking ----
        fire("nav-Venue-Reservations");
        fire("open-request-venue");
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
        expectNotice("INFO Reservation payment successful!");
        TableView<?> reservations = table("reservations-table");
        expect(reservations.getItems().size() == 1, "new reservation listed");
        VenueReservation booking = (VenueReservation) reservations.getItems().get(0);
        expect(booking.status().name().equals("PENDING") && booking.paymentStatus().name().equals("PAID"), "booking is paid and pending");
        reservations.getSelectionModel().select(0);
        shot("06-reservations");

        // Create Event with nothing approved yet explains what to do.
        fire("nav-My-Events");
        fire("open-create-event");
        expect(node("create-request-venue") != null, "create event says a venue must be approved first");

        // The console app has no admin approval screen, so approve through the existing service.
        services.reservations().approve(booking.id());

        // ---- Create & publish a ticketed event ----
        fire("nav-My-Events");
        fire("open-create-event");
        select("create-purpose", EventPurpose.TICKETED_EVENT);
        type("create-name", "Iloilo Jazz Night");
        type("create-description", "An evening of live jazz by the river.");
        type("create-attendance", "80");
        type("category-name", "VIP");
        type("category-price", "500");
        type("category-quantity", "20");
        fire("category-add");
        type("category-name", "vip");
        type("category-price", "300");
        type("category-quantity", "10");
        fire("category-add");
        expectNotice("ERROR Could not add ticket category | Category names must be unique.");
        shot("07-create-event");
        fire("create-publish");
        expectNotice("INFO Event published.");
        TableView<?> myEvents = table("my-events-table");
        expect(myEvents.getItems().size() == 1, "published event listed under My Events");
        myEvents.getSelectionModel().select(0);
        shot("08-my-events");

        // ---- A second customer buys tickets, then cancels them ----
        logOut();
        fire("login-register");
        register("Ben", "Cruz", "ben@example.com", "5000");
        expectNotice("INFO WALLET SETUP COMPLETE!");
        fire("nav-Explore-Events");
        TableView<?> explore = table("explore-table");
        expect(explore.getItems().size() == 1, "public event visible to another customer");
        explore.getSelectionModel().select(0);
        type("ticket-quantity", "2");
        shot("09-explore");
        fire("buy-tickets");
        expectNotice("INFO Ticket purchase completed.");
        expect(services.accounts().findAccount(services.customerAccess()
                .authenticate("ben@example.com", "secret123").account().getId()).getWalletBalance()
                .compareTo(new BigDecimal("4000.00")) == 0, "2 VIP tickets charged PHP 1000 to the wallet file");
        fire("nav-My-Tickets");
        TableView<?> tickets = table("tickets-table");
        expect(tickets.getItems().size() == 1, "purchase listed");
        tickets.getSelectionModel().select(0);
        shot("10-tickets");
        fire("tickets-cancel");
        expectNotice("INFO Ticket purchase cancelled.");
        fire("nav-Explore-Events");
        ((javafx.scene.control.RadioButton) node("explore-private")).fire();
        type("explore-keyword", "wrong-keyword");
        fire("explore-find");
        expectNotice("INFO No events found");
        fire("nav-Wallet-Transactions");
        expect(table("event-transactions-table").getItems().size() >= 2, "ticket payment and refund in history");
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
        table("my-events-table").getSelectionModel().select(0);
        fire("cancel-event");
        expectNotice("INFO Event cancelled.");
        expect(table("my-events-table").getItems().isEmpty(), "cancelled event no longer active");

        // ---- Venue request cancellation (social gathering) ----
        fire("nav-Venue-Reservations");
        fire("open-request-venue");
        select("venue-purpose", EventPurpose.SOCIAL_GATHERING);
        select("venue-choice", CustomerWorkflowService.venueNamed("The Veranda at MO2 Westown Hotel").orElseThrow());
        ((DatePicker) node("venue-date")).setValue(LocalDate.now().plusDays(40));
        type("venue-start", "13:00");
        type("venue-end", "17:00");
        select("venue-pricing", ReservationPricing.HALF_DAY_AFTERNOON);
        fire("venue-pay");
        expectNotice("INFO Reservation payment successful!");
        table("reservations-table").getSelectionModel().select(0);
        fire("cancel-reservation");
        expectNotice("INFO Reservation cancelled.");
        logOut();

        // ---- Admin: the admin login opens the admin dashboard; logging out returns to login ----
        // shortcut: "Add event" is not clicked because the admin view pops real blocking alerts; click it by hand.
        type("login-contact", "admin@drawn.com");
        type("login-password", "admin");
        fire("login-button");
        expect(node("admin-dashboard") != null, "admin dashboard opened");
        expect(node("admin-approved-events-table") != null, "approved public events loaded for admin");
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

    /** Opens the sidebar profile menu, picks "My Profile", then presses the page's Log Out button. */
    private static void logOut() throws Exception {
        ((ButtonBase) root().lookup(".profile-section")).fire();
        ContextMenu menu = Window.getWindows().stream().filter(ContextMenu.class::isInstance).map(ContextMenu.class::cast)
                .findFirst().orElseThrow(() -> new AssertionError("profile menu did not open"));
        menu.getItems().get(0).fire();
        menu.hide();
        shot("13-profile");
        fire("profile-logout");
        expect(node("login-button") != null, "logged out to the login screen");
    }

    /** The current screen, laid out so controls inside scroll panes and tables can be looked up. */
    private static Parent root() {
        Parent root = stage.getScene().getRoot();
        root.applyCss();
        root.layout();
        return root;
    }
    private static Node node(String id) { return root().lookup("#" + id); }
    private static Label label(String selector) { return (Label) root().lookup(selector); }

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
