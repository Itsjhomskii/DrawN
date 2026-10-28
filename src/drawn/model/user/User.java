package drawn.model.user;

import java.util.Objects;

/** Shared identity and profile data for a DrawN user. */
public abstract class User {
    private final String id;
    private final String username;
    private String name;
    private String email;

    protected User(String id, String username, String name, String email) {
        this.id = requireText(id, "User ID");
        this.username = requireText(username, "Username");
        this.name = requireText(name, "Name");
        this.email = requireEmail(email);
    }

    public final String getId() {
        return id;
    }

    public final String getUsername() {
        return username;
    }

    public final String getName() {
        return name;
    }

    public final String getEmail() {
        return email;
    }

    public final void updateName(String name) {
        this.name = requireText(name, "Name");
    }

    public final void updateEmail(String email) {
        this.email = requireEmail(email);
    }

    /** Returns a role-specific title for the UI to display. */
    public abstract String dashboardLabel();

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " cannot be null");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return trimmed;
    }

    private static String requireEmail(String value) {
        String email = requireText(value, "Email");
        int at = email.indexOf('@');
        int lastDot = email.lastIndexOf('.');
        if (at <= 0 || lastDot <= at + 1 || lastDot == email.length() - 1) {
            throw new IllegalArgumentException("Email must have a valid address format");
        }
        return email;
    }
}
