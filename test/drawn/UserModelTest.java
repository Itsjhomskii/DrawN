package drawn;

import drawn.model.user.Admin;
import drawn.model.user.Customer;
import drawn.model.user.User;

/** Dependency-free checks for user validation and role polymorphism. */
public final class UserModelTest {
    private UserModelTest() {
    }

    public static void main(String[] args) {
        User customer = new Customer("CUS-1", "jhomskii", "Jhomari", "jhomari@example.test");
        User admin = new Admin("ADM-1", "admin", "DrawN Admin", "admin@example.test");

        assert customer.dashboardLabel().equals("Customer Dashboard");
        assert admin.dashboardLabel().equals("Admin Dashboard");

        customer.updateName("  Jhomari Reyes  ");
        assert customer.getName().equals("Jhomari Reyes");

        expectIllegalArgument(() -> customer.updateEmail("not-an-email"));
        expectIllegalArgument(() -> new Customer("CUS-2", "", "Name", "name@example.test"));

        System.out.println("UserModelTest passed.");
    }

    private static void expectIllegalArgument(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected validation failure.
        }
    }
}
