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

import java.math.BigDecimal;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

final class CustomerProfileConsole extends CustomerConsoleModule {
    CustomerProfileConsole(DrawN host) {
        super(host);
    }

    void showCustomerDashboard(AccountStore.Account account, Customer customer) {
        host.printBrandBanner();
        System.out.println("Welcome, " + customer.getName() + "!");
        host.printSectionHeader("Account Overview");
        System.out.printf("Account Balance       : PHP %s%n", account.getWalletBalance().setScale(2).toPlainString());
        try {
            List<Event> ownedEvents = host.eventService.eventsFor(customer);
            List<VenueReservation> myReservations=host.reservationService.forCustomer(customer);
            long pending = myReservations.stream()
                    .filter(reservation -> reservation.status() == BookingStatus.PENDING).count();
            System.out.printf("Events Organized      : %d%n", ownedEvents.size());
            System.out.printf("Active Tickets        : %d%n",host.publishingService.activeTicketCount(customer));
            System.out.printf("Venue Requests Awaiting Approval : %d%n", pending);
        } catch (IOException ex) {
            System.out.println("Events Organized      : Not Available (could not read event submissions)");
            System.out.println("Active Tickets        : 0");
            System.out.println("Venue Requests Awaiting Approval : 0");
        }
        try {
            var earnings = host.earningsService.forOrganizer(customer);
            System.out.printf("Gross Ticket Revenue  : PHP %s%n", earnings.grossTicketRevenue().toPlainString());
            System.out.printf("Platform Commission   : PHP %s (%s%%)%n", earnings.platformCommission().toPlainString(),
                    CommissionPolicy.configured().percent().toPlainString());
            System.out.printf("Organizer Net Earnings: PHP %s%n", earnings.netEarnings().toPlainString());
            System.out.printf("Tickets Sold          : %d%n", earnings.ticketsSold());
        } catch (IOException | IllegalArgumentException ex) {
            System.out.println("Organizer earnings    : Not Available (" + ex.getMessage() + ")");
        }
    }

    void showProfile(AccountStore.Account account) {
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

}
