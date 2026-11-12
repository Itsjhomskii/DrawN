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

final class CustomerTicketsConsole extends CustomerConsoleModule {
    CustomerTicketsConsole(DrawN host) {
        super(host);
    }

    void ticketsMenu(AccountStore.Account account, Customer customer) {
        boolean inHub = true;
        while (inHub) {
            host.printSectionHeader("Tickets");
            showMyTickets(customer);
            host.printMenuItem("1", "Buy Tickets");
            host.printMenuItem("2", "Cancel a Ticket Purchase");
            host.printMenuItem("0", "Back to Customer Menu");
            switch (host.readInt("Choose an option: ", 0, 2)) {
                case 1 -> buyPublishedTicket(account, customer);
                case 2 -> cancelTicketPurchase(account, customer);
                case 0 -> inHub = false;
                default -> throw new IllegalStateException("Unexpected Tickets option");
            }
        }
    }

    private void buyPublishedTicket(AccountStore.Account account, Customer customer) {
        try {
            List<Event> published = host.publishingService.publishedEvents();
            if (published.isEmpty()) {
                System.out.println("No published ticketed events are available.");
                return;
            }
            for (int i = 0; i < published.size(); i++) System.out.printf("%d. %s | %s%n", i + 1,
                    published.get(i).name(), published.get(i).date());
            Event event = published.get(host.readInt("Choose an event: ", 1, published.size()) - 1);
            printBuyerEventDetails(event);
            buyTicketForEvent(account, customer, event);
        } catch (IOException | IllegalArgumentException ex) { System.out.println("Ticket purchase failed: " + ex.getMessage()); }
    }

    void buyTicketForEvent(AccountStore.Account account, Customer customer, Event event) throws IOException {
        List<TicketType> types = host.publishingService.ticketTypes(event.id());
        if (types.isEmpty()) { System.out.println("Tickets are not available for this event."); return; }
        for (int i = 0; i < types.size(); i++) System.out.printf("%d. %s | PHP %s | %d left | Sales end: %s%n", i + 1,
                types.get(i).name(), types.get(i).price().toPlainString(), types.get(i).remaining(), types.get(i).salesDeadline());
        TicketType type = types.get(host.readInt("Choose a ticket category: ", 1, types.size()) - 1);
        int quantity = host.readInt("Quantity: ", 1, Math.max(1, type.remaining()));
        BigDecimal total=type.price().multiply(BigDecimal.valueOf(quantity));
        System.out.printf("Purchase summary: %s x %d | Total PHP %s%n",type.name(),quantity,total.setScale(2).toPlainString());
        if(!host.readYesNo("Confirm ticket purchase? (y/n): ")) { System.out.println("Purchase cancelled."); return; }
        host.publishingService.buyTicket(customer, account, event.id(), type.id(), quantity);
        System.out.println("Ticket purchase completed. The charge is recorded in Wallet & Transactions.");
    }

    private void showMyTickets(Customer customer) {
        try {
            List<TicketRepository.Purchase> purchases = host.publishingService.purchases(customer);
            System.out.println("My tickets");
            if (purchases.isEmpty()) System.out.println("No ticket purchases found.");
            else for (int i = 0; i < purchases.size(); i++) {
                TicketRepository.Purchase purchase = purchases.get(i);
                try {
                    Event event=host.publishingService.event(purchase.eventId());
                    String category=host.publishingService.ticketTypes(purchase.eventId()).stream().filter(t->t.id().equals(purchase.ticketTypeId()))
                            .map(TicketType::name).findFirst().orElse("Ticket");
                    System.out.printf("%d. %s | %s | %d ticket(s) | PHP %s | %s%n", i + 1, event.name(), category,
                            purchase.quantity(), purchase.amount().setScale(2).toPlainString(), purchase.status());
                } catch(RuntimeException ex) { System.out.println("Ticket purchase record could not be displayed."); }
            }
        } catch (IOException ex) { System.out.println("Could not load tickets: " + ex.getMessage()); }
    }

    private void cancelTicketPurchase(AccountStore.Account account, Customer customer) {
        try {
            List<TicketRepository.Purchase> active = host.publishingService.purchases(customer).stream()
                    .filter(purchase -> purchase.status() == drawn.model.ticket.TicketPurchaseStatus.ACTIVE).toList();
            if (active.isEmpty()) { System.out.println("No active ticket purchases are available to cancel."); return; }
            System.out.println("\nActive ticket purchases");
            for (int i = 0; i < active.size(); i++) {
                TicketRepository.Purchase purchase = active.get(i);
                Event event = host.publishingService.event(purchase.eventId());
                System.out.printf("%d. %s | %d ticket(s) | PHP %s%n", i + 1, event.name(), purchase.quantity(),
                        purchase.amount().setScale(2).toPlainString());
            }
            TicketRepository.Purchase selected = active.get(host.readInt("Choose a purchase to cancel: ", 1, active.size()) - 1);
            EventPublishingService.TicketCancellationQuote quote = host.publishingService.cancellationQuote(customer, selected.id());
            System.out.printf("Cancellation summary: refund PHP %s | fee PHP %s (organizer PHP %s, admin PHP %s)%n",
                    quote.refundAmount().toPlainString(), quote.cancellationFee().toPlainString(),
                    quote.organizerFee().toPlainString(), quote.adminFee().toPlainString());
            if (!host.readYesNo("Confirm ticket cancellation? (y/n): ")) { System.out.println("Ticket cancellation cancelled."); return; }
            host.publishingService.cancelTicket(customer, account, selected.id());
            System.out.println("Ticket purchase cancelled. Your refund has been added to your wallet.");
        } catch (IOException | IllegalArgumentException ex) {
            System.out.println("Ticket cancellation failed: " + ex.getMessage());
        }
    }

}
