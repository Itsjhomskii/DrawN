package drawn.gui.views;

import javafx.geometry.Pos;
import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.beans.binding.Bindings;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.layout.Region;


/** Shared controls and layout for customer login and registration forms. */
final class AuthenticationFormSupport {
    private AuthenticationFormSupport() { }

    static Label errorLabel() {
        Label error = new Label();
        error.getStyleClass().add("form-error");
        error.setWrapText(true);
        return error;
    }

    static TextField textField(String prompt) {
        TextField field = new TextField();
        field.setPromptText(prompt);
        field.getStyleClass().add("text-input");
        return field;
    }

    static PasswordInput passwordField(String prompt) {
        return new PasswordInput(prompt);
    }

    static HBox iconField(String iconClass, TextField field) {
        HBox row = new HBox(12, new DashboardIcon(iconClass, 17), field);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("auth-icon-field");
        HBox.setHgrow(field, Priority.ALWAYS);
        field.focusedProperty().addListener((observable, oldValue, focused) -> {
            if (focused) {
                if (!row.getStyleClass().contains("input-focused")) row.getStyleClass().add("input-focused");
            } else row.getStyleClass().remove("input-focused");
        });
        return row;
    }

    static Label subtitle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("secondary-text");
        label.setWrapText(true);
        return label;
    }

    static Label fieldLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("auth-field-label");
        return label;
    }

    static VBox labeledField(String label, Node field) {
        VBox group = new VBox(6, fieldLabel(label), field);
        group.getStyleClass().add("auth-field-group");
        return group;
    }

    static Parent overlayLayout(VBox form, String imageResource, boolean formOnLeft, Node photoCopy) {
        form.setAlignment(Pos.CENTER_LEFT);
        form.setMaxWidth(Double.MAX_VALUE);
        // Keep the cream surface at its natural content height.  HBox still fills
        // the window for positioning, while the card itself stays vertically centered.
        form.setMinHeight(Region.USE_PREF_SIZE);
        form.setMaxHeight(Region.USE_PREF_SIZE);
        form.setFillWidth(true);
        form.getStyleClass().add("auth-form-content");
        if (!form.getStyleClass().contains("auth-form")) form.getStyleClass().add("auth-form");
        form.setId("auth-form-card");

        java.net.URL resource = AuthenticationFormSupport.class.getResource(imageResource);
        if (resource == null) throw new IllegalStateException("Missing authentication image: " + imageResource);
        Image image = new Image(resource.toExternalForm());
        ImageView imageView = new ImageView(image);
        imageView.setPreserveRatio(false);
        StackPane root = new StackPane();
        root.getStyleClass().add("auth-root");
        root.heightProperty().addListener((observable, oldHeight, height) -> {
            boolean compact = height.doubleValue() < 760;
            if (compact && !root.getStyleClass().contains("auth-compact")) root.getStyleClass().add("auth-compact");
            else if (!compact) root.getStyleClass().remove("auth-compact");
            form.setSpacing(compact ? 4 : (form.getStyleClass().contains("auth-form-register") ? 7 : 14));
            form.lookupAll(".auth-field-group").forEach(node -> {
                if (node instanceof VBox group) group.setSpacing(compact ? 3 : 6);
            });
        });
        var cardWidth = Bindings.createDoubleBinding(
                () -> Math.min(560, Math.max(360, root.getWidth() * 0.37)), root.widthProperty());
        form.setMinWidth(360);
        form.prefWidthProperty().bind(cardWidth);
        form.maxWidthProperty().bind(cardWidth);
        imageView.fitWidthProperty().bind(root.widthProperty());
        imageView.fitHeightProperty().bind(root.heightProperty());
        root.widthProperty().addListener((observable, oldValue, width) -> cropToCover(imageView, image, width.doubleValue(), root.getHeight()));
        root.heightProperty().addListener((observable, oldValue, height) -> cropToCover(imageView, image, root.getWidth(), height.doubleValue()));
        Region shade = new Region();
        shade.getStyleClass().add("auth-photo-shade");

        HBox content = new HBox();
        content.setAlignment(Pos.CENTER);
        content.paddingProperty().bind(Bindings.createObjectBinding(
                () -> new Insets(0, root.getWidth() * 0.04, 0, root.getWidth() * 0.04), root.widthProperty()));
        content.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        Node left = formOnLeft ? form : photoCopy;
        Node right = formOnLeft ? photoCopy : form;
        content.getChildren().addAll(left, right);
        HBox.setHgrow(photoCopy, Priority.ALWAYS);
        if (photoCopy instanceof VBox hero) {
            hero.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            hero.setAlignment(Pos.TOP_LEFT);
            hero.setPadding(new Insets(54, 28, 0, 28));
            Region heroSpacer = new Region();
            heroSpacer.prefHeightProperty().bind(root.heightProperty().multiply(0.14));
            hero.getChildren().add(1, heroSpacer);
        }
        HBox.setHgrow(form, Priority.NEVER);
        root.getChildren().addAll(imageView, shade, content);
        return root;
    }

    private static void cropToCover(ImageView view, Image image, double width, double height) {
        if (width <= 0 || height <= 0 || image.getWidth() <= 0 || image.getHeight() <= 0) return;
        double targetRatio = width / height;
        double imageRatio = image.getWidth() / image.getHeight();
        if (imageRatio > targetRatio) {
            double cropWidth = image.getHeight() * targetRatio;
            view.setViewport(new Rectangle2D((image.getWidth() - cropWidth) / 2, 0, cropWidth, image.getHeight()));
        } else {
            double cropHeight = image.getWidth() / targetRatio;
            view.setViewport(new Rectangle2D(0, (image.getHeight() - cropHeight) / 2, image.getWidth(), cropHeight));
        }
    }

    static String userMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? "Something went wrong. Please try again."
                : message;
    }
}
