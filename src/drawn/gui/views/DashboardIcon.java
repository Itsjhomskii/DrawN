package drawn.gui.views;

import javafx.scene.AccessibleRole;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;

import java.util.Map;

/** Small, local vector equivalents for the icon classes supplied in the DrawN design reference. */
final class DashboardIcon extends StackPane {
    private static final Map<String, String> PATHS = Map.ofEntries(
            Map.entry("fi fi-rr-home", "M3 10.8 L12 3 L21 10.8 M5.5 9.2 V21 H18.5 V9.2 M9.3 21 V14 H14.7 V21"),
            Map.entry("fi fi-rr-search", "M10.8 18.2 A7.4 7.4 0 1 1 10.8 3.4 A7.4 7.4 0 1 1 10.8 18.2 M16.3 16.3 L21 21"),
            Map.entry("fi fi-rs-search-alt", "M10.8 18.2 A7.4 7.4 0 1 1 10.8 3.4 A7.4 7.4 0 1 1 10.8 18.2 M16.3 16.3 L21 21 M6.7 10.8 H10.8 M8.75 8.75 V12.85"),
            Map.entry("fi fi-br-menu-burger", "M4 6 H20 M4 12 H20 M4 18 H20"),
            Map.entry("fi fi-rr-angle-left", "M15 4 L7 12 L15 20"),
            // Filled, closed path so the floating action icon remains visible at small sizes.
            Map.entry("fi fi-rr-add", "M10 3 H14 V10 H21 V14 H14 V21 H10 V14 H3 V10 H10 Z"),
            Map.entry("fi fi-rs-heart", "M20.8 8.4 C20.8 13.5 12 20 12 20 C12 20 3.2 13.5 3.2 8.4 C3.2 3.6 9 2.3 12 6.8 C15 2.3 20.8 3.6 20.8 8.4 Z"),
            Map.entry("fi fi-rr-wallet", "M3 7 H19 A2 2 0 0 1 21 9 V19 H5 A3 3 0 0 1 2 16 V8 A3 3 0 0 1 5 5 H18 M16 12 H22 V16 H16 A2 2 0 0 1 16 12 Z M18.5 14 H18.6"),
            Map.entry("fi fi-tr-book-bookmark", "M5 4 A2 2 0 0 1 7 2 H19 V21 L13 17 L7 21 V4 M5 4 V19 A2 2 0 0 0 7 21"),
            Map.entry("fi fi-tr-ticket", "M4 6 H20 V10 A2 2 0 0 0 20 14 V18 H4 V14 A2 2 0 0 0 4 10 Z M9 7 V9 M9 12 V14 M9 17 V17"),
            Map.entry("fi fi-sr-pencil", "M4 16.5 L3 21 L7.5 20 L20 7.5 L16.5 4 Z M14.5 6 L18 9.5 M4 16.5 L7.5 20"),
            Map.entry("fi fi-rr-envelope", "M3 6 H21 V18 H3 Z M3 7 L12 13 L21 7"),
            Map.entry("fi fi-rr-lock", "M5 10 H19 V21 H5 Z M8 10 V7 A4 4 0 0 1 16 7 V10 M12 14 V17"),
            Map.entry("fi fi-rr-eye", "M2 12 C4.5 7.5 8 5 12 5 C16 5 19.5 7.5 22 12 C19.5 16.5 16 19 12 19 C8 19 4.5 16.5 2 12 Z M15 12 A3 3 0 1 1 9 12 A3 3 0 1 1 15 12 Z"),
            Map.entry("fi fi-rr-eye-crossed", "M3 3 L21 21 M10.6 5.2 C11.1 5.1 11.5 5 12 5 C16 5 19.5 7.5 22 12 C21 13.8 19.8 15.3 18.4 16.5 M7.1 7.1 C5 8.1 3.3 9.8 2 12 C4.5 16.5 8 19 12 19 C13.3 19 14.5 18.8 15.6 18.3 M9.9 9.9 A3 3 0 0 0 14.1 14.1"),
            Map.entry("fi fi-rr-mobile-button", "M7 2 H17 A2 2 0 0 1 19 4 V20 A2 2 0 0 1 17 22 H7 A2 2 0 0 1 5 20 V4 A2 2 0 0 1 7 2 Z M10 18 H14"),
            Map.entry("fi fi-rs-circle", "M21 12 A9 9 0 1 1 3 12 A9 9 0 1 1 21 12 Z"),
            Map.entry("fi fi-rs-check-circle", "M21 12 A9 9 0 1 1 3 12 A9 9 0 1 1 21 12 Z M7.5 12.2 L10.5 15.2 L16.8 8.8"),
            Map.entry("fi fi-rs-peso-sign", "M7 21 V3 H13 A5 5 0 0 1 13 13 H7 M5 9 H17 M5 13 H16")
    );
    private final SVGPath shape = new SVGPath();

    DashboardIcon(String iconClass, double size) {
        setMinSize(size, size);
        setPrefSize(size, size);
        setMaxSize(size, size);
        setAccessibleRole(AccessibleRole.IMAGE_VIEW);
        setAccessibleText(iconClass);
        getStyleClass().add("nav-icon");
        shape.getStyleClass().add("nav-icon-shape");
        shape.setScaleX(size / 24.0);
        shape.setScaleY(size / 24.0);
        getChildren().add(shape);
        setIconClass(iconClass);
    }

    void setIconClass(String iconClass) {
        shape.setContent(PATHS.getOrDefault(iconClass, PATHS.get("fi fi-rr-home")));
        if ("fi fi-rr-add".equals(iconClass)) {
            if (!shape.getStyleClass().contains("solid-add-icon")) shape.getStyleClass().add("solid-add-icon");
        } else {
            shape.getStyleClass().remove("solid-add-icon");
        }
        setAccessibleText(iconClass);
    }
}
