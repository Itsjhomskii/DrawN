package drawn;

import drawn.model.user.Customer;
import drawn.storage.AccountStore;

/** Customer console menu and feature-module composition. */
final class CustomerConsole {
    private final DrawN host;
    private final CustomerProfileConsole profile;
    private final CustomerTicketsConsole tickets;
    private final CustomerEventsConsole events;
    private final CustomerWalletConsole wallet;

    CustomerConsole(DrawN host) {
        this.host = host;
        profile = new CustomerProfileConsole(host);
        tickets = new CustomerTicketsConsole(host);
        events = new CustomerEventsConsole(host, tickets);
        wallet = new CustomerWalletConsole(host, events);
    }

    String displayPurpose(drawn.model.event.EventPurpose purpose) {
        return events.displayPurpose(purpose);
    }

    void open(AccountStore.Account account, Customer customer) {
        boolean inMenu = true;
        while (inMenu) {
            profile.showCustomerDashboard(account, customer);
            host.printSectionHeader("Customer Menu");
            host.printMenuItem("1", "My Profile");
            host.printMenuItem("2", "Events & Venues");
            host.printMenuItem("3", "Tickets");
            host.printMenuItem("4", "Wallet & Transactions");
            host.printMenuItem("0", "Log Out");
            int choice = host.readInt("Enter your choice: ", 0, 4);
            switch (choice) {
                case 1 -> profile.showProfile(account);
                case 2 -> events.eventsAndVenuesMenu(account, customer);
                case 3 -> tickets.ticketsMenu(account, customer);
                case 4 -> wallet.walletMenu(account, customer);
                case 0 -> inMenu = false;
                default -> throw new IllegalStateException("Unexpected menu option");
            }
            if (choice != 0) host.waitForDashboard();
        }
    }
}
