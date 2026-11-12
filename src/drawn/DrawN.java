package drawn;

import drawn.model.user.ContactMethod;
import drawn.model.user.Customer;
import drawn.model.event.Event;
import drawn.model.event.TicketCategory;
import drawn.service.event.ReservationService;
import drawn.service.event.EventPublishingService;
import drawn.service.event.EventCancellationService;
import drawn.service.payment.PaymentService;
import drawn.service.payment.CommissionPolicy;
import drawn.service.payment.OrganizerEarningsService;
import drawn.service.event.EventService;
import drawn.service.customer.CustomerAccessService;
import drawn.service.customer.CustomerEventCreationService;
import drawn.service.customer.CustomerVenueWorkflowService;
import drawn.storage.AccountStore;
import drawn.storage.EventRepository;
import drawn.storage.ReservationRepository;
import drawn.storage.PaymentRepository;
import drawn.storage.TicketRepository;
import drawn.storage.TransactionJournal;
import drawn.storage.PrivateEventAccessRepository;

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
    private static final String ADMIN_USERNAME = "admin@drawn.com";
    private static final String ADMIN_PASSWORD = "admin";

    private final Scanner input = new Scanner(System.in);
    final List<LegacyEvent> events = new ArrayList<>();
    final AccountStore accounts = new AccountStore(Path.of("data"));
    private final CustomerAccessService customerAccess = new CustomerAccessService(accounts);
    final EventRepository eventRepository = new EventRepository(Path.of("data"));
    final ReservationRepository reservationRepository = new ReservationRepository(Path.of("data"));
    final PaymentRepository paymentRepository = new PaymentRepository(Path.of("data"));
    final TicketRepository ticketRepository = new TicketRepository(Path.of("data"));
    final TransactionJournal transactionJournal = new TransactionJournal(Path.of("data"));
    final PrivateEventAccessRepository privateEventAccess = new PrivateEventAccessRepository(Path.of("data"));
    final OrganizerEarningsService earningsService = new OrganizerEarningsService(eventRepository, ticketRepository,
            paymentRepository, CommissionPolicy.configured());
    final EventService eventService = new EventService(eventRepository, reservationRepository, ticketRepository,
            paymentRepository, java.time.Clock.systemDefaultZone());
    final PaymentService paymentService = new PaymentService(accounts, reservationRepository, paymentRepository,
            transactionJournal, eventRepository);
    final ReservationService reservationService = new ReservationService(eventRepository, reservationRepository,
            java.time.Clock.systemDefaultZone(), paymentService);
    final EventPublishingService publishingService = new EventPublishingService(eventRepository,
            reservationRepository, ticketRepository, paymentRepository, accounts, transactionJournal);
    final CustomerVenueWorkflowService venueWorkflow = new CustomerVenueWorkflowService(accounts,
            reservationRepository, reservationService, paymentService);
    final CustomerEventCreationService eventCreationService = new CustomerEventCreationService(eventService,
            publishingService, privateEventAccess);
    final EventCancellationService cancellationService = new EventCancellationService(eventRepository,
            reservationRepository, ticketRepository, paymentRepository, accounts, transactionJournal);

    private final CustomerConsole customerConsole = new CustomerConsole(this);

    private DrawN() {
    }

    public static void main(String[] args) {
        new DrawN().run();
    }

    private void run() {
        try {
            paymentService.recoverPending();
            publishingService.recoverPending();
            cancellationService.recoverPending();
        } catch (IOException | RuntimeException ex) {
            System.out.println("A pending financial operation needs recovery: " + ex.getMessage());
            return;
        }
        printBrandBanner();

        boolean running = true;
        while (running) {
            printSectionHeader("Welcome");
            printMenuItem("1", "Log in");
            printMenuItem("2", "Register");
            printMenuItem("0", "Exit");
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
        String contact = readRequiredText("Email address or phone number: ");
        String password = readPassword("Password: ");
        if (contact.equalsIgnoreCase(ADMIN_USERNAME) && password.equals(ADMIN_PASSWORD)) {
            System.out.println("Welcome, admin!");
            adminMenu();
            return;
        }
        try {
            CustomerAccessService.AuthenticatedCustomer authenticated = customerAccess.authenticate(contact, password);
            AccountStore.Account account = authenticated.account();
            System.out.println("Welcome, " + account.getFullName() + "!");
            Customer customer = authenticated.customer();
            System.out.println("Organizer level: " + customer.getOrganizerLevel());
            customerMenu(account, customer);
        } catch (IllegalArgumentException ex) {
            System.out.println(ex.getMessage());
        } catch (IOException ex) {
            System.out.println("Could not read customer accounts: " + ex.getMessage());
        }
    }

    private void registerCustomer() {
        printSectionHeader("Customer Registration");
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
            CustomerAccessService.AuthenticatedCustomer registered = customerAccess.register(firstName, lastName,
                    dateOfBirth, method, contactValue, password, initialAmount);
            AccountStore.Account account = registered.account();
            Customer customer = registered.customer();
            if (method == ContactMethod.EMAIL) {
                System.out.println("A simulated confirmation email has been sent to " + email + ".");
            }
            System.out.println("\nWALLET SETUP COMPLETE!\n");
            System.out.printf("Initial Wallet Balance: PHP %s%n%nYour account is now ready!%n",
                    initialAmount.setScale(2).toPlainString());
            customerMenu(account, customer);
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

    private BigDecimal readInitialFunding() {
        printSectionHeader("Initial Wallet Setup");
        System.out.println("Minimum Initial Funding: PHP 500.00\n");
        while (true) {
            BigDecimal amount = readPrice("Enter your initial wallet amount: ");
            if (amount.compareTo(new BigDecimal("500.00")) >= 0) return amount;
            System.out.println("[ERROR] Minimum initial funding is PHP 500.00.\nPlease enter a valid amount.");
        }
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

    private void customerMenu(AccountStore.Account account, Customer customer) {
        customerConsole.open(account, customer);
    }

























































    boolean readYesNo(String prompt) {
        while (true) {
            System.out.print(prompt);
            String value = readLine().trim();
            if (value.equalsIgnoreCase("y") || value.equalsIgnoreCase("yes")) return true;
            if (value.equalsIgnoreCase("n") || value.equalsIgnoreCase("no")) return false;
            System.out.println("Enter y or n.");
        }
    }

    void printEventDetails(Event event) {
        System.out.println("Event details");
        System.out.println("  Event name: " + event.name());
        System.out.println("  Event type: " + event.type());
        System.out.println("  Event purpose: " + customerConsole.displayPurpose(event.purpose()));
        System.out.println("  Visibility: " + event.visibility());
        System.out.println("  Event date: " + event.date());
        System.out.println("  Start time: " + event.startTime());
        System.out.println("  End time: " + event.endTime());
        System.out.println("  Venue: " + event.venue().name());
        System.out.println("  Description: " + event.description());
        System.out.println("  Expected attendance: " + event.expectedAttendance());
        if (!event.ticketCategories().isEmpty()) {
            for (TicketCategory category : event.ticketCategories()) {
                System.out.println("  Ticket category: " + category.name());
                System.out.println("    Ticket price: PHP " + category.price().toPlainString());
                System.out.println("    Ticket quantity: " + category.quantity());
            }
        }
        System.out.println("----------------------------------------");
    }















    private void adminMenu() {
        boolean inMenu = true;
        while (inMenu) {
            printSectionHeader("Admin Dashboard");
            printMenuItem("1", "List events");
            printMenuItem("2", "Add event");
            printMenuItem("0", "Log out");
            int choice = readInt("Choose an option: ", 0, 2);

            switch (choice) {
                case 1 -> listEvents();
                case 2 -> addEvent();
                case 0 -> inMenu = false;
                default -> throw new IllegalStateException("Unexpected menu option");
            }
        }
    }

    void waitForDashboard() {
        System.out.println("Press 0 or Enter to return to Customer Dashboard.");
        while (true) {
            String value = readLine().trim();
            if (value.isEmpty() || value.equals("0")) return;
            System.out.println("Press 0 or Enter to return to Customer Dashboard.");
        }
    }

    private void addEvent() {
        printSectionHeader("Add Event");
        String name = readRequiredText("Event name: ");
        String date = readRequiredText("Date (for example, 2026-12-31): ");
        int capacity = readInt("Ticket capacity: ", 1, Integer.MAX_VALUE);
        BigDecimal price = readPrice("Ticket price (PHP): ");
        events.add(new LegacyEvent(name, date, capacity, price));
        System.out.println("Event added.");
    }

    private void listEvents() {
        System.out.println("\nAvailable events");
        boolean found = !events.isEmpty();
        for (int i = 0; i < events.size(); i++) {
            LegacyEvent event = events.get(i);
            System.out.printf("%d. %s | %s | PHP %s | %d tickets left%n",
                    i + 1, event.name, event.date, event.price.toPlainString(), event.remaining);
        }
        try {
            List<Event> approvedPublic = eventService.publicApprovedEvents();
            for (Event event : approvedPublic) {
                found = true;
                System.out.printf("%s | %s | %s | %s | %s %s–%s | %s%n", event.name(), event.type(),
                        event.purpose(), event.visibility(), event.date(), event.startTime(), event.endTime(),
                        event.venue().name());
            }
        } catch (IOException ex) {
            System.out.println("Could not load approved public submissions: " + ex.getMessage());
        }
        if (!found) {
            System.out.println("No events yet. Ask an admin to add one.");
        }
    }

    private void reserveTickets() {
        if (events.isEmpty()) {
            System.out.println("There are no events to reserve yet.");
            return;
        }

        System.out.println("\nAvailable in-memory events");
        for (int i = 0; i < events.size(); i++) {
            LegacyEvent available = events.get(i);
            System.out.printf("Event %d%n", i + 1);
            System.out.println("  Name: " + available.name);
            System.out.println("  Date: " + available.date);
            System.out.println("  Ticket price: PHP " + available.price.toPlainString());
            System.out.println("  Tickets remaining: " + available.remaining);
            System.out.println("----------------------------------------");
        }
        int eventNumber = readInt("Choose an event number (0 to cancel): ", 0, events.size());
        if (eventNumber == 0) {
            return;
        }

        LegacyEvent event = events.get(eventNumber - 1);
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

    void printBrandBanner() {
        System.out.println();
        System.out.println("+--------------------------------------------------+");
        System.out.println("|  DDDD   RRRR    AAA   W   W  N   N              |");
        System.out.println("|  D   D  R   R  A   A  W   W  NN  N              |");
        System.out.println("|  D   D  RRRR   AAAAA  W W W  N N N              |");
        System.out.println("|  D   D  R  R   A   A  W W W  N  NN              |");
        System.out.println("|  DDDD   R   R  A   A   W W   N   N              |");
        System.out.println("|                                                  |");
        System.out.println("|          FROM DRAWING TO DOING                  |");
        System.out.println("+--------------------------------------------------+");
    }

    void printSectionHeader(String title) {
        System.out.println();
        System.out.println("+-- " + title.toUpperCase(java.util.Locale.ROOT) + " "
                + "-".repeat(Math.max(0, 42 - title.length())) + "+");
    }

    void printMenuItem(String number, String label) {
        System.out.printf("| %-2s %-42s|%n", number + ".", label);
    }

    String readRequiredText(String prompt) {
        while (true) {
            System.out.print(prompt);
            String value = readLine().trim();
            if (!value.isEmpty()) {
                return value;
            }
            System.out.println("Please enter a value.");
        }
    }

    int readInt(String prompt, int minimum, int maximum) {
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

    BigDecimal readPrice(String prompt) {
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

    String readLine() {
        java.io.Console console = System.console();
        System.out.flush();
        return console == null ? input.nextLine() : console.readLine();
    }

    static final class LegacyEvent {
        final String name;
        final String date;
        final BigDecimal price;
        int remaining;

        LegacyEvent(String name, String date, int capacity, BigDecimal price) {
            this.name = name;
            this.date = date;
            this.price = price;
            this.remaining = capacity;
        }
    }
}
