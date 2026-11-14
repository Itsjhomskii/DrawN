package drawn.gui.views;

import drawn.model.event.Event;
import drawn.model.event.VenueReservation;
import drawn.service.customer.CustomerWorkflowService;
import drawn.storage.AccountStore;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Wallet balance, simulated top-up and transaction history (console walletMenu). */
final class WalletPage {
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final MainShell shell;

    WalletPage(MainShell shell) { this.shell = shell; }

    Node view() {
        AccountStore.Account account = shell.session().account();
        Label balance = new Label(Ui.php(account.getWalletBalance()));
        balance.setId("wallet-balance");
        balance.getStyleClass().add("stat-value");
        TextField amount = Ui.field("Amount to add (PHP)");
        amount.setId("topup-amount");
        var topUp = Ui.primary("Add Funds", () -> topUp(amount.getText()));
        topUp.setId("topup-button");
        amount.setOnAction(e -> topUp(amount.getText()));
        HBox topUpRow = Ui.actions(amount, topUp);
        HBox.setHgrow(amount, Priority.ALWAYS);
        VBox balanceCard = Ui.card(Ui.muted("Wallet balance"), balance, Ui.section("Add wallet funds"), topUpRow,
                Ui.muted("Simulated top-up: more than PHP 0.00 and no more than PHP 100,000.00."));

        return Ui.page("Wallet & Transactions", "Your simulated wallet and every payment, refund and fee.",
                balanceCard, history(account));
    }

    private Node history(AccountStore.Account account) {
        try {
            List<AccountStore.Transaction> transactions = shell.services().accounts().loadTransactions(account.getId());
            List<AccountStore.Transaction> funding = transactions.stream().filter(CustomerWorkflowService::isFunding).toList();
            List<AccountStore.Transaction> eventTransactions = transactions.stream().filter(t -> !funding.contains(t)).toList();

            TableView<AccountStore.Transaction> fundingTable = Ui.table(funding, "No top-ups or initial funding found.");
            fundingTable.setId("funding-table");
            Ui.column(fundingTable, "Transaction", CustomerWorkflowService::fundingLabel);
            Ui.column(fundingTable, "Amount", t -> Ui.php(t.amount()));
            Ui.column(fundingTable, "Balance after", t -> Ui.php(t.balanceAfter()));
            Ui.column(fundingTable, "Date", t -> t.timestamp().format(WHEN));

            TableView<AccountStore.Transaction> eventTable = Ui.table(eventTransactions, "No event-related payments, refunds, or fees found.");
            eventTable.setId("event-transactions-table");
            Ui.column(eventTable, "Transaction", t -> CustomerWorkflowService.transactionLabel(t.type()));
            Ui.column(eventTable, "Details", this::details);
            Ui.column(eventTable, "Amount", t -> Ui.php(t.amount()));
            Ui.column(eventTable, "Balance after", t -> Ui.php(t.balanceAfter()));
            Ui.column(eventTable, "Date", t -> t.timestamp().format(WHEN));

            List<Event> events = shell.services().events().eventsFor(shell.session().customer());
            TableView<Event> eventRecords = Ui.table(events, "No event records found.");
            Ui.column(eventRecords, "Event", Event::name);
            Ui.column(eventRecords, "Date", e -> String.valueOf(e.date()));
            Ui.column(eventRecords, "Venue", e -> e.venue().name());
            Ui.column(eventRecords, "Status", e -> Ui.label(e.approvalStatus()));

            List<VenueReservation> reservations = shell.services().reservations().forCustomer(shell.session().customer());
            TableView<VenueReservation> reservationRecords = Ui.table(reservations, "No venue reservation records found.");
            Ui.column(reservationRecords, "Venue", VenueReservation::venueName);
            Ui.column(reservationRecords, "Date", r -> String.valueOf(r.date()));
            Ui.column(reservationRecords, "Time", r -> r.startTime() + "-" + r.endTime());
            Ui.column(reservationRecords, "Cost", r -> Ui.php(r.cost()));
            Ui.column(reservationRecords, "Status", r -> r.status().name());

            return new VBox(14, Ui.section("Wallet funding"), fundingTable, Ui.section("Event transactions"), eventTable,
                    Ui.section("Event records"), eventRecords, Ui.section("Venue reservation records"), reservationRecords);
        } catch (Exception ex) {
            return Ui.muted("Could not read wallet history: " + Ui.message(ex));
        }
    }

    private String details(AccountStore.Transaction transaction) {
        try { return shell.services().workflow().transactionDetails(transaction); }
        catch (Exception ex) { return "Event-related wallet transaction."; }
    }

    private void topUp(String text) {
        try {
            BigDecimal amount = Ui.amount(text, "Top-up amount");
            AccountStore.Account account = shell.session().account();
            if (!Ui.confirm("Confirm simulated top-up?", "SIMULATED TOP-UP: " + Ui.php(amount) + " | Current balance: "
                    + Ui.php(account.getWalletBalance()))) {
                Ui.info("Top-up cancelled.", "Your balance was not changed.");
                return;
            }
            shell.services().accounts().topUp(account, amount);
            Ui.info("Top-up successful.", "New wallet balance: " + Ui.php(account.getWalletBalance()));
            shell.showPage(MainShell.WALLET);
        } catch (Exception ex) {
            Ui.error("Could not save wallet top-up", Ui.message(ex));
        }
    }
}
