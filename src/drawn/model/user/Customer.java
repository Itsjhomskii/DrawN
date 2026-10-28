package drawn.model.user;

/** A customer who can create event bookings and ticket reservations. */
public final class Customer extends User {
    public Customer(String id, String username, String name, String email) {
        super(id, username, name, email);
    }

    @Override
    public String dashboardLabel() {
        return "Customer Dashboard";
    }
}
