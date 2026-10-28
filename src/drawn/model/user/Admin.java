package drawn.model.user;

/** An administrator who can manage spaces and verify payments. */
public final class Admin extends User {
    public Admin(String id, String username, String name, String email) {
        super(id, username, name, email);
    }

    @Override
    public String dashboardLabel() {
        return "Admin Dashboard";
    }
}
