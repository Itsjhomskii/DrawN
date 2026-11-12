package drawn.gui.views;

import drawn.storage.AccountStore;
import javafx.scene.Node;

/** Read-only profile (console showProfile). */
final class ProfilePage {
    private final MainShell shell;

    ProfilePage(MainShell shell) { this.shell = shell; }

    Node view() {
        AccountStore.Account account = shell.session().account();
        String email = blank(account.getEmail()) ? "Not provided" : account.getEmail();
        String phone = blank(account.getPhone()) ? "Not provided" : account.getPhone();
        String username = blank(account.getEmail()) ? account.getPhone() : account.getEmail();
        var logOut = Ui.secondary("Log Out", shell::logOut);
        logOut.setId("profile-logout");
        return Ui.page("My Profile", "Your DrawN customer account.", Ui.card(Ui.details(
                "Name", account.getFullName(),
                "Date of birth", account.getDateOfBirth(),
                "Email", email,
                "Phone", phone,
                "Username", username,
                "Organizer level", String.valueOf(shell.session().customer().getOrganizerLevel()))), Ui.actions(logOut));
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
