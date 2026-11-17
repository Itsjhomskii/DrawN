package drawn.gui.views;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.RotateTransition;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.geometry.Bounds;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import javafx.event.EventHandler;

import java.util.List;

/** Floating customer quick actions. Navigation and workflow logic stay in MainShell. */
final class RadialFab extends Pane {
    private static final double FAB_SIZE = 58;
    private static final double EDGE = 28;
    private static final Duration ACTION_DURATION = Duration.millis(270);

    private final DashboardIcon plusIcon = new DashboardIcon("fi fi-rr-add", 23);
    private final Button toggle = new Button("", plusIcon);
    private final List<Button> actions;
    private final List<Runnable> callbacks;
    private final List<Target> targets;
    private Timeline motion;
    private ParallelTransition activeTransition;
    private boolean expanded;
    private boolean animating;
    private final EventHandler<MouseEvent> outsidePressHandler = this::onOutsidePress;
    private final EventHandler<KeyEvent> keyPressHandler = this::onKeyPressed;

    RadialFab(Runnable joinEvent, Runnable requestVenue, Runnable createEvent) {
        getStyleClass().add("radial-fab-layer");
        setPickOnBounds(false);
        callbacks = List.of(joinEvent, requestVenue, createEvent);
        actions = List.of(
                action("Join an Event", "fi fi-tr-ticket", "quick-join-event"),
                action("Request a Venue", "fi fi-rr-marker", "quick-request-venue"),
                action("Create & Publish Event", "fi fi-rr-calendar", "quick-create-event"));
        targets = List.of(new Target(-85, -180), new Target(-155, -105), new Target(-185, -15));
        actions.forEach(action -> { action.setVisible(false); action.setManaged(false); getChildren().add(action); });
        toggle.setId("quick-actions-toggle");
        toggle.getStyleClass().add("radial-fab");
        toggle.setTooltip(new Tooltip("Quick Actions"));
        toggle.setAccessibleText("Quick Actions");
        toggle.setFocusTraversable(true);
        toggle.setOnAction(event -> toggle());
        getChildren().add(toggle);
        for (int i = 0; i < actions.size(); i++) {
            int index = i;
            actions.get(i).setOnAction(event -> {
                Runnable callback = callbacks.get(index);
                collapse();
                callback.run();
            });
        }
        sceneProperty().addListener((observable, oldScene, scene) -> {
            if (oldScene != null) detachSceneHandlers(oldScene);
            if (scene != null) attachSceneHandlers(scene);
        });
    }

    private static Button action(String text, String icon, String id) {
        DashboardIcon graphic = new DashboardIcon(icon, 17);
        graphic.getStyleClass().add("radial-fab-action-icon");
        Button button = new Button(text, graphic);
        button.setId(id);
        button.getStyleClass().add("radial-fab-action");
        button.setAccessibleText(text);
        button.setTooltip(new Tooltip(text));
        button.setAlignment(Pos.CENTER_LEFT);
        return button;
    }

    private void toggle() {
        if (expanded) collapse(); else expand();
    }

    private void expand() {
        expanded = true;
        animating = true;
        toggle.setAccessibleText("Close quick actions");
        toggle.setTooltip(new Tooltip("Close quick actions"));
        layoutChildren();
        Timeline timeline = new Timeline();
        for (int index = 0; index < actions.size(); index++) {
            Button action = actions.get(index);
            Target target = targets.get(index);
            double delay = index * 42;
            double startX = -target.x;
            double startY = -target.y;
            double midX = startX * 0.25 + 9;
            double midY = startY * 0.25 - 7;
            action.setVisible(true);
            action.setOpacity(0);
            action.setTranslateX(startX);
            action.setTranslateY(startY);
            timeline.getKeyFrames().addAll(
                    new KeyFrame(Duration.ZERO, new KeyValue(action.opacityProperty(), 0)),
                    new KeyFrame(Duration.millis(delay), new KeyValue(action.opacityProperty(), 0)),
                    new KeyFrame(Duration.millis(delay + 145),
                            new KeyValue(action.translateXProperty(), midX, Interpolator.EASE_BOTH),
                            new KeyValue(action.translateYProperty(), midY, Interpolator.EASE_BOTH),
                            new KeyValue(action.opacityProperty(), 0.75, Interpolator.EASE_BOTH)),
                    new KeyFrame(Duration.millis(delay + ACTION_DURATION.toMillis()),
                            new KeyValue(action.translateXProperty(), 0, Interpolator.EASE_OUT),
                            new KeyValue(action.translateYProperty(), 0, Interpolator.EASE_OUT),
                            new KeyValue(action.opacityProperty(), 1, Interpolator.EASE_OUT)));
        }
        play(timeline, true);
    }

    private void collapse() {
        if (!expanded) return;
        if (activeTransition != null) activeTransition.stop();
        animating = false;
        expanded = false;
        animating = true;
        toggle.setAccessibleText("Quick Actions");
        toggle.setTooltip(new Tooltip("Quick Actions"));
        Timeline timeline = new Timeline();
        for (int index = 0; index < actions.size(); index++) {
            Button action = actions.get(index);
            Target target = targets.get(index);
            double delay = (actions.size() - 1 - index) * 38;
            double endX = -target.x;
            double endY = -target.y;
            double midX = endX * 0.25 + 9;
            double midY = endY * 0.25 - 7;
            timeline.getKeyFrames().addAll(
                    new KeyFrame(Duration.ZERO),
                    new KeyFrame(Duration.millis(delay + 130),
                            new KeyValue(action.translateXProperty(), midX, Interpolator.EASE_BOTH),
                            new KeyValue(action.translateYProperty(), midY, Interpolator.EASE_BOTH),
                            new KeyValue(action.opacityProperty(), 0.55, Interpolator.EASE_BOTH)),
                    new KeyFrame(Duration.millis(delay + ACTION_DURATION.toMillis()),
                            new KeyValue(action.translateXProperty(), endX, Interpolator.EASE_IN),
                            new KeyValue(action.translateYProperty(), endY, Interpolator.EASE_IN),
                            new KeyValue(action.opacityProperty(), 0, Interpolator.EASE_IN)));
        }
        play(timeline, false);
    }

    private void play(Timeline timeline, boolean opening) {
        if (activeTransition != null) activeTransition.stop();
        motion = timeline;
        RotateTransition rotate = new RotateTransition(Duration.millis(240), plusIcon);
        rotate.setToAngle(opening ? 45 : 0);
        rotate.setInterpolator(Interpolator.EASE_BOTH);
        ParallelTransition transition = new ParallelTransition(timeline, rotate);
        activeTransition = transition;
        transition.setOnFinished(event -> {
            animating = false;
            if (!expanded) actions.forEach(action -> action.setVisible(false));
            requestLayout();
        });
        transition.play();
    }

    private void attachSceneHandlers(Scene scene) {
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, outsidePressHandler);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, keyPressHandler);
    }

    private void detachSceneHandlers(Scene scene) {
        scene.removeEventFilter(MouseEvent.MOUSE_PRESSED, outsidePressHandler);
        scene.removeEventFilter(KeyEvent.KEY_PRESSED, keyPressHandler);
    }

    private void onOutsidePress(MouseEvent event) {
        if (expanded && !isChildOfFab(event.getTarget())) collapse();
    }

    private void onKeyPressed(KeyEvent event) {
        if (expanded && event.getCode() == KeyCode.ESCAPE) {
            collapse();
            event.consume();
        }
    }

    /** Let clicks fall through everywhere except the visible floating controls. */
    @Override public boolean contains(double localX, double localY) {
        for (Node child : getChildren()) {
            if (!child.isVisible()) continue;
            Bounds bounds = child.getBoundsInParent();
            if (bounds.contains(localX, localY)) return true;
        }
        return false;
    }

    private boolean isChildOfFab(Object target) {
        if (!(target instanceof Node node)) return false;
        for (Node current = node; current != null; current = current.getParent()) if (current == this) return true;
        return false;
    }

    @Override protected void layoutChildren() {
        double width = getWidth(), height = getHeight();
        if (width <= 0 || height <= 0) return;
        double fabX = Math.max(EDGE, width - EDGE - FAB_SIZE);
        double fabY = Math.max(EDGE, height - EDGE - FAB_SIZE);
        toggle.resizeRelocate(fabX, fabY, FAB_SIZE, FAB_SIZE);
        double radiusScale = Math.min(1, Math.max(0.55, Math.min((height - 110) / 180, (width - 190) / 185)));
        for (int i = 0; i < actions.size(); i++) {
            Button action = actions.get(i);
            double buttonWidth = Math.min(Math.max(178, action.prefWidth(-1)), Math.max(150, width - 24));
            double buttonHeight = 44;
            action.resize(buttonWidth, buttonHeight);
            Target target = targets.get(i);
            double centerX = fabX + FAB_SIZE / 2 + target.x * radiusScale;
            double centerY = fabY + FAB_SIZE / 2 + target.y * radiusScale;
            double x = clamp(centerX - buttonWidth / 2, 12, Math.max(12, width - buttonWidth - 12));
            double y = clamp(centerY - buttonHeight / 2, 12, Math.max(12, height - buttonHeight - 12));
            action.relocate(x, y);
        }
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private record Target(double x, double y) { }
}
