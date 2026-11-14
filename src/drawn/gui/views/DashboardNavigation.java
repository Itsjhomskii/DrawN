package drawn.gui.views;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;

import java.util.List;
import java.util.function.Consumer;

/** Customer dashboard navigation definitions and shared button construction. */
final class DashboardNavigation {
    private static final List<Item> ITEMS = List.of(
            new Item("Home", "fi fi-rr-home"),
            new Item("Explore Events", "fi fi-rr-search"),
            new Item("Explore Venues", "fi fi-rs-search-alt"),
            new Item("My Events", "fi fi-rs-heart"),
            new Item("Venue Reservations", "fi fi-tr-book-bookmark"),
            new Item("My Tickets", "fi fi-tr-ticket"),
            new Item("Wallet & Transactions", "fi fi-rr-wallet")
    );

    private DashboardNavigation() { }

    static List<Item> items() { return ITEMS; }

    static Button button(Item item, boolean collapsed, Consumer<String> navigate) {
        Button button = new Button(collapsed ? "" : item.label(), new DashboardIcon(item.iconClass(), 19));
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.CENTER_LEFT);
        button.setGraphicTextGap(14);
        button.getStyleClass().add("sidebar-item");
        button.setTooltip(new Tooltip(item.label()));
        button.setAccessibleText(item.label());
        button.setOnAction(event -> navigate.accept(item.label()));
        return button;
    }

    record Item(String label, String iconClass) { }
}
