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

final class CustomerWalletConsole extends CustomerConsoleModule {
    private final CustomerEventsConsole events;

    CustomerWalletConsole(DrawN host, CustomerEventsConsole events) {
        super(host);
        this.events = events;
    }

    void walletMenu(AccountStore.Account account, Customer customer) {
        boolean inMenu = true;
        while (inMenu) {
            System.out.println("\nWallet & Transactions");
            System.out.println("1. View wallet balance");
            System.out.println("2. Add wallet funds");
            System.out.println("3. View transaction history");
            System.out.println("0. Back to dashboard");
            int choice = host.readInt("Choose an option: ", 0, 3);
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

    private void topUpWallet(AccountStore.Account account) {
        BigDecimal amount = host.readPrice("Enter amount to add (PHP): ");
        if (amount.signum()<=0 || amount.compareTo(new BigDecimal("100000.00"))>0) {
            System.out.println("Simulated top-up must be greater than PHP 0.00 and no more than PHP 100,000.00."); return;
        }
        System.out.printf("SIMULATED TOP-UP: PHP %s | Current balance: PHP %s%n",amount.setScale(2).toPlainString(),
                account.getWalletBalance().setScale(2).toPlainString());
        if(!host.readYesNo("Confirm simulated top-up? (y/n): ")) { System.out.println("Top-up cancelled."); return; }
        try {
            host.accounts.topUp(account, amount);
            System.out.printf("Top-up successful. New wallet balance: PHP %s%n",
                    account.getWalletBalance().setScale(2).toPlainString());
        } catch (IOException | IllegalArgumentException ex) {
            System.out.println("Could not save wallet top-up: " + ex.getMessage());
        }
    }

    private void showWalletHistory(AccountStore.Account account, Customer customer) {
        try {
            List<AccountStore.Transaction> transactions = host.accounts.loadTransactions(account.getId());
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
            events.showEventAndVenueRecords(customer);
        } catch (IOException ex) {
            System.out.println("Could not read wallet history: " + ex.getMessage());
        }
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
            TicketRepository.Purchase purchase = host.ticketRepository.allPurchaseRecords().stream()
                    .filter(candidate -> candidate.id().equals(ticketPurchaseId)).findFirst().orElse(null);
            if (purchase == null) return action + " for a ticket purchase.";
            Event event = host.eventRepository.findAll().stream().filter(candidate -> candidate.id().equals(purchase.eventId()))
                    .findFirst().orElse(null);
            String category = host.ticketRepository.types(purchase.eventId()).stream()
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
            VenueReservation reservation = host.reservationRepository.findAll().stream()
                    .filter(candidate -> candidate.id().equals(venueReservationId)).findFirst().orElse(null);
            if (reservation == null) return "Venue reservation transaction.";
            return reservation.venueName() + " | " + reservation.date() + " | " + reservation.startTime()
                    + "-" + reservation.endTime() + ".";
        }
        return "Event-related wallet transaction.";
    }

}
