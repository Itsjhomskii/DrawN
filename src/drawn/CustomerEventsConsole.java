package drawn;

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
import drawn.service.payment.CommissionPolicy;
import drawn.service.event.EventService;
import drawn.storage.AccountStore;
import drawn.storage.TicketRepository;
import drawn.DrawN.LegacyEvent;

import java.math.BigDecimal;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.ArrayList;

final class CustomerEventsConsole extends CustomerConsoleModule {
    private final CustomerTicketsConsole tickets;

    CustomerEventsConsole(DrawN host, CustomerTicketsConsole tickets) {
        super(host);
        this.tickets = tickets;
    }

    void eventsAndVenuesMenu(AccountStore.Account account, Customer customer) {
        boolean inHub = true;
        while (inHub) {
            host.printSectionHeader("Plan an Event");
            host.printMenuItem("1", "My Event Plans & Venue Requests");
            host.printMenuItem("2", "Create & Publish Event (Approved Venue)");
            host.printMenuItem("3", "Manage Venue Requests");
            host.printMenuItem("4", "Explore Events");
            host.printMenuItem("0", "Back to Customer Menu");
            switch (host.readInt("Choose an option: ", 0, 4)) {
                case 1 -> showMyEvents(customer);
                case 2 -> createEvent(account, customer);
                case 3 -> venueReservations(account, customer);
                case 4 -> exploreEvents(account, customer);
                case 0 -> inHub = false;
                default -> throw new IllegalStateException("Unexpected event planning option");
            }
        }
    }

    private void showMyEvents(Customer customer) {
        try {
            List<Event> ownEvents = host.eventService.eventsFor(customer).stream()
                    .filter(event -> event.approvalStatus() != drawn.model.event.EventApprovalStatus.CANCELLED).toList();
            System.out.println("\nMy Event Plans");
            List<VenueReservation> bookings = host.reservationService.forCustomer(customer);
            List<VenueReservation> unlinkedReservations = bookings.stream().filter(reservation -> reservation.eventId().isBlank()).toList();
            if (ownEvents.isEmpty()) System.out.println("No active events found.");
            else {
                for (int i = 0; i < ownEvents.size(); i++) System.out.printf("%d. %s%n", i + 1, ownEvents.get(i).name());
                int eventChoice = host.readInt("Choose an event (0 to go back): ", 0, ownEvents.size());
                if (eventChoice == 0) return;
                Event event = ownEvents.get(eventChoice - 1);
                VenueReservation booking = bookings.stream().filter(reservation -> reservation.eventId().equals(event.id())).findFirst().orElse(null);
                System.out.println();
                host.printEventDetails(event);
                System.out.println("  Event status: " + event.approvalStatus());
                if (booking != null) System.out.printf("  Venue booking: %s | PHP %s%n", reservationStatusLabel(booking),
                        booking.cost().setScale(2).toPlainString());
                System.out.println("\n1. Cancel event\n0. Back");
                if (host.readInt("Choose an action: ", 0, 1) == 1) {
                    if (!host.readYesNo("Cancel this event and process any eligible refunds? (y/n): ")) {
                        System.out.println("Event cancellation cancelled.");
                        return;
                    }
                    host.cancellationService.cancel(customer, event.id());
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
            int reservationAction = host.readInt("Choose an option: ",1,2);
            if (reservationAction == 2) { requestVenueReservation(account, customer); return; }
            List<VenueReservation> mine = host.reservationService.forCustomer(customer);
            List<VenueReservation> activeReservations = mine.stream().filter(reservation -> reservation.status() != BookingStatus.CANCELLED
                    && reservation.status() != BookingStatus.DECLINED && reservation.status() != BookingStatus.EXPIRED).toList();
            System.out.println("\nActive venue requests");
            if (activeReservations.isEmpty()) { System.out.println("No active venue requests found."); return; }
            for (int i = 0; i < activeReservations.size(); i++) System.out.printf("%d. %s%n", i + 1, activeReservations.get(i).venueName());
            int selectedIndex = host.readInt("Choose a venue request (0 to go back): ", 0, activeReservations.size());
            if (selectedIndex == 0) return;
            VenueReservation selected = activeReservations.get(selectedIndex - 1);
            System.out.println("\nVenue: " + selected.venueName());
            System.out.println("Date: " + selected.date());
            System.out.println("Time: " + selected.startTime() + "-" + selected.endTime());
            System.out.println("Reservation cost: PHP " + selected.cost().setScale(2).toPlainString());
            System.out.println("Pricing: " + reservationCostExplanation(selected));
            System.out.println("Reservation status: " + reservationStatusLabel(selected));
            System.out.println("Payment status: " + selected.paymentStatus());
            if (selected.status() == BookingStatus.PENDING && selected.paymentStatus() == PaymentStatus.PAID)
                System.out.println("This paid request is awaiting administrator approval.");
            System.out.println("\n1. Cancel reservation\n0. Back");
            if (host.readInt("Choose an action: ", 0, 1) == 1) {
                if (!host.readYesNo("Cancel this reservation and apply the eligible refund policy? (y/n): ")) {
                    System.out.println("Reservation cancellation cancelled.");
                    return;
                }
                try {
                    VenueReservation cancelled = host.paymentService.cancelReservation(customer, account, selected.id());
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
        EventPurpose purpose = chooseEventPurpose();
        Venue venue = chooseVenue(purpose);
        LocalDate date=readEventDate();
        LocalTime start=readTime("Start time (HH:mm): ");
        LocalTime end=readTime("End time (HH:mm): ");
        ReservationPricing pricing=chooseEnum("Reservation pricing",ReservationPricing.values());
        try {
            ReservationService.validatePricing(start, end, pricing);
            BigDecimal quote = ReservationService.price(venue, start, end, pricing);
            System.out.println("\nReservation payment review");
            System.out.printf("Venue: %s%nDate: %s%nTime: %s-%s%nAmount due: PHP %s%n", venue.name(), date,
                    start, end, quote.setScale(2).toPlainString());
            if (account.getWalletBalance().compareTo(quote) < 0)
                throw new IllegalArgumentException("Insufficient wallet balance.");
            if (!host.readYesNo("Confirm full reservation payment? (y/n): ")) {
                System.out.println("Reservation request cancelled. No payment was taken.");
                return;
            }
            VenueReservation submitted = host.venueWorkflow.requestAndPay(customer, account, venue, date, start, end, pricing);
            System.out.printf("%nReservation payment successful!%n%nAmount Paid: PHP %s%nPayment Status: %s%nReservation Status: %s%n%n"
                            + "Your reservation has been submitted for administrator approval.%n"
                            + "You may create an event using this reservation once it has been approved.%n",
                    submitted.cost().setScale(2).toPlainString(), submitted.paymentStatus(), submitted.status());
        } catch(IOException|IllegalArgumentException ex) { System.out.println("Reservation request failed: "+ex.getMessage()); }
    }

    private void browsePublishedEvents() {
        try {
            List<Event> published = host.publishingService.publishedEvents();
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
                for (TicketType type : host.publishingService.ticketTypes(event.id()))
                    System.out.printf("  Ticket category: %s%n  Ticket price: PHP %s%n  Tickets available: %d%n  Sales deadline: %s%n",
                            type.name(), type.price().toPlainString(), type.remaining(), type.salesDeadline());
                System.out.println("----------------------------------------");
            }
        } catch (IOException ex) { System.out.println("Could not browse published events: " + ex.getMessage()); }
    }

    private void listCustomerEvents() {
        System.out.println("\nAvailable events");
        boolean found = false;
        for (int i = 0; i < host.events.size(); i++) {
            LegacyEvent event = host.events.get(i);
            found = true;
            System.out.printf("Event %d%n", i + 1);
            System.out.println("  Name: " + event.name);
            System.out.println("  Date: " + event.date);
            System.out.println("  Ticket price: PHP " + event.price.toPlainString());
            System.out.println("  Tickets remaining: " + event.remaining);
            System.out.println("----------------------------------------");
        }
        try {
        List<Event> approved = host.publishingService.publishedEvents();
            for (Event event : approved) {
                found = true;
                host.printEventDetails(event);
            }
        } catch (IOException ex) {
            System.out.println("Could not load approved events: " + ex.getMessage());
        }
        if (!found) System.out.println("No events are currently available.");
    }

    private void exploreEvents(AccountStore.Account account, Customer customer) {
        try {
            System.out.println("\nExplore events");
            System.out.println("1. Public events\n2. Private event access");
            int source = host.readInt("Choose an option: ", 1, 2);
            List<Event> published;
            if (source == 1) {
                published = host.publishingService.publishedEvents();
            } else {
                String keyword = host.readRequiredText("Private event access keyword: ");
                published = host.eventRepository.findAll().stream().filter(event -> event.approvalStatus()
                        == drawn.model.event.EventApprovalStatus.PUBLISHED && event.visibility() == EventVisibility.PRIVATE)
                        .filter(event -> {
                            try { return host.privateEventAccess.matches(event.id(), keyword); }
                            catch (IOException ex) { throw new IllegalStateException(ex); }
                        }).toList();
            }
            if (published.isEmpty()) { System.out.println("No published events are available."); return; }
            for (int i = 0; i < published.size(); i++) System.out.printf("%d. %s%n", i + 1, published.get(i).name());
            int choice = host.readInt("Choose an event (0 to go back): ", 0, published.size());
            if (choice == 0) return;
            Event event = published.get(choice - 1);
            printBuyerEventDetails(event);
            if (event.purpose() == EventPurpose.TICKETED_EVENT) {
                System.out.println("1. Buy tickets\n0. Back");
                if (host.readInt("Choose an action: ", 0, 1) == 1) tickets.buyTicketForEvent(account, customer, event);
            }
        } catch (IOException | IllegalArgumentException ex) {
            System.out.println("Could not explore events: " + ex.getMessage());
        }
    }

    private void createEvent(AccountStore.Account account, Customer customer) {
        if (customer.getAge() < 16) {
            System.out.println("Customers must be at least 16 to submit an event.");
            return;
        }

        try {
            List<VenueReservation> eligible = host.reservationService.forCustomer(customer).stream()
                    .filter(r -> (r.status() == BookingStatus.APPROVED || r.status() == BookingStatus.CONFIRMED)
                            && r.paymentStatus() == PaymentStatus.PAID
                            && r.eventId().isBlank()).toList();
            System.out.println("\nCreate & Publish Event from an Approved Venue Booking");
            if (eligible.isEmpty()) {
                System.out.println("No approved, paid venue reservation is ready for event details yet.");
                System.out.println("Request and pay for a venue first, then wait for administrator approval.");
                if (host.readYesNo("Request another venue now? (y/n): ")) requestVenueReservation(account, customer);
                return;
            }
            for (int i=0;i<eligible.size();i++) {
                VenueReservation r=eligible.get(i);
                System.out.printf("%d. %s | %s %s-%s | %s%n", i+1,r.venueName(),r.date(),r.startTime(),r.endTime(),r.status());
            }
            int reservationChoice=host.readInt("Choose reservation (0 to cancel): ",0,eligible.size());
            if(reservationChoice==0) return;
            VenueReservation reservation=eligible.get(reservationChoice-1);
            EventType type=chooseEnum("Event category",EventType.values());
            EventPurpose purpose=chooseEventPurpose();
            Venue venue=VenueCatalog.all().stream().filter(v->v.name().equals(reservation.venueName())).findFirst()
                    .orElseThrow(()->new IllegalArgumentException("Reserved venue details are unavailable."));
            if(!venue.supports(purpose)) throw new IllegalArgumentException("Selected reservation venue does not support this event purpose.");
            String name=host.readRequiredText("Event name: ");
            String description=readDescription();
            int attendance=host.readInt("Expected attendance (venue capacity "+venue.capacity()+"): ",1,venue.capacity());
            EventVisibility visibility=host.readInt("Visibility: 1. Public  2. Private: ",1,2)==1?EventVisibility.PUBLIC:EventVisibility.PRIVATE;
            String privateKeyword = visibility == EventVisibility.PRIVATE
                    ? host.readRequiredText("Private event access keyword: ") : "";
            List<TicketCategory> categories = purpose == EventPurpose.TICKETED_EVENT ? readTicketCategories() : List.of();
            int ticketQuantity = categories.stream().mapToInt(TicketCategory::quantity).sum();
            if (ticketQuantity > venue.capacity()) throw new IllegalArgumentException("Total ticket quantity exceeds venue capacity.");
            EventDraft draft=new EventDraft(name,description,type,purpose,visibility,reservation.date(),reservation.startTime(),
                    reservation.endTime(),attendance,venue,categories);
            printDraft(draft);
            if(!host.readYesNo("Confirm and create this event? (y/n): ")) {
                System.out.println("Event creation cancelled. Your venue reservation remains valid.");
                return;
            }
            host.eventCreationService.create(customer, draft, reservation.id(), privateKeyword);
            System.out.println("Event published.");
        } catch (IOException | RuntimeException ex) {
            System.out.println("Event submission failed: " + ex.getMessage());
        }
    }

    private String readDescription() {
        while (true) {
            String value = host.readRequiredText("Event description (maximum 500 characters): ");
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
        return options[host.readInt("Choose an option: ", 1, options.length) - 1];
    }

    private EventPurpose chooseEventPurpose() {
        System.out.println("\nEvent purpose:");
        System.out.println("1. Social Gathering");
        System.out.println("2. Commercial / Ticketed Event");
        return host.readInt("Choose an option: ", 1, 2) == 1
                ? EventPurpose.SOCIAL_GATHERING : EventPurpose.TICKETED_EVENT;
    }

    String displayPurpose(EventPurpose purpose) {
        return purpose == EventPurpose.SOCIAL_GATHERING ? "Social Gathering" : "Commercial / Ticketed Event";
    }

    private LocalDate readEventDate() {
        while (true) {
            System.out.print("Event date (YYYY-MM-DD): ");
            try {
                LocalDate date = LocalDate.parse(host.readLine().trim());
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
                return LocalTime.parse(host.readLine().trim());
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
        return compatible.get(host.readInt("Choose a venue: ", 1, compatible.size()) - 1);
    }

    private List<TicketCategory> readTicketCategories() {
        int categoryCount = host.readInt("Number of ticket categories: ", 1, Integer.MAX_VALUE);
        List<TicketCategory> categories = new ArrayList<>();
        while (categories.size() < categoryCount) {
            String name = host.readRequiredText("Category " + (categories.size() + 1) + " name: ");
            if (categories.stream().anyMatch(category -> category.name().equalsIgnoreCase(name))) {
                System.out.println("Category names must be unique. Enter a different name.");
                continue;
            }
            BigDecimal price = host.readPrice("Ticket price (PHP): ");
            int quantity = host.readInt("Available quantity: ", 1, Integer.MAX_VALUE);
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

    void showEventAndVenueRecords(Customer customer) throws IOException {
        List<Event> events = host.eventService.eventsFor(customer);
        List<VenueReservation> reservations = host.reservationService.forCustomer(customer);
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

}
