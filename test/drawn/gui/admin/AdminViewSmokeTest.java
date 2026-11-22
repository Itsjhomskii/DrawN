package drawn.gui.admin;

import drawn.gui.AppServices;
import drawn.model.event.ReservationPricing;
import drawn.model.event.Venue;
import drawn.model.event.VenueCatalog;
import drawn.model.event.VenueType;
import drawn.model.user.ContactMethod;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;

import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Drives the real admin screen against a throwaway data folder: approve one paid venue request, try a decline
 * without a reason, decline the other, then open every tab. Optional argument: a folder for tab screenshots.
 */
public final class AdminViewSmokeTest {
    private static Scene scene;
    private static Path screenshots;

    private AdminViewSmokeTest() { }

    public static void main(String[] args) throws Exception {
        screenshots = args.length > 0 ? Path.of(args[0]) : null;
        if (screenshots != null) Files.createDirectories(screenshots);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.startup(() -> {
            try { run(); } catch (Throwable t) { failure.set(t); } finally { done.countDown(); }
        });
        if (!done.await(60, TimeUnit.SECONDS)) throw new AssertionError("Admin GUI test timed out");
        Platform.exit();
        if (failure.get() instanceof Exception e) throw e;
        if (failure.get() != null) throw new AssertionError(failure.get());
        System.out.println("AdminViewSmokeTest passed.");
    }

    private static void run() throws Exception {
        AppServices services = new AppServices(Files.createTempDirectory("drawn-admin-gui-"));
        var owner = services.customerAccess().register("Jamie", "Organizer", LocalDate.now().minusYears(30),
                ContactMethod.EMAIL, "owner@example.test", "pass", new BigDecimal("100000.00"));
        Venue venue = VenueCatalog.all().stream().filter(v -> v.type() == VenueType.BOTH).findFirst().orElseThrow();
        LocalDate date = LocalDate.now().plusDays(45);
        services.venueWorkflow().requestAndPay(owner.customer(), owner.account(), venue, date,
                LocalTime.of(9, 0), LocalTime.of(11, 0), ReservationPricing.HOURLY);
        services.venueWorkflow().requestAndPay(owner.customer(), owner.account(), venue, date,
                LocalTime.of(14, 0), LocalTime.of(16, 0), ReservationPricing.HOURLY);

        AdminView view = new AdminView(new AdminView.Host() {
            @Override public List<drawn.model.event.Event> approvedPublicEvents() throws Exception { return services.events().publicApprovedEvents(); }
            @Override public void setRoot(Parent root) { scene.setRoot(root); }
            @Override public void logout() { }
        }, services.adminDashboard(), services.venues(), "admin@drawn.com");
        scene = new Scene(view.view(), 1280, 820);
        scene.getStylesheets().add(AdminView.class.getResource("/drawn/gui/admin/admin.css").toExternalForm());
        Stage stage = new Stage();
        stage.setScene(scene);
        stage.show();

        openTab("admin-tab-venue-requests");
        expect(table("admin-venue-requests").getItems().size() == 2, "both paid requests listed");
        shot("01-venue-requests");
        table("admin-venue-requests").getSelectionModel().select(0);
        fire("admin-approve-venue");
        expect(status().startsWith("Approved "), "approve message: " + status());
        expect(table("admin-venue-requests").getItems().size() == 1, "approved request left the queue");

        table("admin-venue-requests").getSelectionModel().select(0);
        fire("admin-decline-venue");
        expect(status().contains("decline reason is required"), "blank reason refused: " + status());
        ((TextField) node("admin-decline-reason")).setText("Venue under maintenance");
        fire("admin-decline-venue");
        expect(status().startsWith("Declined ") && status().contains("REFUNDED"), "decline message: " + status());
        expect(table("admin-venue-requests").getItems().isEmpty(), "queue empty after both decisions");

        openTab("admin-tab-transactions");
        expect(table("admin-transactions").getItems().size() >= 4, "payments and refund listed");
        shot("02-transactions");
        openTab("admin-tab-revenue");
        expect(((Label) node("admin-revenue-refunds")).getText().startsWith("PHP "), "refund total shown");
        shot("03-revenue");
        openTab("admin-tab-decisions");
        expect(table("admin-decisions").getItems().size() == 2, "both decisions recorded");
        shot("04-decisions");
        openTab("admin-tab-ticket-purchases");
        openTab("admin-tab-event-approvals");
        openTab("admin-tab-unfinished-operations");
        expect(table("admin-unfinished").getItems().isEmpty(), "no unfinished operations");

        openTab("admin-tab-venues");
        int venueCount = table("admin-venues-table").getItems().size();
        fillVenueForm("Test Garden", "Garden", "50", "1500.00", "5000.00");
        fire("admin-add-venue");
        expect(status().startsWith("Added Test Garden"), "add venue message: " + status());
        expect(table("admin-venues-table").getItems().size() == venueCount + 1, "new venue listed");
        expect(VenueCatalog.all().stream().anyMatch(v -> v.name().equals("Test Garden")), "organizers can book the new venue");
        shot("05-venues");

        fillVenueForm("Bad Rate", "Garden", "50", "abc", "5000.00");
        fire("admin-add-venue");
        expect(status().contains("Hourly rate must be a number"), "bad rate refused: " + status());

        selectVenue("Test Garden");
        fire("admin-remove-venue");
        expect(status().startsWith("Removed Test Garden"), "remove venue message: " + status());
        expect(table("admin-venues-table").getItems().size() == venueCount, "removed venue left the list");

        selectVenue(venue.name());
        fire("admin-remove-venue");
        expect(status().contains("cannot be removed"), "venue with a live reservation is protected: " + status());
        stage.close();
    }

    private static void openTab(String id) {
        TabPane tabs = (TabPane) node("admin-tabs");
        tabs.getTabs().stream().filter(t -> id.equals(t.getId())).findFirst()
                .ifPresentOrElse(t -> tabs.getSelectionModel().select(t), () -> { throw new AssertionError("missing tab " + id); });
        layout();
    }

    private static void fillVenueForm(String name, String category, String capacity, String hourly, String halfDay) {
        ((TextField) node("admin-venue-name")).setText(name);
        ((TextField) node("admin-venue-category")).setText(category);
        ((TextField) node("admin-venue-capacity")).setText(capacity);
        ((TextField) node("admin-venue-hourly")).setText(hourly);
        ((TextField) node("admin-venue-half-day")).setText(halfDay);
    }

    @SuppressWarnings("unchecked")
    private static void selectVenue(String name) {
        TableView<VenueCatalog.Entry> table = (TableView<VenueCatalog.Entry>) table("admin-venues-table");
        table.getSelectionModel().select(table.getItems().stream()
                .filter(entry -> entry.venue().name().equals(name)).findFirst().orElseThrow());
    }

    private static void layout() { scene.getRoot().applyCss(); scene.getRoot().layout(); }
    private static Node node(String id) {
        layout();
        Node found = scene.getRoot().lookup("#" + id);
        if (found == null) throw new AssertionError("missing node #" + id);
        return found;
    }
    private static TableView<?> table(String id) { return (TableView<?>) node(id); }
    private static String status() { return ((Label) node("admin-status")).getText(); }
    private static void fire(String id) { ((ButtonBase) node(id)).fire(); }
    private static void expect(boolean condition, String what) { if (!condition) throw new AssertionError(what); }

    private static void shot(String name) throws Exception {
        if (screenshots == null) return;
        layout();
        WritableImage image = scene.snapshot(null);
        PixelReader pixels = image.getPixelReader();
        BufferedImage out = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < out.getHeight(); y++)
            for (int x = 0; x < out.getWidth(); x++) out.setRGB(x, y, pixels.getArgb(x, y));
        javax.imageio.ImageIO.write(out, "png", screenshots.resolve(name + ".png").toFile());
    }
}
