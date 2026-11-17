package drawn.gui.views;

import drawn.model.event.Event;
import drawn.model.event.VenueReservation;
import drawn.service.customer.CustomerWorkflowService;
import drawn.storage.AccountStore;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Customer wallet dashboard backed by the existing wallet and record services. */
final class WalletPage {
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("MMMM d, yyyy · h:mm a", Locale.US);
    private static final DecimalFormat PESO = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
    private static final List<String> CATEGORIES = List.of("Top-ups", "Event Transactions", "Event Records", "Pending");

    private final MainShell shell;
    private final VBox pageBody = new VBox(18);
    private final ScrollPane scroll = new ScrollPane(pageBody);
    private String selectedCategory = CATEGORIES.get(0);
    private WalletData data = WalletData.empty();
    private String transactionLoadError = "";

    WalletPage(MainShell shell) { this.shell = shell; }

    Node view() {
        pageBody.getStyleClass().add("content-page");
        pageBody.getStyleClass().add("wallet-page");
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("content-scroll");
        scroll.setId("wallet-page-scroll");
        refresh();
        pageBody.widthProperty().addListener((observable, oldWidth, width) -> updateResponsiveWidths(width.doubleValue()));
        return scroll;
    }

    private void refresh() {
        loadData();
        pageBody.getChildren().setAll(header(), overview(), summaries(), history());
        updateResponsiveWidths(pageBody.getWidth());
    }

    private void loadData() {
        AccountStore.Account account = shell.session().account();
        List<AccountStore.Transaction> transactions = List.of();
        List<Event> events = List.of();
        List<VenueReservation> reservations = List.of();
        transactionLoadError = "";
        try {
            transactions = shell.services().accounts().loadTransactions(account.getId()).stream()
                    .sorted(Comparator.comparing(AccountStore.Transaction::timestamp).reversed()).toList();
        } catch (Exception ex) {
            transactionLoadError = "Could not read wallet transactions: " + Ui.message(ex);
        }
        try { events = shell.services().events().eventsFor(shell.session().customer()); }
        catch (Exception ignored) { }
        try { reservations = shell.services().reservations().forCustomer(shell.session().customer()); }
        catch (Exception ignored) { }
        reservations = reservations.stream().sorted(Comparator.comparing(VenueReservation::date).reversed()).toList();
        data = new WalletData(transactions, events, reservations);
    }

    private Node header() {
        Label eyebrow = new Label("DRAWN / FINANCIAL OVERVIEW");
        eyebrow.getStyleClass().add("wallet-eyebrow");
        VBox text = new VBox(4, eyebrow, Ui.title("Wallet & Transactions"), Ui.muted("Your money, your events, all in one place."));
        HBox header = new HBox(text, Ui.grow(), new DashboardIcon("fi fi-rr-wallet", 20));
        header.setAlignment(Pos.TOP_LEFT);
        header.getStyleClass().add("wallet-header");
        return header;
    }

    private Node overview() {
        BigDecimal balance = shell.session().account().getWalletBalance();
        Label label = new Label("AVAILABLE BALANCE");
        label.getStyleClass().add("wallet-balance-label");
        Label amount = new Label(peso(balance));
        amount.setId("wallet-balance");
        amount.getStyleClass().add("wallet-balance-value");
        Label walletName = new Label("DrawN Wallet");
        walletName.getStyleClass().add("wallet-balance-caption");
        Button addFunds = new Button("Add Funds", new DashboardIcon("fi fi-rr-add", 14));
        addFunds.setId("topup-button");
        addFunds.getStyleClass().add("wallet-add-funds-button");
        addFunds.setOnAction(event -> showAddFunds());
        HBox bottom = new HBox(walletName, Ui.grow(), addFunds);
        bottom.setAlignment(Pos.CENTER_LEFT);
        VBox balanceCard = new VBox(18, label, amount, bottom);
        balanceCard.setId("wallet-balance-card");
        balanceCard.getStyleClass().add("wallet-balance-card");

        VBox activityCard = new VBox(12);
        activityCard.setId("wallet-activity-card");
        activityCard.getStyleClass().add("wallet-activity-card");
        Label activityTitle = Ui.section("Financial overview");
        activityCard.getChildren().add(activityTitle);
        activityCard.getChildren().add(activityLine("Wallet credits", latestDate(this::isCredit), "↙"));
        activityCard.getChildren().add(activityLine("Event spending", latestDate(this::isPayment), "↗"));
        activityCard.getChildren().add(activityLine("Refund activity", latestDate(t -> t.type().startsWith("REFUND:")), "↶"));

        FlowPane overview = new FlowPane(12, 12, balanceCard, activityCard);
        overview.getStyleClass().add("wallet-overview");
        overview.setId("wallet-overview");
        return overview;
    }

    private HBox activityLine(String label, String date, String icon) {
        Label glyph = new Label(icon);
        glyph.getStyleClass().add("wallet-activity-icon");
        Label name = new Label(label);
        name.getStyleClass().add("wallet-activity-label");
        Label value = new Label(date);
        value.getStyleClass().add("wallet-activity-date");
        HBox row = new HBox(9, name, Ui.grow(), value, glyph);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("wallet-activity-row");
        return row;
    }

    private String latestDate(java.util.function.Predicate<AccountStore.Transaction> test) {
        return data.transactions().stream().filter(test).map(AccountStore.Transaction::timestamp)
                .max(LocalDateTime::compareTo).map(time -> time.format(DateTimeFormatter.ofPattern("MMM d", Locale.US)))
                .orElse("No activity");
    }

    private Node summaries() {
        BigDecimal topups = sum(data.transactions().stream().filter(CustomerWorkflowService::isFunding).toList());
        BigDecimal spending = sum(data.transactions().stream().filter(this::isPayment).toList());
        BigDecimal refunds = sum(data.transactions().stream().filter(t -> t.type().startsWith("REFUND:")).toList());
        FlowPane cards = new FlowPane(12, 12,
                summaryCard("Total Top-ups", peso(topups), "↙", "Includes initial funding and successful top-ups"),
                summaryCard("Total Spending", peso(spending), "▣", "Gross wallet payments · refunds shown separately"),
                summaryCard("Refunds Received", peso(refunds), "↶", "Completed wallet refunds"));
        cards.getStyleClass().add("wallet-summary-grid");
        cards.setId("wallet-summary-grid");
        return cards;
    }

    private VBox summaryCard(String title, String value, String icon, String caption) {
        HBox heading = new HBox(8, new Label(icon), new Label(title));
        heading.getStyleClass().add("financial-summary-heading");
        Label amount = new Label(value);
        amount.getStyleClass().add("financial-summary-value");
        VBox card = new VBox(8, heading, amount, Ui.muted(caption));
        card.getStyleClass().add("financial-summary-card");
        card.setMinWidth(210);
        card.setId("summary-" + title.toLowerCase(Locale.ROOT).replace(' ', '-'));
        return card;
    }

    private Node history() {
        VBox panel = new VBox(14);
        panel.setId("transaction-history-panel");
        panel.getStyleClass().add("transaction-history-panel");
        HBox title = new HBox(Ui.section("Transaction History"), Ui.grow(), new Label("▽"));
        title.setAlignment(Pos.CENTER_LEFT);
        panel.getChildren().add(title);

        FlowPane filters = new FlowPane(7, 7);
        filters.getStyleClass().add("transaction-category-filters");
        for (String category : CATEGORIES) {
            Button filter = new Button(category);
            filter.setId("wallet-filter-" + category.toLowerCase(Locale.ROOT).replace(' ', '-'));
            filter.getStyleClass().add("transaction-category-filter");
            if (category.equals(selectedCategory)) filter.getStyleClass().add("transaction-category-active");
            filter.setOnAction(event -> { selectedCategory = category; refresh(); });
            filters.getChildren().add(filter);
        }
        panel.getChildren().add(filters);
        if (selectedCategory.equals("Pending"))
            panel.getChildren().add(Ui.muted("Venue reservations appear here with their current approval and payment status."));

        VBox rows = new VBox();
        rows.setId("transaction-history-rows");
        if (!transactionLoadError.isBlank()) rows.getChildren().add(Ui.muted(transactionLoadError));
        else if (selectedCategory.equals("Top-ups")) addTransactionRows(rows,
                data.transactions().stream().filter(CustomerWorkflowService::isFunding).toList());
        else if (selectedCategory.equals("Event Transactions")) addTransactionRows(rows,
                data.transactions().stream().filter(t -> !CustomerWorkflowService.isFunding(t)).filter(this::isEventTransaction).toList());
        else if (selectedCategory.equals("Event Records")) addEventRows(rows);
        else addPendingRows(rows);
        if (rows.getChildren().isEmpty()) rows.getChildren().add(Ui.muted(emptyMessage(selectedCategory)));
        panel.getChildren().add(rows);
        return panel;
    }

    private void addTransactionRows(VBox rows, List<AccountStore.Transaction> transactions) {
        transactions.forEach(transaction -> rows.getChildren().add(transactionRow(transaction)));
    }

    private Node transactionRow(AccountStore.Transaction transaction) {
        boolean credit = isCredit(transaction);
        String label = CustomerWorkflowService.isFunding(transaction)
                ? CustomerWorkflowService.fundingLabel(transaction)
                : CustomerWorkflowService.transactionLabel(transaction.type());
        if (label.equals(transaction.type())) label = readableType(transaction.type());
        String rowLabel = label;
        Label icon = new Label(credit ? "↙" : "↗");
        icon.getStyleClass().add("transaction-row-icon");
        VBox description = new VBox(3, new Label(label), muted(transaction.timestamp().format(WHEN)));
        description.getStyleClass().add("transaction-row-description");
        Label amount = new Label((credit ? "+ " : "− ") + peso(transaction.amount().abs()));
        amount.getStyleClass().add(credit ? "transaction-credit" : "transaction-debit");
        HBox content = new HBox(12, icon, description, Ui.grow(), amount);
        content.setAlignment(Pos.CENTER_LEFT);
        Button row = new Button("", content);
        row.setId("transaction-row-" + Math.abs(transaction.hashCode()));
        row.getStyleClass().add("transaction-row");
        row.setMaxWidth(Double.MAX_VALUE);
        row.setOnAction(event -> showTransactionDetails(transaction, rowLabel));
        return row;
    }

    private void addEventRows(VBox rows) {
        data.events().stream().sorted(Comparator.comparing(Event::date).reversed()).forEach(event -> {
            HBox row = recordRow("Event record", event.name(), event.date().format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US)), event.venue().name());
            rows.getChildren().add(row);
        });
    }

    private void addPendingRows(VBox rows) {
        List<Node> entries = new ArrayList<>();
        for (VenueReservation reservation : data.reservations()) {
            entries.add(recordRow("Venue reservation", reservation.venueName(), reservation.date().format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US)),
                    reservationState(reservation) + " · " + Ui.php(reservation.cost())));
        }
        for (AccountStore.Transaction transaction : data.transactions()) if (isVenueTransaction(transaction)) entries.add(transactionRow(transaction));
        rows.getChildren().addAll(entries);
    }

    private HBox recordRow(String kind, String title, String date, String detail) {
        Label icon = new Label("▣");
        icon.getStyleClass().add("transaction-row-icon");
        VBox description = new VBox(3, new Label(title), muted(date));
        HBox content = new HBox(12, icon, description, Ui.grow(), muted(detail));
        content.setAlignment(Pos.CENTER_LEFT);
        content.getStyleClass().add("transaction-record-row");
        content.setPadding(new Insets(12, 0, 12, 0));
        content.setUserData(kind);
        return content;
    }

    private void showTransactionDetails(AccountStore.Transaction transaction, String label) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Transaction details");
        if (scroll.getScene() != null) dialog.initOwner(scroll.getScene().getWindow());
        VBox content = new VBox(12);
        content.getStyleClass().add("transaction-details");
        content.getChildren().add(new HBox(8, BackNavigation.button(dialog::close), Ui.muted("Wallet  ›  Transaction Details")));
        content.getChildren().addAll(Ui.title(label), Ui.details(
                "Transaction reference", reference(transaction.type()),
                "Transaction type", label,
                "Amount", (isCredit(transaction) ? "+ " : "− ") + peso(transaction.amount().abs()),
                "Date and time", transaction.timestamp().format(WHEN),
                "Related event or reservation", CustomerWorkflowService.isFunding(transaction)
                        ? "Not linked to an event or reservation." : transactionDetails(transaction),
                "Record status", "Recorded in wallet",
                "Balance after", peso(transaction.balanceAfter())));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().clear();
        dialog.getDialogPane().getStyleClass().add("transaction-details-dialog");
        dialog.show();
    }

    private String transactionDetails(AccountStore.Transaction transaction) {
        try { return shell.services().workflow().transactionDetails(transaction); }
        catch (Exception ignored) { return "Not available in the saved record."; }
    }

    private String reference(String type) {
        int separator = type.indexOf(':');
        return separator < 0 ? "Not recorded" : type.substring(separator + 1);
    }

    private Event linkedEvent(VenueReservation reservation) {
        if (reservation.eventId().isBlank()) return null;
        return data.events().stream().filter(event -> event.id().equals(reservation.eventId())).findFirst().orElse(null);
    }

    private String reservationState(VenueReservation reservation) {
        String approval = reservation.status() == drawn.model.event.BookingStatus.PENDING
                ? "Pending admin approval"
                : CustomerText.reservationStatus(reservation, linkedEvent(reservation));
        return approval + " · " + reservation.paymentStatus().name();
    }

    private void showAddFunds() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Add Funds");
        if (scroll.getScene() != null) dialog.initOwner(scroll.getScene().getWindow());
        ButtonType confirmType = new ButtonType("Confirm Top-up", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(confirmType);
        TextField amount = Ui.field("Amount in PHP");
        amount.setId("topup-amount");
        Label preview = Ui.muted("Enter an amount to preview the top-up.");
        Label error = Ui.errorLabel();
        Button confirm = (Button) dialog.getDialogPane().lookupButton(confirmType);
        confirm.setId("topup-confirm");
        confirm.getStyleClass().add("primary-button");
        amount.textProperty().addListener((observable, oldValue, value) -> {
            try { preview.setText("You are adding " + peso(Ui.amount(value, "Top-up amount")) + " to your wallet."); }
            catch (RuntimeException ex) { preview.setText("Enter an amount greater than PHP 0.00."); }
        });
        confirm.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            error.setText("");
            confirm.setDisable(true);
            try {
                BigDecimal value = Ui.amount(amount.getText(), "Top-up amount");
                if (value.signum() <= 0) throw new IllegalArgumentException("Top-up amount must be greater than PHP 0.00.");
                shell.services().accounts().topUp(shell.session().account(), value);
                dialog.setResult(confirmType);
                dialog.close();
                Ui.info("Top-up successful.", "New wallet balance: " + peso(shell.session().account().getWalletBalance()));
                refresh();
            } catch (Exception ex) {
                error.setText(Ui.message(ex));
                confirm.setDisable(false);
            }
        });
        VBox content = new VBox(14, new HBox(8, BackNavigation.button(dialog::close), Ui.muted("Wallet  ›  Add Funds")),
                Ui.title("Add Funds"),
                Ui.muted("Top up your DrawN wallet using simulated funds."), amount,
                Ui.muted("Minimum: PHP 0.01 · Maximum: PHP 100,000.00"), preview, error);
        content.setPadding(new Insets(8));
        content.setPrefWidth(390);
        content.getStyleClass().add("add-funds-dialog");
        dialog.getDialogPane().setContent(content);
        dialog.show();
    }

    private boolean isCredit(AccountStore.Transaction transaction) {
        String type = transaction.type();
        return CustomerWorkflowService.isFunding(transaction) || type.startsWith("REFUND:") || type.startsWith("CREDIT:")
                || transaction.amount().signum() < 0;
    }

    private boolean isPayment(AccountStore.Transaction transaction) {
        return transaction.type().startsWith("PAYMENT:");
    }

    private boolean isEventTransaction(AccountStore.Transaction transaction) {
        return !isVenueTransaction(transaction) && (isPayment(transaction)
                || transaction.type().startsWith("REFUND:") || transaction.type().startsWith("CREDIT:"));
    }

    private boolean isVenueTransaction(AccountStore.Transaction transaction) {
        return transaction.type().startsWith("PAYMENT:venue:") || transaction.type().startsWith("REFUND:venue:");
    }

    private String emptyMessage(String category) {
        if (!transactionLoadError.isBlank()) return transactionLoadError;
        return switch (category) {
            case "Top-ups" -> "No top-ups or initial wallet funding found.";
            case "Event Transactions" -> "No event-related payments, refunds, or fees found.";
            case "Event Records" -> "No event records found.";
            default -> "No pending reservations or reservation-related wallet activity found.";
        };
    }

    private static String readableType(String type) {
        String[] words = type.replace(':', ' ').replace('_', ' ').toLowerCase(Locale.ROOT).split("\\s+");
        StringBuilder readable = new StringBuilder();
        for (String word : words) {
            if (word.isBlank()) continue;
            if (!readable.isEmpty()) readable.append(' ');
            readable.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return readable.toString();
    }

    private static String peso(BigDecimal amount) { return "₱" + PESO.format(amount == null ? BigDecimal.ZERO : amount); }
    private static BigDecimal sum(List<AccountStore.Transaction> transactions) {
        return transactions.stream().map(t -> t.amount().abs()).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void updateResponsiveWidths(double width) {
        if (width <= 0) return;
        Node overviewNode = pageBody.getChildren().size() > 1 ? pageBody.getChildren().get(1) : null;
        if (overviewNode instanceof FlowPane overview && overview.getChildren().size() == 2) {
            double cardWidth = width < 640 ? width - 56 : (width - 68) / 2;
            overview.getChildren().forEach(node -> { if (node instanceof Region region) region.setPrefWidth(cardWidth); });
        }
        Node summaryNode = pageBody.getChildren().size() > 2 ? pageBody.getChildren().get(2) : null;
        if (summaryNode instanceof FlowPane summaries) {
            double cardWidth = width >= 980 ? (width - 80) / 3 : width >= 560 ? (width - 68) / 2 : width - 56;
            summaries.getChildren().forEach(node -> { if (node instanceof Region region) region.setPrefWidth(cardWidth); });
        }
    }

    private static Label muted(String text) { return Ui.muted(text); }

    private record WalletData(List<AccountStore.Transaction> transactions, List<Event> events,
                              List<VenueReservation> reservations) {
        private WalletData {
            transactions = List.copyOf(transactions);
            events = List.copyOf(events);
            reservations = List.copyOf(reservations);
        }
        static WalletData empty() { return new WalletData(List.of(), List.of(), List.of()); }
    }
}
