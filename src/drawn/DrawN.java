package drawn;

import drawn.model.user.ContactMethod;
import drawn.model.user.Customer;
import drawn.storage.AccountStore;

import java.math.BigDecimal;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Scanner;

/** Console entry point for the DrawN event management and ticketing app. */
public final class DrawN {
    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "234";

    private final Scanner input = new Scanner(System.in);
    private final List<Event> events = new ArrayList<>();
    private final AccountStore accounts = new AccountStore(Path.of("data"));

    private DrawN() {
    }

    public static void main(String[] args) {
        new DrawN().run();
    }

    private void run() {
        System.out.println("========================================");
        System.out.println("  DrawN - Event Management & Ticketing");
        System.out.println("========================================");

        boolean running = true;
        while (running) {
            System.out.println("\n--- DrawN ---");
            System.out.println("1. Log in");
            System.out.println("2. Register");
            System.out.println("0. Exit");
            int choice = readInt("Choose an option: ", 0, 2);
            if (choice == 0) {
                running = false;
            } else if (choice == 2) {
                registerCustomer();
            } else {
                login();
            }
        }

        System.out.println("Thanks for using DrawN. Goodbye!");
    }

    private void login() {
        String contact = readRequiredText("Email address or phone number (admin for admin login): ");
        String password = readPassword("Password: ");
        if (contact.equalsIgnoreCase(ADMIN_USERNAME) && password.equals(ADMIN_PASSWORD)) {
            System.out.println("Welcome, admin!");
            adminMenu();
            return;
        }
        try {
            String normalized = normalizeLoginContact(contact);
            AccountStore.Account account = accounts.loadAccounts().stream()
                    .filter(candidate -> candidate.getContactValue().equalsIgnoreCase(normalized))
                    .filter(candidate -> candidate.getPassword().equals(password))
                    .findFirst().orElse(null);
            if (account == null) {
                System.out.println("Incorrect contact or password. Please try again.");
                return;
            }
            System.out.println("Welcome, " + account.getFullName() + "!");
            try {
                Customer customer = new Customer(account.getId(), account.getFullName(), account.getContactMethod(),
                        account.getContactValue(), LocalDate.parse(account.getDateOfBirth()));
                System.out.println("Organizer level: " + customer.getOrganizerLevel());
            } catch (IllegalArgumentException ex) {
                System.out.println("Organizer level could not be calculated: " + ex.getMessage());
            }
            customerMenu(account);
        } catch (IllegalArgumentException ex) {
            System.out.println("Enter a valid email address or exactly 10 phone digits.");
        } catch (IOException ex) {
            System.out.println("Could not read customer accounts: " + ex.getMessage());
        }
    }

    private void registerCustomer() {
        System.out.println("\nCustomer Registration");
        String firstName = readRequiredText("First name: ");
        String lastName = readRequiredText("Last name: ");
        LocalDate dateOfBirth = readDateOfBirth();

        System.out.println("\nChoose your preferred contact method:\n");
        System.out.println("[1] Email Address");
        System.out.println("[2] Phone Number\n");
        int methodChoice = readInt("Enter your choice: ", 1, 2);
        ContactMethod method = methodChoice == 1 ? ContactMethod.EMAIL : ContactMethod.PHONE;
        String email = null;
        String phone = null;
        try {
            if (method == ContactMethod.EMAIL) {
                email = readEmail();
            } else {
                phone = readPhone();
            }
            String password = readRequiredPassword();
            BigDecimal initialAmount = readInitialFunding();
            String contactValue = method == ContactMethod.EMAIL ? email : phone;
            String id = contactValue;
            AccountStore.Account account = new AccountStore.Account(id, firstName, lastName,
                    dateOfBirth.toString(), email, phone, method, password, initialAmount);
            new Customer(id, firstName + " " + lastName, method, contactValue, dateOfBirth);
            accounts.register(account, initialAmount);
            if (method == ContactMethod.EMAIL) {
                System.out.println("A simulated confirmation email has been sent to " + email + ".");
            }
            System.out.println("\nWALLET SETUP COMPLETE!\n");
            System.out.printf("Initial Wallet Balance: PHP %s%n%nYour account is now ready!%n",
                    initialAmount.setScale(2).toPlainString());
            customerMenu(account);
        } catch (IllegalArgumentException ex) {
            System.out.println("[ERROR] " + ex.getMessage());
        } catch (IOException ex) {
            System.out.println("[ERROR] Registration could not be saved: " + ex.getMessage());
        }
    }

    private LocalDate readDateOfBirth() {
        while (true) {
            System.out.print("Date of birth (YYYY-MM-DD): ");
            String value = readLine().trim();
            try {
                LocalDate dob = LocalDate.parse(value);
                LocalDate today = LocalDate.now();
                if (dob.isAfter(today)) {
                    System.out.println("Date of birth cannot be in the future.");
                } else if (java.time.Period.between(dob, today).getYears() < 16) {
                    System.out.println("Customers must be at least 16 years old.");
                } else {
                    return dob;
                }
            } catch (RuntimeException ex) {
                System.out.println("Enter a valid date in YYYY-MM-DD format.");
            }
        }
    }

    private String readEmail() {
        while (true) {
            String email = readRequiredText("Email address: ").trim();
            if (email.matches("(?i)^[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9-]+(?:\\.[a-z0-9-]+)+$")) {
                return email.toLowerCase(java.util.Locale.ROOT);
            }
            System.out.println("Enter a valid email address.");
        }
    }

    private String readPhone() {
        while (true) {
            String phone = readRequiredText("Phone number (10 digits): ");
            if (phone.matches("\\d{10}")) return "+63" + phone;
            System.out.println("Enter exactly 10 numerical digits.");
        }
    }

    private String normalizeLoginContact(String contact) {
        if (contact.contains("@")) {
            if (!contact.matches("(?i)^[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9-]+(?:\\.[a-z0-9-]+)+$")) {
                throw new IllegalArgumentException("Invalid email.");
            }
            return contact.toLowerCase(java.util.Locale.ROOT);
        }
        if (!contact.matches("\\d{10}")) throw new IllegalArgumentException("Invalid phone.");
        return "+63" + contact;
    }

    private BigDecimal readInitialFunding() {
        System.out.println("\n================================");
        System.out.println("       INITIAL WALLET SETUP");
        System.out.println("================================\n");
        System.out.println("Minimum Initial Funding: PHP 500.00\n");
        while (true) {
            BigDecimal amount = readPrice("Enter your initial wallet amount: ");
            if (amount.compareTo(new BigDecimal("500.00")) >= 0) return amount;
            System.out.println("[ERROR] Minimum initial funding is PHP 500.00.\nPlease enter a valid amount.");
        }
    }

    private BigDecimal readTopUp() {
        return readPrice("Enter amount to add (PHP): ");
    }

    private String readPassword(String prompt) {
        System.out.flush();
        java.io.Console console = System.console();
        if (console == null) {
            System.out.print(prompt);
            return readLine();
        }
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("windows")) {
            try {
                System.out.print(prompt);
                return readWindowsMaskedPassword();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // Fall back to the JDK hidden-input reader if the terminal helper is unavailable.
            }
        }
        char[] password = console.readPassword();
        if (password == null) return "";
        return new String(password);
    }

    private String readRequiredPassword() {
        while (true) {
            String password = readPassword("Password: ");
            if (!password.isEmpty()) return password;
            System.out.println("Password cannot be blank.");
        }
    }

    private String readWindowsMaskedPassword() throws IOException, InterruptedException {
        String script = "$b = New-Object System.Text.StringBuilder; "
                + "while ($true) { $k = [Console]::ReadKey($true); "
                + "if ($k.Key -eq [System.ConsoleKey]::Enter) { [Console]::Error.WriteLine(); break }; "
                + "if ($k.Key -eq [System.ConsoleKey]::Backspace) { if ($b.Length -gt 0) { "
                + "$b.Length = $b.Length - 1; [Console]::Error.Write([char]8); "
                + "[Console]::Error.Write(' '); [Console]::Error.Write([char]8) } } else { "
                + "$null = $b.Append($k.KeyChar); [Console]::Error.Write('*') } }; "
                + "[Console]::Out.WriteLine([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($b.ToString())))";
        Process process = new ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile", "-Command", script)
                .redirectInput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        process.getInputStream().transferTo(output);
        int exitCode = process.waitFor();
        if (exitCode != 0) throw new IOException("Password input helper failed.");
        String encoded = output.toString(StandardCharsets.US_ASCII).trim();
        try {
            return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw new IOException("Password input helper returned invalid data.", ex);
        }
    }

    private void customerMenu(AccountStore.Account account) {
        boolean inMenu = true;
        while (inMenu) {
            System.out.println("\nCustomer dashboard");
            System.out.println("1. View available events");
            System.out.println("2. Reserve tickets");
            System.out.println("3. View wallet balance");
            System.out.println("4. Add wallet funds");
            System.out.println("5. View wallet transaction history");
            System.out.println("0. Log out");
            int choice = readInt("Choose an option: ", 0, 5);

            switch (choice) {
                case 1 -> listEvents();
                case 2 -> reserveTickets();
                case 3 -> System.out.printf("Wallet balance: PHP %s%n", account.getWalletBalance().setScale(2).toPlainString());
                case 4 -> topUpWallet(account);
                case 5 -> showWalletHistory(account);
                case 0 -> inMenu = false;
                default -> throw new IllegalStateException("Unexpected menu option");
            }
        }
    }

    private void topUpWallet(AccountStore.Account account) {
        BigDecimal amount = readTopUp();
        try {
            accounts.topUp(account, amount);
            System.out.printf("Top-up successful. New wallet balance: PHP %s%n",
                    account.getWalletBalance().setScale(2).toPlainString());
        } catch (IOException | IllegalArgumentException ex) {
            System.out.println("Could not save wallet top-up: " + ex.getMessage());
        }
    }

    private void showWalletHistory(AccountStore.Account account) {
        try {
            List<AccountStore.Transaction> transactions = accounts.loadTransactions(account.getId());
            System.out.println("\nWallet transaction history");
            if (transactions.isEmpty()) {
                System.out.println("No wallet transactions found.");
                return;
            }
            for (AccountStore.Transaction transaction : transactions) {
                System.out.printf("%s | PHP %s | Balance PHP %s | %s%n", transaction.type(),
                        transaction.amount().setScale(2).toPlainString(),
                        transaction.balanceAfter().setScale(2).toPlainString(), transaction.timestamp());
            }
        } catch (IOException ex) {
            System.out.println("Could not read wallet history: " + ex.getMessage());
        }
    }

    private void adminMenu() {
        boolean inMenu = true;
        while (inMenu) {
            System.out.println("\nAdmin dashboard");
            System.out.println("1. List events");
            System.out.println("2. Add event");
            System.out.println("0. Log out");
            int choice = readInt("Choose an option: ", 0, 2);

            switch (choice) {
                case 1 -> listEvents();
                case 2 -> addEvent();
                case 0 -> inMenu = false;
                default -> throw new IllegalStateException("Unexpected menu option");
            }
        }
    }

    private void addEvent() {
        System.out.println("\nAdd event");
        String name = readRequiredText("Event name: ");
        String date = readRequiredText("Date (for example, 2026-12-31): ");
        int capacity = readInt("Ticket capacity: ", 1, Integer.MAX_VALUE);
        BigDecimal price = readPrice("Ticket price (PHP): ");
        events.add(new Event(name, date, capacity, price));
        System.out.println("Event added.");
    }

    private void listEvents() {
        System.out.println("\nAvailable events");
        if (events.isEmpty()) {
            System.out.println("No events yet. Ask an admin to add one.");
            return;
        }

        for (int i = 0; i < events.size(); i++) {
            Event event = events.get(i);
            System.out.printf("%d. %s | %s | PHP %s | %d tickets left%n",
                    i + 1, event.name, event.date, event.price.toPlainString(), event.remaining);
        }
    }

    private void reserveTickets() {
        if (events.isEmpty()) {
            System.out.println("There are no events to reserve yet.");
            return;
        }

        listEvents();
        int eventNumber = readInt("Choose an event number (0 to cancel): ", 0, events.size());
        if (eventNumber == 0) {
            return;
        }

        Event event = events.get(eventNumber - 1);
        if (event.remaining == 0) {
            System.out.println("That event is sold out.");
            return;
        }

        String customerName = readRequiredText("Your name: ");
        int quantity = readInt("Number of tickets: ", 1, event.remaining);
        event.remaining -= quantity;
        BigDecimal total = event.price.multiply(BigDecimal.valueOf(quantity));
        System.out.printf("Reserved %d ticket(s) for %s. Total: PHP %s.%n",
                quantity, customerName, total.toPlainString());
        System.out.println("Payment verification is not available in this starter version.");
    }

    private String readRequiredText(String prompt) {
        while (true) {
            System.out.print(prompt);
            String value = readLine().trim();
            if (!value.isEmpty()) {
                return value;
            }
            System.out.println("Please enter a value.");
        }
    }

    private int readInt(String prompt, int minimum, int maximum) {
        while (true) {
            System.out.print(prompt);
            String value = readLine().trim();
            try {
                int number = Integer.parseInt(value);
                if (number >= minimum && number <= maximum) {
                    return number;
                }
            } catch (NumberFormatException ignored) {
                // Show the same helpful retry message for invalid text and out-of-range values.
            }
            System.out.printf("Enter a whole number from %d to %d.%n", minimum, maximum);
        }
    }

    private BigDecimal readPrice(String prompt) {
        while (true) {
            System.out.print(prompt);
            String value = readLine().trim();
            try {
                BigDecimal price = new BigDecimal(value);
                if (price.signum() >= 0 && price.scale() <= 2) {
                    return price;
                }
            } catch (NumberFormatException ignored) {
                // Show the same helpful retry message for invalid text and invalid prices.
            }
            System.out.println("Enter a non-negative amount with up to 2 decimal places.");
        }
    }

    private String readLine() {
        java.io.Console console = System.console();
        System.out.flush();
        return console == null ? input.nextLine() : console.readLine();
    }

    private static final class Event {
        private final String name;
        private final String date;
        private final BigDecimal price;
        private int remaining;

        private Event(String name, String date, int capacity, BigDecimal price) {
            this.name = name;
            this.date = date;
            this.price = price;
            this.remaining = capacity;
        }
    }
}
