package drawn;

import drawn.model.user.ContactMethod;
import drawn.model.user.Customer;
import drawn.model.event.Event;
import drawn.model.event.EventDraft;
import drawn.model.event.EventPurpose;
import drawn.model.event.EventType;
import drawn.model.event.EventVisibility;
import drawn.model.event.TicketCategory;
import drawn.model.event.Venue;
import drawn.model.event.VenueCatalog;
import drawn.model.event.ReservationPricing;
import drawn.model.event.VenueReservation;
import drawn.model.event.BookingStatus;
import drawn.model.payment.PaymentStatus;
import drawn.model.payment.PaymentTransaction;
import drawn.model.ticket.TicketType;
import drawn.service.event.ReservationService;
import drawn.service.event.EventPublishingService;
import drawn.service.payment.PaymentService;
import drawn.service.event.EventService;
import drawn.storage.AccountStore;
import drawn.storage.EventRepository;
import drawn.storage.ReservationRepository;
import drawn.storage.PaymentRepository;
import drawn.storage.TicketRepository;

import java.math.BigDecimal;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Scanner;

/** Console entry point for the DrawN event management and ticketing app. */
public final class DrawN {
    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "234";

    private final Scanner input = new Scanner(System.in);
    private final List<LegacyEvent> events = new ArrayList<>();
    private final AccountStore accounts = new AccountStore(Path.of("data"));
    private final EventRepository eventRepository = new EventRepository(Path.of("data"));
    private final ReservationRepository reservationRepository = new ReservationRepository(Path.of("data"));
    private final PaymentRepository paymentRepository = new PaymentRepository(Path.of("data"));
    private final TicketRepository ticketRepository = new TicketRepository(Path.of("data"));
    private final EventService eventService = new EventService(eventRepository);
    private final PaymentService paymentService = new PaymentService(accounts, reservationRepository, paymentRepository);
    private final ReservationService reservationService = new ReservationService(eventRepository, reservationRepository,
            java.time.Clock.systemDefaultZone(), paymentService);
    private final EventPublishingService publishingService = new EventPublishingService(eventRepository,
            reservationRepository, ticketRepository, paymentRepository, accounts);

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
            Customer customer;
            try {
                customer = new Customer(account.getId(), account.getFullName(), account.getContactMethod(),
                        account.getContactValue(), LocalDate.parse(account.getDateOfBirth()));
                System.out.println("Organizer level: " + customer.getOrganizerLevel());
            } catch (IllegalArgumentException ex) {
                System.out.println("Organizer level could not be calculated: " + ex.getMessage());
                return;
            }
            customerMenu(account, customer);
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
            Customer customer = new Customer(id, firstName + " " + lastName, method, contactValue, dateOfBirth);
            accounts.register(account, initialAmount);
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

    private void customerMenu(AccountStore.Account account, Customer customer) {
        boolean inMenu = true;
        while (inMenu) {
            showCustomerDashboard(account, customer);
            System.out.println("\nCUSTOMER MENU");
            System.out.println("1. My Profile");
            System.out.println("2. Create Event");
            System.out.println("3. My Events");
            System.out.println("4. Venue Reservations");
            System.out.println("5. Explore Events");
            System.out.println("6. Buy Tickets");
            System.out.println("7. My Tickets");
            boolean organizerAccess = hasOrganizerAccess(customer);
            if (organizerAccess) System.out.println("8. Organizer Management");
            System.out.println("9. Support Center");
            System.out.println("10. Wallet & Transactions");
            System.out.println("0. Log Out");
            int choice = readInt("Enter your choice: ", 0, 10);

            switch (choice) {
                case 1 -> showProfile(account);
                case 2 -> createEvent(customer);
                case 3 -> showMyEvents(customer);
                case 4 -> venueReservations(account, customer);
                case 5 -> {
                    browsePublishedEvents();
                    listCustomerEvents();
                }
                case 6 -> buyPublishedTicket(account, customer);
                case 7 -> showMyTickets(customer);
                case 8 -> {
                    if (organizerAccess) organizerManagement(customer);
                    else System.out.println("Organizer management is available after approval and full payment.");
                }
                case 9 -> featureUnavailable();
                case 10 -> walletMenu(account);
                case 0 -> inMenu = false;
                default -> throw new IllegalStateException("Unexpected menu option");
            }
        }
    }

    private void showCustomerDashboard(AccountStore.Account account, Customer customer) {
        System.out.println("\n========================================");
        System.out.println("                 DrawN");
        System.out.println("       EVENT ORGANIZING SYSTEM");
        System.out.println("========================================");
        System.out.println("Welcome, " + customer.getName() + "!");
        System.out.println("\nACCOUNT OVERVIEW");
        System.out.printf("Account Balance       : PHP %s%n", account.getWalletBalance().setScale(2).toPlainString());
        try {
            List<Event> ownedEvents = eventService.eventsFor(customer);
            long pending = reservationService.forCustomer(customer).stream()
                    .filter(reservation -> reservation.status() == BookingStatus.PENDING).count();
            System.out.printf("Events Organized      : %d%n", ownedEvents.size());
            System.out.println("Active Tickets        : Not Available");
            System.out.printf("Pending Requests      : %d%n", pending);
        } catch (IOException ex) {
            System.out.println("Events Organized      : Not Available (could not read event submissions)");
            System.out.println("Active Tickets        : Not Available");
            System.out.println("Pending Requests      : Not Available (could not read event submissions)");
        }
        System.out.println("My Earnings           : Not Available");
    }

    private void showProfile(AccountStore.Account account) {
        System.out.println("\nMy Profile");
        System.out.println("Name: " + account.getFullName());
        System.out.println("Date of birth: " + account.getDateOfBirth());
        System.out.println("Email: " + (account.getEmail() == null || account.getEmail().isBlank()
                ? "Not provided" : account.getEmail()));
        System.out.println("Phone: " + (account.getPhone() == null || account.getPhone().isBlank()
                ? "Not provided" : account.getPhone()));
        System.out.println("Account ID: " + account.getId());
    }

    private void showMyEvents(Customer customer) {
        try {
            List<Event> ownEvents = eventService.eventsFor(customer);
            System.out.println("\nMy Events");
            if (ownEvents.isEmpty()) {
                System.out.println("You have not submitted any events.");
                return;
            }
            List<VenueReservation> bookings = reservationService.forCustomer(customer);
            for (Event event : ownEvents) {
                printEventDetails(event);
                VenueReservation booking = bookings.stream().filter(r -> r.eventId().equals(event.id())).findFirst().orElse(null);
                if (booking == null) {
                    System.out.println("  Request status: " + event.approvalStatus());
                } else {
                    System.out.printf("  Request status: %s | Reservation cost: PHP %s | Payment: %s%n", booking.status(),
                            booking.cost().setScale(2).toPlainString(), booking.paymentStatus());
                }
                if (booking != null && booking.status() == BookingStatus.DECLINED) {
                    System.out.println("  Decline reason: " + booking.declineReason());
                }
            }
        } catch (IOException ex) {
            System.out.println("Could not load your events: " + ex.getMessage());
        }
    }

    private void venueReservations(AccountStore.Account account, Customer customer) {
        try {
            List<VenueReservation> mine = reservationService.forCustomer(customer);
            System.out.println("\nVenue reservations");
            if (mine.isEmpty()) System.out.println("No reservation requests found.");
            for (int i = 0; i < mine.size(); i++) {
                VenueReservation r = mine.get(i);
                System.out.printf("Reservation %d%n", i + 1);
                System.out.println("  Venue: " + r.venueName());
                System.out.println("  Date: " + r.date());
                System.out.println("  Start time: " + r.startTime());
                System.out.println("  End time: " + r.endTime());
                System.out.println("  Reservation cost: PHP " + r.cost().toPlainString());
                System.out.println("  Reservation status: " + r.status());
                System.out.println("  Payment status: " + r.paymentStatus());
                if (r.status() == BookingStatus.DECLINED) System.out.println("  Decline reason: " + r.declineReason());
                System.out.println("----------------------------------------");
            }
            if (mine.stream().anyMatch(r -> r.status() == BookingStatus.APPROVED && r.paymentStatus() == PaymentStatus.UNPAID)
                    && readYesNo("Pay an approved reservation now? (y/n): ")) {
                List<VenueReservation> payable = mine.stream().filter(r -> r.status() == BookingStatus.APPROVED
                        && r.paymentStatus() == PaymentStatus.UNPAID).toList();
                for (int i = 0; i < payable.size(); i++) System.out.printf("%d. %s | PHP %s%n", i + 1,
                        payable.get(i).id(), payable.get(i).cost().toPlainString());
                VenueReservation selected = payable.get(readInt("Choose reservation: ", 1, payable.size()) - 1);
                try {
                    paymentService.payVenue(customer, account, selected.id());
                    System.out.println("Full payment completed. Reservation confirmed.");
                } catch (IllegalArgumentException | IOException ex) { System.out.println("Payment failed: " + ex.getMessage()); }
            }
            if (readYesNo("View payment history? (y/n): ")) showPaymentHistory(customer);
        } catch (IOException ex) { System.out.println("Could not load reservations: " + ex.getMessage()); }
    }

    private void showPaymentHistory(Customer customer) {
        try {
            List<PaymentTransaction> history = paymentService.history(customer);
            System.out.println("\nPayment history");
            if (history.isEmpty()) { System.out.println("No payment transactions found."); return; }
            for (PaymentTransaction tx : history) System.out.printf("%s | %s | %s | PHP %s | %s%n",
                    tx.purpose(), tx.referenceId(), tx.status(), tx.amount().toPlainString(), tx.timestamp());
        } catch (IOException ex) { System.out.println("Could not load payment history: " + ex.getMessage()); }
    }

    private void browsePublishedEvents() {
        try {
            List<Event> published = publishingService.publishedEvents();
            System.out.println("\nPublished events");
            if (published.isEmpty()) System.out.println("No published events are available yet.");
            for (int i = 0; i < published.size(); i++) {
                Event event = published.get(i);
                System.out.printf("Event %d%n", i + 1);
                System.out.println("  Name: " + event.name());
                System.out.println("  Type: " + event.type());
                System.out.println("  Date: " + event.date());
                System.out.println("  Start time: " + event.startTime());
                System.out.println("  End time: " + event.endTime());
                System.out.println("  Venue: " + event.venue().name());
                System.out.println("  Description: " + event.description());
                for (TicketType type : publishingService.ticketTypes(event.id()))
                    System.out.printf("  Ticket category: %s%n  Ticket price: PHP %s%n  Tickets available: %d%n  Sales deadline: %s%n",
                            type.name(), type.price().toPlainString(), type.remaining(), type.salesDeadline());
                System.out.println("----------------------------------------");
            }
        } catch (IOException ex) { System.out.println("Could not browse published events: " + ex.getMessage()); }
    }

    private void listCustomerEvents() {
        System.out.println("\nAvailable events");
        boolean found = false;
        for (int i = 0; i < events.size(); i++) {
            LegacyEvent event = events.get(i);
            found = true;
            System.out.printf("Event %d%n", i + 1);
            System.out.println("  Name: " + event.name);
            System.out.println("  Date: " + event.date);
            System.out.println("  Ticket price: PHP " + event.price.toPlainString());
            System.out.println("  Tickets remaining: " + event.remaining);
            System.out.println("----------------------------------------");
        }
        try {
            List<Event> approved = eventService.publicApprovedEvents();
            for (Event event : approved) {
                found = true;
                printEventDetails(event);
            }
        } catch (IOException ex) {
            System.out.println("Could not load approved events: " + ex.getMessage());
        }
        if (!found) System.out.println("No events are currently available.");
    }

    private void buyPublishedTicket(AccountStore.Account account, Customer customer) {
        try {
            List<Event> published = publishingService.publishedEvents();
            if (published.isEmpty()) {
                System.out.println("No published ticketed events are available.");
                if (!events.isEmpty() && readYesNo("Use the existing in-memory event reservation list? (y/n): ")) reserveTickets();
                return;
            }
            if (!events.isEmpty()) {
                System.out.println("1. Published ticketed events\n2. Existing admin-created events");
                if (readInt("Choose a ticket source: ", 1, 2) == 2) { reserveTickets(); return; }
            }
            for (int i = 0; i < published.size(); i++) System.out.printf("%d. %s | %s%n", i + 1,
                    published.get(i).name(), published.get(i).date());
            Event event = published.get(readInt("Choose an event: ", 1, published.size()) - 1);
            List<TicketType> types = publishingService.ticketTypes(event.id());
            for (int i = 0; i < types.size(); i++) System.out.printf("%d. %s | PHP %s | %d left%n", i + 1,
                    types.get(i).name(), types.get(i).price().toPlainString(), types.get(i).remaining());
            TicketType type = types.get(readInt("Choose a ticket category: ", 1, types.size()) - 1);
            int quantity = readInt("Quantity: ", 1, Math.max(1, type.remaining()));
            publishingService.buyTicket(customer, account, event.id(), type.id(), quantity);
            System.out.println("Ticket purchase completed. The charge is recorded in payment history.");
        } catch (IOException | IllegalArgumentException ex) { System.out.println("Ticket purchase failed: " + ex.getMessage()); }
    }

    private void showMyTickets(Customer customer) {
        try {
            List<String> purchases = publishingService.purchaseHistory(customer);
            System.out.println("\nMy tickets");
            if (purchases.isEmpty()) System.out.println("No ticket purchases found.");
            else for (String purchase : purchases) System.out.println(purchase);
        } catch (IOException ex) { System.out.println("Could not load tickets: " + ex.getMessage()); }
    }

    private void organizerManagement(Customer customer) {
        try {
            List<Event> eligible = new ArrayList<>();
            for (Event event : eventService.eventsFor(customer)) {
                if (!event.venue().supports(EventPurpose.TICKETED_EVENT)) continue;
                boolean confirmed = reservationService.forCustomer(customer).stream().anyMatch(r -> r.eventId().equals(event.id())
                        && r.status() == BookingStatus.CONFIRMED && r.paymentStatus() == PaymentStatus.PAID);
                if (confirmed) eligible.add(event);
            }
            if (eligible.isEmpty()) { System.out.println("Organizer tools become available after a reservation is approved and fully paid."); return; }
            for (int i = 0; i < eligible.size(); i++) System.out.printf("%d. %s | %s%n", i + 1,
                    eligible.get(i).name(), eligible.get(i).date());
            Event event = eligible.get(readInt("Choose your event: ", 1, eligible.size()) - 1);
            System.out.println("1. Configure ticket categories\n2. Publish event");
            int action = readInt("Choose an action: ", 1, 2);
            if (action == 1) {
                int count = readInt("Number of ticket categories: ", 1, 20);
                List<TicketType> types = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    String name = readRequiredText("Category name: ");
                    BigDecimal price = readPrice("Ticket price (PHP): ");
                    int quantity = readInt("Available quantity: ", 1, event.venue().capacity());
                    LocalDateTime deadline = readDateTime("Sales deadline (YYYY-MM-DDTHH:mm): ");
                    types.add(new TicketType(java.util.UUID.randomUUID().toString(), event.id(), name, price,
                            quantity, 0, deadline));
                }
                publishingService.configureTickets(customer, event.id(), types);
                System.out.println("Ticket categories saved.");
            } else {
                publishingService.publish(customer, event.id());
                System.out.println("Event published and visible to customers.");
            }
        } catch (IOException | IllegalArgumentException ex) { System.out.println("Organizer action failed: " + ex.getMessage()); }
    }

    private boolean hasOrganizerAccess(Customer customer) {
        try {
            return eventService.eventsFor(customer).stream().filter(event -> event.venue().supports(EventPurpose.TICKETED_EVENT)).anyMatch(event -> {
                try {
                    return reservationService.forCustomer(customer).stream().anyMatch(r -> r.eventId().equals(event.id())
                            && r.status() == BookingStatus.CONFIRMED && r.paymentStatus() == PaymentStatus.PAID);
                } catch (IOException ex) { return false; }
            });
        } catch (IOException ex) { return false; }
    }

    private LocalDateTime readDateTime(String prompt) {
        while (true) {
            System.out.print(prompt);
            try { return LocalDateTime.parse(readLine().trim()); }
            catch (RuntimeException ex) { System.out.println("Enter a valid date and time such as 2026-12-31T18:00."); }
        }
    }

    private void walletMenu(AccountStore.Account account) {
        boolean inMenu = true;
        while (inMenu) {
            System.out.println("\nWallet & Transactions");
            System.out.println("1. View wallet balance");
            System.out.println("2. Add wallet funds");
            System.out.println("3. View transaction history");
            System.out.println("0. Back to dashboard");
            int choice = readInt("Choose an option: ", 0, 3);
            switch (choice) {
                case 1 -> System.out.printf("Wallet balance: PHP %s%n",
                        account.getWalletBalance().setScale(2).toPlainString());
                case 2 -> topUpWallet(account);
                case 3 -> showWalletHistory(account);
                case 0 -> inMenu = false;
                default -> throw new IllegalStateException("Unexpected wallet menu option");
            }
        }
    }

    private void featureUnavailable() {
        System.out.println("Feature not yet available.");
    }

    private void createEvent(Customer customer) {
        if (customer.getAge() < 16) {
            System.out.println("Customers must be at least 16 to submit an event.");
            return;
        }

        System.out.println("\nCreate Event Submission");
        EventPurpose purpose = chooseEventPurpose();
        EventVisibility visibility = EventVisibility.PRIVATE;
        EventType type = chooseEnum("Event type", EventType.values());
        String name = readRequiredText("Event name: ");
        String description = readDescription();
        LocalDate date = readEventDate();
        LocalTime startTime = readTime("Start time (HH:mm): ");
        LocalTime endTime = readTime("End time (HH:mm): ");
        while (!endTime.isAfter(startTime)) {
            System.out.println("End time must be later than start time.");
            endTime = readTime("End time (HH:mm): ");
        }
        int attendance = readInt("Expected attendance: ", 1, Integer.MAX_VALUE);
        Venue venue = chooseVenue(purpose);
        ReservationPricing pricing;
        while (true) {
            pricing = chooseEnum("Reservation pricing", ReservationPricing.values());
            try { ReservationService.validatePricing(startTime, endTime, pricing); break; }
            catch (IllegalArgumentException ex) { System.out.println(ex.getMessage()); }
        }
        List<TicketCategory> ticketCategories = List.of();
        EventDraft draft = new EventDraft(name, description, type, purpose, visibility, date, startTime, endTime,
                attendance, venue, ticketCategories);

        printDraft(draft);
        if (!readYesNo("Submit this event for administrator approval? (y/n): ")) {
            System.out.println("Event submission cancelled. Nothing was saved.");
            return;
        }
        try {
            Event saved = eventService.submit(customer, draft);
            VenueReservation booking = reservationService.request(customer, saved, pricing);
            System.out.printf("Event and venue request submitted. Reservation %s | PHP %s | Status: PENDING.%n",
                    booking.id(), booking.cost().setScale(2).toPlainString());
        } catch (IOException | IllegalArgumentException ex) {
            System.out.println("Event submission failed: " + ex.getMessage());
        }
    }

    private String readDescription() {
        while (true) {
            String value = readRequiredText("Event description (maximum 500 characters): ");
            System.out.printf("Description length: %d/500 characters.%n", value.length());
            if (value.length() <= 500) return value;
            System.out.println("Description is too long. Please shorten it to 500 characters or fewer.");
        }
    }

    private <T extends Enum<T>> T chooseEnum(String label, T[] options) {
        System.out.println("\n" + label + ":");
        for (int i = 0; i < options.length; i++) {
            System.out.printf("%d. %s%n", i + 1, options[i].name().replace('_', ' '));
        }
        return options[readInt("Choose an option: ", 1, options.length) - 1];
    }

    private EventPurpose chooseEventPurpose() {
        System.out.println("\nEvent purpose:");
        System.out.println("1. Social Gathering");
        System.out.println("2. Event Organizing");
        return readInt("Choose an option: ", 1, 2) == 1
                ? EventPurpose.SOCIAL_GATHERING : EventPurpose.TICKETED_EVENT;
    }

    private String displayPurpose(EventPurpose purpose) {
        return purpose == EventPurpose.SOCIAL_GATHERING ? "Social Gathering" : "Event Organizing";
    }

    private LocalDate readEventDate() {
        while (true) {
            System.out.print("Event date (YYYY-MM-DD): ");
            try {
                LocalDate date = LocalDate.parse(readLine().trim());
                if (date.isBefore(LocalDate.now())) {
                    System.out.println("Event date cannot be in the past.");
                } else {
                    return date;
                }
            } catch (RuntimeException ex) {
                System.out.println("Enter a valid date in YYYY-MM-DD format.");
            }
        }
    }

    private LocalTime readTime(String prompt) {
        while (true) {
            System.out.print(prompt);
            try {
                return LocalTime.parse(readLine().trim());
            } catch (RuntimeException ex) {
                System.out.println("Enter a valid time in HH:mm format.");
            }
        }
    }

    private Venue chooseVenue(EventPurpose purpose) {
        List<Venue> compatible = VenueCatalog.all().stream().filter(venue -> venue.supports(purpose)).toList();
        System.out.println("\nAvailable compatible venues:");
        for (int i = 0; i < compatible.size(); i++) {
            Venue venue = compatible.get(i);
            System.out.printf("%d. %s (%s) | capacity %d | PHP %s/hour | half-day PHP %s AM / %s PM%n",
                    i + 1, venue.name(), venue.type(), venue.capacity(), venue.hourlyRate().toPlainString(),
                    venue.morningRate().toPlainString(), venue.afternoonRate().toPlainString());
        }
        return compatible.get(readInt("Choose a venue: ", 1, compatible.size()) - 1);
    }

    private List<TicketCategory> readTicketCategories() {
        int categoryCount = readInt("Number of ticket categories: ", 1, Integer.MAX_VALUE);
        List<TicketCategory> categories = new ArrayList<>();
        while (categories.size() < categoryCount) {
            String name = readRequiredText("Category " + (categories.size() + 1) + " name: ");
            if (categories.stream().anyMatch(category -> category.name().equalsIgnoreCase(name))) {
                System.out.println("Category names must be unique. Enter a different name.");
                continue;
            }
            BigDecimal price = readPrice("Ticket price (PHP): ");
            int quantity = readInt("Available quantity: ", 1, Integer.MAX_VALUE);
            categories.add(new TicketCategory(name, price, quantity));
        }
        return List.copyOf(categories);
    }

    private void printDraft(EventDraft draft) {
        System.out.println("\nReview event submission");
        System.out.println("Event name: " + draft.name());
        System.out.println("Description: " + draft.description());
        System.out.println("Event type: " + draft.type());
        System.out.println("Event purpose: " + displayPurpose(draft.purpose()));
        System.out.println("Visibility: " + draft.visibility());
        System.out.println("Event date: " + draft.date());
        System.out.println("Start time: " + draft.startTime());
        System.out.println("End time: " + draft.endTime());
        System.out.println("Expected attendance: " + draft.expectedAttendance());
        System.out.println("Venue: " + draft.venue().name());
        if (!draft.ticketCategories().isEmpty()) {
            System.out.println("Ticket categories:");
            for (TicketCategory category : draft.ticketCategories()) {
                System.out.printf("  %s | PHP %s | %d available%n", category.name(),
                        category.price().toPlainString(), category.quantity());
            }
        }
    }

    private boolean readYesNo(String prompt) {
        while (true) {
            System.out.print(prompt);
            String value = readLine().trim();
            if (value.equalsIgnoreCase("y") || value.equalsIgnoreCase("yes")) return true;
            if (value.equalsIgnoreCase("n") || value.equalsIgnoreCase("no")) return false;
            System.out.println("Enter y or n.");
        }
    }

    private void printEventDetails(Event event) {
        System.out.println("Event details");
        System.out.println("  Event name: " + event.name());
        System.out.println("  Event type: " + event.type());
        System.out.println("  Event purpose: " + displayPurpose(event.purpose()));
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

    private static final class LegacyEvent {
        private final String name;
        private final String date;
        private final BigDecimal price;
        private int remaining;

        private LegacyEvent(String name, String date, int capacity, BigDecimal price) {
            this.name = name;
            this.date = date;
            this.price = price;
            this.remaining = capacity;
        }
    }
}
