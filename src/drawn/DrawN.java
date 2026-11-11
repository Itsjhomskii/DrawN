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
            System.out.println("9. Wallet & Transactions");
            System.out.println("0. Log Out");
            int choice = readInt("Enter your choice: ", 0, 9);

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
                case 9 -> walletMenu(account);
                case 0 -> inMenu = false;
                default -> throw new IllegalStateException("Unexpected menu option");
            }
            if (choice != 0) waitForDashboard();
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
            List<VenueReservation> myReservations=reservationService.forCustomer(customer);
            long pending = myReservations.stream()
                    .filter(reservation -> reservation.status() == BookingStatus.PENDING).count();
            System.out.printf("Events Organized      : %d%n", ownedEvents.size());
            System.out.printf("Active Tickets        : %d%n",publishingService.activeTicketCount(customer));
            System.out.printf("Pending Reservations  : %d%n", pending);
            System.out.printf("Pending Event Reviews : %d%n",ownedEvents.stream().filter(e->e.approvalStatus()==drawn.model.event.EventApprovalStatus.PENDING).count());
        } catch (IOException ex) {
            System.out.println("Events Organized      : Not Available (could not read event submissions)");
            System.out.println("Active Tickets        : 0");
            System.out.println("Pending Reservations  : 0");
            System.out.println("Pending Event Reviews : 0");
        }
        try {
            var earnings = earningsService.forOrganizer(customer);
            System.out.printf("Gross Ticket Revenue  : PHP %s%n", earnings.grossTicketRevenue().toPlainString());
            System.out.printf("Platform Commission   : PHP %s (%s%%)%n", earnings.platformCommission().toPlainString(),
                    CommissionPolicy.configured().percent().toPlainString());
            System.out.printf("Organizer Net Earnings: PHP %s%n", earnings.netEarnings().toPlainString());
            System.out.printf("Tickets Sold          : %d%n", earnings.ticketsSold());
            System.out.printf("Verified Transactions : %d%n", earnings.verifiedTransactions());
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
            List<Event> ownEvents = eventService.eventsFor(customer);
            System.out.println("\nMy Events");
            if (ownEvents.isEmpty()) { System.out.println("You have not submitted any events."); return; }
            List<VenueReservation> bookings = reservationService.forCustomer(customer);
            for (int i = 0; i < ownEvents.size(); i++) {
                Event event = ownEvents.get(i); System.out.printf("\nEvent %d%n", i + 1); printEventDetails(event);
                VenueReservation booking = bookings.stream().filter(r -> r.eventId().equals(event.id())).findFirst().orElse(null);
                System.out.println("  Status: " + event.approvalStatus());
                if (booking != null) System.out.printf("  Reservation: %s | PHP %s | Payment: %s%n", booking.status(),
                        booking.cost().setScale(2).toPlainString(), booking.paymentStatus());
                if (booking != null && booking.status() == BookingStatus.DECLINED)
                    System.out.println("  Reservation decline reason: " + booking.declineReason());
            }
            System.out.println("\n1. Edit draft or rejected event\n2. Delete draft\n3. Submit draft or resubmit rejected event"
                    + "\n4. Cancel event\n5. Publish approved event\n0. Back");
            int action = readInt("Choose an action: ", 0, 5);
            if (action == 0) return;
            Event selected = ownEvents.get(readInt("Choose event: ", 1, ownEvents.size()) - 1);
            switch (action) {
                case 1 -> editCustomerEvent(customer, selected);
                case 2 -> { eventService.deleteDraft(customer, selected.id()); System.out.println("Draft deleted."); }
                case 3 -> { eventService.submitDraft(customer, selected.id()); System.out.println("Event submitted for review."); }
                case 4 -> { cancellationService.cancel(customer, selected.id()); System.out.println("Event cancelled and paid tickets refunded."); }
                case 5 -> {
                    if (selected.purpose() == EventPurpose.TICKETED_EVENT) publishingService.publish(customer, selected.id());
                    else eventService.publish(customer, selected.id());
                    System.out.println("Event published.");
                }
                default -> throw new IllegalStateException("Unexpected event action.");
            }
        } catch (IOException ex) {
            System.out.println("Could not load your events: " + ex.getMessage());
        } catch (IllegalArgumentException | IllegalStateException ex) {
            System.out.println("Event action failed: " + ex.getMessage());
        }
    }

    private void editCustomerEvent(Customer customer, Event event) throws IOException {
        String name = readRequiredText("Event name: ");
        String description = readDescription();
        EventType type = chooseEnum("Event category", EventType.values());
        EventPurpose purpose = chooseEventPurpose();
        EventVisibility visibility = readInt("Visibility: 1. Public  2. Private: ", 1, 2) == 1
                ? EventVisibility.PUBLIC : EventVisibility.PRIVATE;
        int attendance = readInt("Expected attendance (venue capacity " + event.venue().capacity() + "): ",
                1, event.venue().capacity());
        List<TicketCategory> categories = purpose == EventPurpose.TICKETED_EVENT ? readTicketCategories() : List.of();
        EventDraft draft = new EventDraft(name, description, type, purpose, visibility, event.date(), event.startTime(),
                event.endTime(), attendance, event.venue(), categories);
        eventService.editDraft(customer, event.id(), draft);
        System.out.println("Event changes saved. Event ID and reservation link were preserved.");
    }

    private void venueReservations(AccountStore.Account account, Customer customer) {
        try {
            System.out.println("\nVenue reservations\n1. View reservations and payments\n2. Request a venue reservation\n3. Cancel a reservation");
            int reservationAction = readInt("Choose an option: ",1,3);
            if (reservationAction == 2) { requestVenueReservation(customer); return; }
            List<VenueReservation> mine = reservationService.forCustomer(customer);
            if (reservationAction == 3) {
                if (mine.isEmpty()) { System.out.println("No reservations are available to cancel."); return; }
                for (int i = 0; i < mine.size(); i++) {
                    VenueReservation r = mine.get(i);
                    System.out.printf("%d. %s | %s | %s | %s%n", i + 1, r.venueName(), r.date(), r.status(), r.paymentStatus());
                }
                while (true) {
                    try {
                        int selectedIndex = readInt("Choose reservation (0 to go back): ", 0, mine.size());
                        if (selectedIndex == 0) return;
                        VenueReservation selected = mine.get(selectedIndex - 1);
                        if (!readYesNo("Cancel this reservation? A paid reservation will be refunded to your wallet. (y/n): ")) return;
                        VenueReservation cancelled = paymentService.cancelReservation(customer, account, selected.id());
                        System.out.println("Reservation cancelled. Refund status: " + cancelled.paymentStatus());
                        break;
                    } catch (IllegalArgumentException | IOException ex) {
                        System.out.println("Could not cancel reservation: " + ex.getMessage() + ". Choose another reservation or enter 0 to go back.");
                    }
                }
                return;
            }
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
                System.out.println("  Pricing: " + reservationCostExplanation(r));
                System.out.println("  Reservation status: " + r.status());
                System.out.println("  Payment status: " + r.paymentStatus());
                if (r.status() == BookingStatus.DECLINED) System.out.println("  Decline reason: " + r.declineReason());
                System.out.println("----------------------------------------");
            }
            if (mine.stream().anyMatch(r -> r.status() == BookingStatus.APPROVED && r.paymentStatus() == PaymentStatus.UNPAID)
                    && readYesNo("Pay an approved reservation now? (y/n): ")) {
                List<VenueReservation> payable = mine.stream().filter(r -> r.status() == BookingStatus.APPROVED
                        && r.paymentStatus() == PaymentStatus.UNPAID).toList();
                for (int i = 0; i < payable.size(); i++) System.out.printf("%d. %s | %s | PHP %s%n", i + 1,
                        payable.get(i).venueName(),payable.get(i).date(),payable.get(i).cost().toPlainString());
                VenueReservation selected = payable.get(readInt("Choose reservation: ", 1, payable.size()) - 1);
                try {
                    System.out.printf("Reservation payment: %s on %s | PHP %s%n",selected.venueName(),selected.date(),selected.cost().setScale(2).toPlainString());
                    if(!readYesNo("Confirm reservation payment? (y/n): ")) { System.out.println("Payment cancelled."); return; }
                    paymentService.payVenue(customer, account, selected.id());
                    System.out.println("Full payment completed. Reservation confirmed.");
                } catch (IllegalArgumentException | IOException ex) { System.out.println("Payment failed: " + ex.getMessage()); }
            }
            if (readYesNo("View payment history? (y/n): ")) showPaymentHistory(customer);
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

    private void requestVenueReservation(Customer customer) {
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
            System.out.printf("Reservation requested for %s. Estimated cost PHP %s. Status PENDING.%n",
                    venue.name(),reservation.cost().setScale(2).toPlainString());
        } catch(IOException|IllegalArgumentException ex) { System.out.println("Reservation request failed: "+ex.getMessage()); }
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
            List<TicketType> types = publishingService.ticketTypes(event.id());
            for (int i = 0; i < types.size(); i++) System.out.printf("%d. %s | PHP %s | %d left%n", i + 1,
                    types.get(i).name(), types.get(i).price().toPlainString(), types.get(i).remaining());
            TicketType type = types.get(readInt("Choose a ticket category: ", 1, types.size()) - 1);
            int quantity = readInt("Quantity: ", 1, Math.max(1, type.remaining()));
            BigDecimal total=type.price().multiply(BigDecimal.valueOf(quantity));
            System.out.printf("Purchase summary: %s x %d | Total PHP %s%n",type.name(),quantity,total.setScale(2).toPlainString());
            if(!readYesNo("Confirm ticket purchase? (y/n): ")) { System.out.println("Purchase cancelled."); return; }
            publishingService.buyTicket(customer, account, event.id(), type.id(), quantity);
            System.out.println("Ticket purchase completed. The charge is recorded in payment history.");
        } catch (IOException | IllegalArgumentException ex) { System.out.println("Ticket purchase failed: " + ex.getMessage()); }
    }

    private void showMyTickets(Customer customer) {
        try {
            List<String> purchases = publishingService.purchaseHistory(customer);
            System.out.println("\nMy tickets");
            if (purchases.isEmpty()) System.out.println("No ticket purchases found.");
            else for (String purchase : purchases) {
                String[] f=purchase.replace("\"","").split(",",-1);
                try {
                    Event event=publishingService.event(f[1]);
                    String category=publishingService.ticketTypes(f[1]).stream().filter(t->t.id().equals(f[2]))
                            .map(TicketType::name).findFirst().orElse("Ticket");
                    System.out.printf("%s | %s | %s ticket(s) | PHP %s | %s | %s%n",event.name(),category,f[4],f[5],f[6],f.length>7?f[7]:"ACTIVE");
                } catch(RuntimeException ex) { System.out.println("Ticket purchase record could not be displayed."); }
            }
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
                    String description = readRequiredText("Category description: ");
                    BigDecimal price = readPrice("Ticket price (PHP): ");
                    int quantity = readInt("Available quantity: ", 1, event.venue().capacity());
                    LocalDateTime start = readDateTime("Sales opening (YYYY-MM-DDTHH:mm): ");
                    LocalDateTime deadline = readDateTime("Sales closing (YYYY-MM-DDTHH:mm): ");
                    types.add(new TicketType(java.util.UUID.randomUUID().toString(), event.id(), name,description,price,
                            quantity, 0,start,deadline));
                }
                publishingService.configureTickets(customer, event.id(), types);
                System.out.println("Ticket categories saved.");
            } else {
                if(publishingService.ticketTypes(event.id()).isEmpty()) {
                    System.out.println("This commercial event has no ticket categories. Choose Configure ticket categories, then return to Organizer Management to publish.");
                    return;
                }
                printEventDetails(event);
                for(TicketType type:publishingService.ticketTypes(event.id()))
                    System.out.printf("%s | %s | PHP %s | %d available | %s–%s%n",type.name(),type.description(),type.price(),type.quantity(),type.salesStart(),type.salesDeadline());
                if(!readYesNo("Are you sure you want to publish this event? (y/n): ")) { System.out.println("Publishing cancelled."); return; }
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

    private void createEvent(Customer customer) {
        if (customer.getAge() < 16) {
            System.out.println("Customers must be at least 16 to submit an event.");
            return;
        }

        try {
            List<VenueReservation> eligible = reservationService.forCustomer(customer).stream()
                    .filter(r -> r.status() == BookingStatus.CONFIRMED && r.paymentStatus() == PaymentStatus.PAID
                            && r.eventId().isBlank()).toList();
            System.out.println("\nCreate Event from Approved Reservation");
            if (eligible.isEmpty()) { System.out.println("No approved, paid reservations are available for event creation."); return; }
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
            EventDraft draft=new EventDraft(name,description,type,purpose,visibility,reservation.date(),reservation.startTime(),
                    reservation.endTime(),attendance,venue,List.of());
            printDraft(draft);
            if(!readYesNo("Save this event as a draft? (y/n): ")) return;
            Event saved=eventService.saveDraftForReservation(customer,draft,reservation.id());
            reservationService.attachEvent(customer,reservation.id(),saved.id());
            System.out.println("Draft saved. Submit it for approval from My Events when ready.");
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

    private void waitForDashboard() {
        System.out.println("Press 0 or Enter to return to Customer Dashboard.");
        while (true) {
            String value = readLine().trim();
            if (value.isEmpty() || value.equals("0")) return;
            System.out.println("Press 0 or Enter to return to Customer Dashboard.");
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
