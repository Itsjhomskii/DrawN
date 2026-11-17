package drawn.gui.views;

import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;

/** Shared icon-only back action for customer detail views. */
final class BackNavigation {
    private BackNavigation() { }

    static Button button(Runnable navigateBack) {
        Button back = new Button("", new DashboardIcon("fi fi-rr-arrow-small-left", 18));
        back.setId("back-navigation");
        back.getStyleClass().add("back-navigation");
        back.setAccessibleText("Go Back");
        back.setTooltip(new Tooltip("Go Back"));
        back.setOnAction(event -> navigateBack.run());
        return back;
    }
}
