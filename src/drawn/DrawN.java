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
import drawn.model.ticket.TicketType;
import drawn.service.event.ReservationService;
import drawn.service.event.EventPublishingService;
import drawn.service.event.EventCancellationService;
import drawn.service.payment.PaymentService;
import drawn.service.payment.CommissionPolicy;
import drawn.service.payment.OrganizerEarningsService;
import drawn.service.event.EventService;
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
    private final TransactionJournal transactionJournal = new TransactionJournal(Path.of("data"));
    private final PrivateEventAccessRepository privateEventAccess = new PrivateEventAccessRepository(Path.of("data"));
    private final OrganizerEarningsService earningsService = new OrganizerEarningsService(eventRepository, ticketRepository,
            paymentRepository, CommissionPolicy.configured());
    private final EventService eventService = new EventService(eventRepository, reservationRepository, ticketRepository,
            paymentRepository, java.time.Clock.systemDefaultZone());
    private final PaymentService paymentService = new PaymentService(accounts, reservationRepository, paymentRepository,
            transactionJournal, eventRepository);
    private final ReservationService reservationService = new ReservationService(eventRepository, reservationRepository,
            java.time.Clock.systemDefaultZone(), paymentService);
    private final EventPublishingService publishingService = new EventPublishingService(eventRepository,
            reservationRepository, ticketRepository, paymentRepository, accounts, transactionJournal);
    private final EventCancellationService cancellationService = new EventCancellationService(eventRepository,
            reservationRepository, ticketRepository, paymentRepository, accounts, transactionJournal);

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
        printSectionHeader("Initial Wallet Setup");
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
            printSectionHeader("Customer Menu");
            printMenuItem("1", "My Profile");
            printMenuItem("2", "Events & Venues");
            printMenuItem("3", "Tickets");
            printMenuItem("4", "Wallet & Transactions");
            printMenuItem("0", "Log Out");
            int choice = readInt("Enter your choice: ", 0, 4);

            switch (choice) {
                case 1 -> showProfile(account);
                case 2 -> eventsAndVenuesMenu(account, customer);
                case 3 -> ticketsMenu(account, customer);
                case 4 -> walletMenu(account, customer);
                case 0 -> inMenu = false;
                default -> throw new IllegalStateException("Unexpected menu option");
            }
            if (choice != 0) waitForDashboard();
        }
    }

    private void ticketsMenu(AccountStore.Account account, Customer customer) {
        boolean inHub = true;
        while (inHub) {
            printSectionHeader("Tickets");
            showMyTickets(customer);
            printMenuItem("1", "Buy Tickets");
            printMenuItem("2", "Cancel a Ticket Purchase");
            printMenuItem("0", "Back to Customer Menu");
            switch (readInt("Choose an option: ", 0, 2)) {
                case 1 -> buyPublishedTicket(account, customer);
                case 2 -> cancelTicketPurchase(account, customer);
                case 0 -> inHub = false;
                default -> throw new IllegalStateException("Unexpected Tickets option");
            }
        }
    }

    private void eventsAndVenuesMenu(AccountStore.Account account, Customer customer) {
        boolean inHub = true;
        while (inHub) {
            printSectionHeader("Plan an Event");
            printMenuItem("1", "My Event Plans & Venue Requests");
            printMenuItem("2", "Create & Publish Event (Paid Venue)");
            printMenuItem("3", "Manage Venue Requests");
            printMenuItem("4", "Explore Events");
            printMenuItem("0", "Back to Customer Menu");
            switch (readInt("Choose an option: ", 0, 4)) {
                case 1 -> showMyEvents(customer);
                case 2 -> createEvent(account, customer);
                case 3 -> venueReservations(account, customer);
                case 4 -> exploreEvents(account, customer);
                case 0 -> inHub = false;
                default -> throw new IllegalStateException("Unexpected event planning option");
            }
        }
    }

    private void showCustomerDashboard(AccountStore.Account account, Customer customer) {
        printBrandBanner();
        System.out.println("Welcome, " + customer.getName() + "!");
        printSectionHeader("Account Overview");
        System.out.printf("Account Balance       : PHP %s%n", account.getWalletBalance().setScale(2).toPlainString());
        try {
            List<Event> ownedEvents = eventService.eventsFor(customer);
            List<VenueReservation> myReservations=reservationService.forCustomer(customer);
            long pending = myReservations.stream()
                    .filter(reservation -> reservation.status() == BookingStatus.PENDING).count();
            System.out.printf("Events Organized      : %d%n", ownedEvents.size());
            System.out.printf("Active Tickets        : %d%n",publishingService.activeTicketCount(customer));
            System.out.printf("Venue Requests Awaiting Approval : %d%n", pending);
        } catch (IOException ex) {
            System.out.println("Events Organized      : Not Available (could not read event submissions)");
            System.out.println("Active Tickets        : 0");
            System.out.println("Venue Requests Awaiting Approval : 0");
        }
        try {
            var earnings = earningsService.forOrganizer(customer);
            System.out.printf("Gross Ticket Revenue  : PHP %s%n", earnings.grossTicketRevenue().toPlainString());
            System.out.printf("Platform Commission   : PHP %s (%s%%)%n", earnings.platformCommission().toPlainString(),
                    CommissionPolicy.configured().percent().toPlainString());
            System.out.printf("Organizer Net Earnings: PHP %s%n", earnings.netEarnings().toPlainString());
            System.out.printf("Tickets Sold          : %d%n", earnings.ticketsSold());
        } catch (IOException | IllegalArgumentException ex) {
            System.out.println("Organizer earnings    : Not Available (" + ex.getMessage() + ")");
        }
    }

    private void showProfile(AccountStore.Account account) {
        System.out.println("\nMy Profile");
        System.out.println("Name: " + account.getFullName());
        System.out.println("Date of birth: " + account.getDateOfBirth());
        System.out.println("Email: " + (account.getEmail() == null || account.getEmail().isBlank()
                ? "Not provided" : account.getEmail()));
        System.out.println("Phone: " + (account.getPhone() == null || account.getPhone().isBlank()
                ? "Not provided" : account.getPhone()));
        System.out.println("Username: " + (account.getEmail() == null || account.getEmail().isBlank()
                ? account.getPhone() : account.getEmail()));
    }

    private void showMyEvents(Customer customer) {
        try {
            List<Event> ownEvents = eventService.eventsFor(customer).stream()
                    .filter(event -> event.approvalStatus() != drawn.model.event.EventApprovalStatus.CANCELLED).toList();
            System.out.println("\nMy Event Plans");
            List<VenueReservation> bookings = reservationService.forCustomer(customer);
            List<VenueReservation> unlinkedReservations = bookings.stream().filter(reservation -> reservation.eventId().isBlank()).toList();
            if (ownEvents.isEmpty()) System.out.println("No active events found.");
            else {
                for (int i = 0; i < ownEvents.size(); i++) System.out.printf("%d. %s%n", i + 1, ownEvents.get(i).name());
                int eventChoice = readInt("Choose an event (0 to go back): ", 0, ownEvents.size());
                if (eventChoice == 0) return;
                Event event = ownEvents.get(eventChoice - 1);
                VenueReservation booking = bookings.stream().filter(reservation -> reservation.eventId().equals(event.id())).findFirst().orElse(null);
                System.out.println();
                printEventDetails(event);
                System.out.println("  Event status: " + event.approvalStatus());
                if (booking != null) System.out.printf("  Venue booking: %s | PHP %s%n", reservationStatusLabel(booking),
                        booking.cost().setScale(2).toPlainString());
                System.out.println("\n1. Cancel event\n0. Back");
                if (readInt("Choose an action: ", 0, 1) == 1) {
                    cancellationService.cancel(customer, event.id());
                    System.out.println("Event and linked reservation cancelled; paid tickets and reservations were refunded.");
                }
                return;
            }
            if (!unlinkedReservations.isEmpty()) {
                System.out.println("\nVenue requests not yet linked to an event");
                for (VenueReservation reservation : unlinkedReservations) {
                    System.out.printf("%s | %s %s-%s | PHP %s | %s%n", reservation.venueName(),
                            reservation.date(), reservation.startTime(), reservation.endTime(),
                            reservation.cost().setScale(2).toPlainString(), reservationStatusLabel(reservation));
                }
            }
        } catch (IOException ex) {
            System.out.println("Could not load your events: " + ex.getMessage());
        } catch (IllegalArgumentException | IllegalStateException ex) {
            System.out.println("Event action failed: " + ex.getMessage());
        }
    }

    private void venueReservations(AccountStore.Account account, Customer customer) {
        try {
            System.out.println("\nVenue reservations\n1. View reservations\n2. Request another venue");
            int reservationAction = readInt("Choose an option: ",1,2);
            if (reservationAction == 2) { requestVenueReservation(account, customer); return; }
            List<VenueReservation> mine = reservationService.forCustomer(customer);
            List<VenueReservation> activeReservations = mine.stream().filter(reservation -> reservation.status() != BookingStatus.CANCELLED
                    && reservation.status() != BookingStatus.DECLINED && reservation.status() != BookingStatus.EXPIRED).toList();
            System.out.println("\nActive venue requests");
            if (activeReservations.isEmpty()) { System.out.println("No active venue requests found."); return; }
            for (int i = 0; i < activeReservations.size(); i++) System.out.printf("%d. %s%n", i + 1, activeReservations.get(i).venueName());
            int selectedIndex = readInt("Choose a venue request (0 to go back): ", 0, activeReservations.size());
            if (selectedIndex == 0) return;
            VenueReservation selected = activeReservations.get(selectedIndex - 1);
            System.out.println("\nVenue: " + selected.venueName());
            System.out.println("Date: " + selected.date());
            System.out.println("Time: " + selected.startTime() + "-" + selected.endTime());
            System.out.println("Reservation cost: PHP " + selected.cost().setScale(2).toPlainString());
            System.out.println("Pricing: " + reservationCostExplanation(selected));
            System.out.println("Reservation status: " + reservationStatusLabel(selected));
            System.out.println("\n1. Cancel reservation\n0. Back");
            if (readInt("Choose an action: ", 0, 1) == 1) {
                try {
                    VenueReservation cancelled = paymentService.cancelReservation(customer, account, selected.id());
                    System.out.println("Reservation cancelled. Refund status: " + cancelled.paymentStatus());
                } catch (IllegalArgumentException | IOException ex) { System.out.println("Reservation cancellation failed: " + ex.getMessage()); }
            }
        } catch (IOException ex) { System.out.println("Could not load reservations: " + ex.getMessage()); }
    }

    private String reservationCostExplanation(VenueReservation reservation) {
        Venue venue=VenueCatalog.all().stream().filter(v->v.name().equals(reservation.venueName())).findFirst().orElse(null);
        if(venue==null) return reservation.pricing()+" rate; stored total PHP "+reservation.cost().setScale(2).toPlainString();
        return switch(reservation.pricing()) {
            case HALF_DAY_MORNING -> "fixed morning block PHP "+venue.morningRate().setScale(2).toPlainString()+" (not based on guest count or minutes)";
            case HALF_DAY_AFTERNOON -> "fixed afternoon block PHP "+venue.afternoonRate().setScale(2).toPlainString()+" (not based on guest count or minutes)";
            case HOURLY -> {
                long minutes=java.time.Duration.between(reservation.startTime(),reservation.endTime()).toMinutes();
                BigDecimal hours=BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60),4,java.math.RoundingMode.HALF_UP);
                yield venue.hourlyRate().toPlainString()+"/hour × "+hours.toPlainString()+" hours";
            }
        };
    }

    private String reservationStatusLabel(VenueReservation reservation) {
        return reservation.status().name();
    }

    private void requestVenueReservation(AccountStore.Account account, Customer customer) {
        List<Venue> venues=VenueCatalog.all();
        for(int i=0;i<venues.size();i++) System.out.printf("%d. %s | %s | capacity %d | PHP %s/hour%n",i+1,
                venues.get(i).name(),venues.get(i).type(),venues.get(i).capacity(),venues.get(i).hourlyRate());
        Venue venue=venues.get(readInt("Choose venue: ",1,venues.size())-1);
        LocalDate date=readEventDate();
        LocalTime start=readTime("Start time (HH:mm): ");
        LocalTime end=readTime("End time (HH:mm): ");
        ReservationPricing pricing=chooseEnum("Reservation pricing",ReservationPricing.values());
        try {
            VenueReservation reservation=reservationService.request(customer,venue,date,start,end,pricing);
            if (account.getWalletBalance().compareTo(reservation.cost()) < 0) {
                reservationRepository.delete(reservation.id());
                throw new IllegalArgumentException("Insufficient wallet balance for this venue reservation.");
            }
            VenueReservation approved = reservationService.approve(reservation.id());
            VenueReservation confirmed = paymentService.payVenue(customer, account, approved.id());
            System.out.printf("Reservation confirmed for %s. PHP %s was paid from your wallet.%n", venue.name(),
                    confirmed.cost().setScale(2).toPlainString());
        } catch(IOException|IllegalArgumentException ex) { System.out.println("Reservation request failed: "+ex.getMessage()); }
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
        List<Event> approved = publishingService.publishedEvents();
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
            printBuyerEventDetails(event);
            buyTicketForEvent(account, customer, event);
        } catch (IOException | IllegalArgumentException ex) { System.out.println("Ticket purchase failed: " + ex.getMessage()); }
    }

    private void exploreEvents(AccountStore.Account account, Customer customer) {
        try {
            System.out.println("\nExplore events");
            System.out.println("1. Public events\n2. Private event access");
            int source = readInt("Choose an option: ", 1, 2);
            List<Event> published;
            if (source == 1) {
                published = publishingService.publishedEvents();
            } else {
                String keyword = readRequiredText("Private event access keyword: ");
                published = eventRepository.findAll().stream().filter(event -> event.approvalStatus()
                        == drawn.model.event.EventApprovalStatus.PUBLISHED && event.visibility() == EventVisibility.PRIVATE)
                        .filter(event -> {
                            try { return privateEventAccess.matches(event.id(), keyword); }
                            catch (IOException ex) { throw new IllegalStateException(ex); }
                        }).toList();
            }
            if (published.isEmpty()) { System.out.println("No published events are available."); return; }
            for (int i = 0; i < published.size(); i++) System.out.printf("%d. %s%n", i + 1, published.get(i).name());
            int choice = readInt("Choose an event (0 to go back): ", 0, published.size());
            if (choice == 0) return;
            Event event = published.get(choice - 1);
            printBuyerEventDetails(event);
            if (event.purpose() == EventPurpose.TICKETED_EVENT) {
                System.out.println("1. Buy tickets\n0. Back");
                if (readInt("Choose an action: ", 0, 1) == 1) buyTicketForEvent(account, customer, event);
            }
        } catch (IOException | IllegalArgumentException ex) {
            System.out.println("Could not explore events: " + ex.getMessage());
        }
    }

    private void buyTicketForEvent(AccountStore.Account account, Customer customer, Event event) throws IOException {
        List<TicketType> types = publishingService.ticketTypes(event.id());
        if (types.isEmpty()) { System.out.println("Tickets are not available for this event."); return; }
        for (int i = 0; i < types.size(); i++) System.out.printf("%d. %s | PHP %s | %d left | Sales end: %s%n", i + 1,
                types.get(i).name(), types.get(i).price().toPlainString(), types.get(i).remaining(), types.get(i).salesDeadline());
        TicketType type = types.get(readInt("Choose a ticket category: ", 1, types.size()) - 1);
        int quantity = readInt("Quantity: ", 1, Math.max(1, type.remaining()));
        BigDecimal total=type.price().multiply(BigDecimal.valueOf(quantity));
        System.out.printf("Purchase summary: %s x %d | Total PHP %s%n",type.name(),quantity,total.setScale(2).toPlainString());
        if(!readYesNo("Confirm ticket purchase? (y/n): ")) { System.out.println("Purchase cancelled."); return; }
        publishingService.buyTicket(customer, account, event.id(), type.id(), quantity);
        System.out.println("Ticket purchase completed. The charge is recorded in Wallet & Transactions.");
    }

    private void showMyTickets(Customer customer) {
        try {
            List<TicketRepository.Purchase> purchases = publishingService.purchases(customer);
            System.out.println("My tickets");
            if (purchases.isEmpty()) System.out.println("No ticket purchases found.");
            else for (int i = 0; i < purchases.size(); i++) {
                TicketRepository.Purchase purchase = purchases.get(i);
                try {
                    Event event=publishingService.event(purchase.eventId());
                    String category=publishingService.ticketTypes(purchase.eventId()).stream().filter(t->t.id().equals(purchase.ticketTypeId()))
                            .map(TicketType::name).findFirst().orElse("Ticket");
                    System.out.printf("%d. %s | %s | %d ticket(s) | PHP %s | %s%n", i + 1, event.name(), category,
                            purchase.quantity(), purchase.amount().setScale(2).toPlainString(), purchase.status());
                } catch(RuntimeException ex) { System.out.println("Ticket purchase record could not be displayed."); }
            }
        } catch (IOException ex) { System.out.println("Could not load tickets: " + ex.getMessage()); }
    }

    private void cancelTicketPurchase(AccountStore.Account account, Customer customer) {
        try {
            List<TicketRepository.Purchase> active = publishingService.purchases(customer).stream()
                    .filter(purchase -> purchase.status() == drawn.model.ticket.TicketPurchaseStatus.ACTIVE).toList();
            if (active.isEmpty()) { System.out.println("No active ticket purchases are available to cancel."); return; }
            System.out.println("\nActive ticket purchases");
            for (int i = 0; i < active.size(); i++) {
                TicketRepository.Purchase purchase = active.get(i);
                Event event = publishingService.event(purchase.eventId());
                System.out.printf("%d. %s | %d ticket(s) | PHP %s%n", i + 1, event.name(), purchase.quantity(),
                        purchase.amount().setScale(2).toPlainString());
            }
            TicketRepository.Purchase selected = active.get(readInt("Choose a purchase to cancel: ", 1, active.size()) - 1);
            EventPublishingService.TicketCancellationQuote quote = publishingService.cancellationQuote(customer, selected.id());
            System.out.printf("Cancellation summary: refund PHP %s | fee PHP %s (organizer PHP %s, admin PHP %s)%n",
                    quote.refundAmount().toPlainString(), quote.cancellationFee().toPlainString(),
                    quote.organizerFee().toPlainString(), quote.adminFee().toPlainString());
            if (!readYesNo("Confirm ticket cancellation? (y/n): ")) { System.out.println("Ticket cancellation cancelled."); return; }
            publishingService.cancelTicket(customer, account, selected.id());
            System.out.println("Ticket purchase cancelled. Your refund has been added to your wallet.");
        } catch (IOException | IllegalArgumentException ex) {
            System.out.println("Ticket cancellation failed: " + ex.getMessage());
        }
    }

    private void walletMenu(AccountStore.Account account, Customer customer) {
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
                case 3 -> showWalletHistory(account, customer);
                case 0 -> inMenu = false;
                default -> throw new IllegalStateException("Unexpected wallet menu option");
            }
        }
    }

    private void createEvent(AccountStore.Account account, Customer customer) {
        if (customer.getAge() < 16) {
            System.out.println("Customers must be at least 16 to submit an event.");
            return;
        }

        try {
            List<VenueReservation> eligible = reservationService.forCustomer(customer).stream()
                    .filter(r -> (r.status() == BookingStatus.RESERVED || r.status() == BookingStatus.CONFIRMED)
                            && r.paymentStatus() == PaymentStatus.PAID
                            && r.eventId().isBlank()).toList();
            System.out.println("\nCreate & Publish Event from a Paid Venue Booking");
            if (eligible.isEmpty()) {
                System.out.println("No paid venue booking is ready for event details yet.");
                System.out.println("First request a venue, wait for approval, and pay it from Manage Venue Requests.");
                if (readYesNo("Request another venue now? (y/n): ")) requestVenueReservation(account, customer);
                return;
            }
            for (int i=0;i<eligible.size();i++) {
                VenueReservation r=eligible.get(i);
                System.out.printf("%d. %s | %s %s-%s | %s%n", i+1,r.venueName(),r.date(),r.startTime(),r.endTime(),r.status());
            }
            int reservationChoice=readInt("Choose reservation (0 to cancel): ",0,eligible.size());
            if(reservationChoice==0) return;
            VenueReservation reservation=eligible.get(reservationChoice-1);
            EventType type=chooseEnum("Event category",EventType.values());
            EventPurpose purpose=chooseEventPurpose();
            Venue venue=VenueCatalog.all().stream().filter(v->v.name().equals(reservation.venueName())).findFirst()
                    .orElseThrow(()->new IllegalArgumentException("Reserved venue details are unavailable."));
            if(!venue.supports(purpose)) throw new IllegalArgumentException("Selected reservation venue does not support this event purpose.");
            String name=readRequiredText("Event name: ");
            String description=readDescription();
            int attendance=readInt("Expected attendance (venue capacity "+venue.capacity()+"): ",1,venue.capacity());
            EventVisibility visibility=readInt("Visibility: 1. Public  2. Private: ",1,2)==1?EventVisibility.PUBLIC:EventVisibility.PRIVATE;
            String privateKeyword = visibility == EventVisibility.PRIVATE
                    ? readRequiredText("Private event access keyword: ") : "";
            List<TicketCategory> categories = purpose == EventPurpose.TICKETED_EVENT ? readTicketCategories() : List.of();
            int ticketQuantity = categories.stream().mapToInt(TicketCategory::quantity).sum();
            if (ticketQuantity > venue.capacity()) throw new IllegalArgumentException("Total ticket quantity exceeds venue capacity.");
            EventDraft draft=new EventDraft(name,description,type,purpose,visibility,reservation.date(),reservation.startTime(),
                    reservation.endTime(),attendance,venue,categories);
            printDraft(draft);
            if(!readYesNo("Create and publish this event now? (y/n): ")) return;
            Event saved=eventService.saveDraftForReservation(customer,draft,reservation.id());
            reservationService.attachEvent(customer,reservation.id(),saved.id());
            if (visibility == EventVisibility.PRIVATE) privateEventAccess.save(saved.id(), privateKeyword);
            if (purpose == EventPurpose.TICKETED_EVENT) {
                LocalDateTime salesStart = LocalDateTime.now();
                LocalDateTime salesDeadline = reservation.date().atTime(reservation.startTime()).minusMinutes(1);
                List<TicketType> types = categories.stream().map(category -> new TicketType(java.util.UUID.randomUUID().toString(),
                        saved.id(), category.name(), category.name() + " ticket", category.price(), category.quantity(), 0,
                        salesStart, salesDeadline)).toList();
                publishingService.configureTickets(customer, saved.id(), types);
            }
            eventService.publish(customer, saved.id());
            if (purpose == EventPurpose.TICKETED_EVENT) publishingService.publish(customer, saved.id());
            System.out.println("Event published.");
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
        System.out.println("2. Commercial / Ticketed Event");
        return readInt("Choose an option: ", 1, 2) == 1
                ? EventPurpose.SOCIAL_GATHERING : EventPurpose.TICKETED_EVENT;
    }

    private String displayPurpose(EventPurpose purpose) {
        return purpose == EventPurpose.SOCIAL_GATHERING ? "Social Gathering" : "Commercial / Ticketed Event";
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
                    i + 1, venue.name(), VenueCatalog.categoryOf(venue), venue.capacity(), venue.hourlyRate().toPlainString(),
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

    private void printBuyerEventDetails(Event event) {
        System.out.println("\nEvent details");
        System.out.println("  Name: " + event.name());
        System.out.println("  Type: " + event.type());
        System.out.println("  Date: " + event.date());
        System.out.println("  Time: " + event.startTime() + " - " + event.endTime());
        System.out.println("  Venue: " + event.venue().name());
        System.out.println("  Description: " + event.description());
        System.out.println("----------------------------------------");
    }

    private void topUpWallet(AccountStore.Account account) {
        BigDecimal amount = readTopUp();
        if (amount.signum()<=0 || amount.compareTo(new BigDecimal("100000.00"))>0) {
            System.out.println("Simulated top-up must be greater than PHP 0.00 and no more than PHP 100,000.00."); return;
        }
        System.out.printf("SIMULATED TOP-UP: PHP %s | Current balance: PHP %s%n",amount.setScale(2).toPlainString(),
                account.getWalletBalance().setScale(2).toPlainString());
        if(!readYesNo("Confirm simulated top-up? (y/n): ")) { System.out.println("Top-up cancelled."); return; }
        try {
            accounts.topUp(account, amount);
            System.out.printf("Top-up successful. New wallet balance: PHP %s%n",
                    account.getWalletBalance().setScale(2).toPlainString());
        } catch (IOException | IllegalArgumentException ex) {
            System.out.println("Could not save wallet top-up: " + ex.getMessage());
        }
    }

    private void showWalletHistory(AccountStore.Account account, Customer customer) {
        try {
            List<AccountStore.Transaction> transactions = accounts.loadTransactions(account.getId());
            System.out.println("\nWallet & event transactions");
            if (transactions.isEmpty()) {
                System.out.println("No wallet transactions found.");
                return;
            }
            List<AccountStore.Transaction> funding = transactions.stream()
                    .filter(transaction -> transaction.type().equals("TOP_UP") || transaction.type().equals("INITIAL_FUNDING"))
                    .toList();
            List<AccountStore.Transaction> eventTransactions = transactions.stream().filter(transaction -> !funding.contains(transaction)).toList();
            System.out.println("\nWallet funding");
            if (funding.isEmpty()) System.out.println("No top-ups or initial funding found.");
            for (AccountStore.Transaction transaction : funding) printTransaction(transaction, transaction.type().equals("TOP_UP") ? "Wallet top-up" : "Initial wallet funding", "Funds added to your wallet.");
            System.out.println("\nEvent transactions");
            if (eventTransactions.isEmpty()) System.out.println("No event-related payments, refunds, or fees found.");
            for (AccountStore.Transaction transaction : eventTransactions)
                printTransaction(transaction, transactionLabel(transaction.type()), transactionDetails(transaction));
            showEventAndVenueRecords(customer);
        } catch (IOException ex) {
            System.out.println("Could not read wallet history: " + ex.getMessage());
        }
    }

    private void showEventAndVenueRecords(Customer customer) throws IOException {
        List<Event> events = eventService.eventsFor(customer);
        List<VenueReservation> reservations = reservationService.forCustomer(customer);
        System.out.println("\nEvent & venue records");
        System.out.println("Events");
        if (events.isEmpty()) System.out.println("No event records found.");
        for (Event event : events) System.out.printf("%s | %s | %s | %s%n", event.name(), event.date(),
                event.venue().name(), event.approvalStatus());
        System.out.println("Venue reservations");
        if (reservations.isEmpty()) System.out.println("No venue reservation records found.");
        for (VenueReservation reservation : reservations) System.out.printf("%s | %s %s-%s | PHP %s | %s%n",
                reservation.venueName(), reservation.date(), reservation.startTime(), reservation.endTime(),
                reservation.cost().setScale(2).toPlainString(), reservationStatusLabel(reservation));
    }

    private void printTransaction(AccountStore.Transaction transaction, String label, String details) {
        System.out.printf("%s%n  %s%n  Amount: PHP %s | Balance: PHP %s | %s%n", label, details,
                transaction.amount().setScale(2).toPlainString(), transaction.balanceAfter().setScale(2).toPlainString(),
                transaction.timestamp());
    }

    private String transactionLabel(String type) {
        if (type.startsWith("PAYMENT:ticket:")) return "Ticket purchase";
        if (type.startsWith("REFUND:ticket-cancellation:")) return "Ticket cancellation refund";
        if (type.startsWith("REFUND:ticket-refund:")) return "Event cancellation ticket refund";
        if (type.startsWith("CREDIT:ticket-cancellation-fee:")) return "Ticket cancellation fee received";
        if (type.startsWith("PAYMENT:venue:")) return "Venue booking payment";
        if (type.startsWith("REFUND:venue:")) return "Venue booking refund";
        return type;
    }

    private String transactionDetails(AccountStore.Transaction transaction) throws IOException {
        String type = transaction.type();
        String purchaseId = null;
        String action = null;
        if (type.startsWith("PAYMENT:ticket:")) { purchaseId = type.substring("PAYMENT:ticket:".length()); action = "Purchase"; }
        else if (type.startsWith("REFUND:ticket-cancellation:")) { purchaseId = type.substring("REFUND:ticket-cancellation:".length()); action = "Customer cancellation"; }
        else if (type.startsWith("REFUND:ticket-refund:")) { purchaseId = type.substring("REFUND:ticket-refund:".length()); action = "Event cancellation"; }
        else if (type.startsWith("CREDIT:ticket-cancellation-fee:")) { purchaseId = type.substring("CREDIT:ticket-cancellation-fee:".length()); action = "Organizer share of cancellation fee"; }
        if (purchaseId != null) {
            String ticketPurchaseId = purchaseId;
            TicketRepository.Purchase purchase = ticketRepository.allPurchaseRecords().stream()
                    .filter(candidate -> candidate.id().equals(ticketPurchaseId)).findFirst().orElse(null);
            if (purchase == null) return action + " for a ticket purchase.";
            Event event = eventRepository.findAll().stream().filter(candidate -> candidate.id().equals(purchase.eventId()))
                    .findFirst().orElse(null);
            String category = ticketRepository.types(purchase.eventId()).stream()
                    .filter(candidate -> candidate.id().equals(purchase.ticketTypeId())).map(TicketType::name)
                    .findFirst().orElse("Ticket");
            return action + " — " + (event == null ? "Event" : event.name()) + " | " + category + " | "
                    + purchase.quantity() + " ticket(s).";
        }
        String reservationId = null;
        if (type.startsWith("PAYMENT:venue:")) reservationId = type.substring("PAYMENT:venue:".length());
        else if (type.startsWith("REFUND:venue:")) reservationId = type.substring("REFUND:venue:".length());
        if (reservationId != null) {
            String venueReservationId = reservationId;
            VenueReservation reservation = reservationRepository.findAll().stream()
                    .filter(candidate -> candidate.id().equals(venueReservationId)).findFirst().orElse(null);
            if (reservation == null) return "Venue reservation transaction.";
            return reservation.venueName() + " | " + reservation.date() + " | " + reservation.startTime()
                    + "-" + reservation.endTime() + ".";
        }
        return "Event-related wallet transaction.";
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

    private void waitForDashboard() {
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

    private void printBrandBanner() {
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

    private void printSectionHeader(String title) {
        System.out.println();
        System.out.println("+-- " + title.toUpperCase(java.util.Locale.ROOT) + " "
                + "-".repeat(Math.max(0, 42 - title.length())) + "+");
    }

    private void printMenuItem(String number, String label) {
        System.out.printf("| %-2s %-42s|%n", number + ".", label);
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
