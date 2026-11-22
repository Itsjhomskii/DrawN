package drawn.service.admin;

/** Admin login check for the shared login page. */
public final class AdminService {
    private static final String ADMIN_USERNAME = "admin@drawn.com";
    private static final String ADMIN_PASSWORD = "admin";

    public static boolean isAdminLogin(String username, String password) {
        return username != null && password != null
                && username.equalsIgnoreCase(ADMIN_USERNAME) && password.equals(ADMIN_PASSWORD);
    }
}
