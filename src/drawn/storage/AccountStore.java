package drawn.storage;

import drawn.model.user.ContactMethod;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** CSV-backed customer accounts and simulated wallet transactions. */
public final class AccountStore {
    private static final String ACCOUNT_HEADER = "id,firstName,lastName,dateOfBirth,email,phone,contactMethod,password,walletBalance";
    private static final String TRANSACTION_HEADER = "accountId,type,amount,balanceAfter,timestamp";

    private final Path accountsFile;
    private final Path transactionsFile;

    public AccountStore(Path dataDirectory) {
        this.accountsFile = dataDirectory.resolve("accounts.csv");
        this.transactionsFile = dataDirectory.resolve("wallet-transactions.csv");
    }

    public synchronized List<Account> loadAccounts() throws IOException {
        List<String> lines = readLines(accountsFile);
        List<Account> result = new ArrayList<>();
        if (lines.isEmpty()) return result;
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            List<String> fields = parseCsv(lines.get(i));
            if (fields.size() != 9) throw new IOException("Invalid customer record in " + accountsFile);
            try {
                result.add(new Account(fields.get(0), fields.get(1), fields.get(2), fields.get(3),
                        fields.get(4), fields.get(5), ContactMethod.valueOf(fields.get(6)), fields.get(7),
                        new BigDecimal(fields.get(8))));
            } catch (RuntimeException ex) {
                throw new IOException("Invalid customer record in " + accountsFile, ex);
            }
        }
        return result;
    }

    public synchronized void register(Account account, BigDecimal initialAmount) throws IOException {
        if (initialAmount == null || initialAmount.compareTo(new BigDecimal("500.00")) < 0
                || initialAmount.scale() > 2) {
            throw new IllegalArgumentException("Minimum initial funding is PHP 500.00.");
        }
        List<Account> accounts = loadAccounts();
        for (Account existing : accounts) {
            if (existing.getContactMethod() == account.getContactMethod()
                    && existing.getContactValue().equalsIgnoreCase(account.getContactValue())) {
                throw new IllegalArgumentException("That contact is already registered.");
            }
        }
        for (Account existing : accounts) {
            if (existing.getId().equals(account.getId())) {
                throw new IllegalArgumentException("Duplicate account ID.");
            }
        }
        account.setWalletBalance(initialAmount);
        List<String> oldAccounts = readLines(accountsFile);
        List<String> oldTransactions = readLines(transactionsFile);
        try {
            accounts.add(account);
            writeAccounts(accounts);
            List<String> transactions = new ArrayList<>(oldTransactions);
            if (transactions.isEmpty()) transactions.add(TRANSACTION_HEADER);
            transactions.add(csv(account.getId(), "INITIAL_FUNDING", initialAmount.toPlainString(),
                    initialAmount.toPlainString(), LocalDateTime.now().toString()));
            writeLines(transactionsFile, transactions);
        } catch (IOException ex) {
            restore(accountsFile, oldAccounts);
            restore(transactionsFile, oldTransactions);
            throw ex;
        }
    }

    public synchronized void topUp(Account account, BigDecimal amount) throws IOException {
        if (amount.signum() < 0 || amount.scale() > 2) throw new IllegalArgumentException("Invalid top-up amount.");
        List<Account> accounts = loadAccounts();
        Account stored = accounts.stream().filter(item -> item.getId().equals(account.getId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Account not found."));
        BigDecimal newBalance = stored.getWalletBalance().add(amount);
        List<String> oldAccounts = readLines(accountsFile);
        List<String> oldTransactions = readLines(transactionsFile);
        try {
            stored.setWalletBalance(newBalance);
            writeAccounts(accounts);
            List<String> transactions = new ArrayList<>(oldTransactions);
            if (transactions.isEmpty()) transactions.add(TRANSACTION_HEADER);
            transactions.add(csv(account.getId(), "TOP_UP", amount.toPlainString(), newBalance.toPlainString(),
                    LocalDateTime.now().toString()));
            writeLines(transactionsFile, transactions);
            account.setWalletBalance(newBalance);
        } catch (IOException ex) {
            restore(accountsFile, oldAccounts);
            restore(transactionsFile, oldTransactions);
            throw ex;
        }
    }

    public synchronized void charge(Account account, BigDecimal amount, String reference) throws IOException {
        changeBalance(account, amount, reference, false);
    }

    public synchronized void refund(Account account, BigDecimal amount, String reference) throws IOException {
        changeBalance(account, amount, reference, true);
    }

    private void changeBalance(Account account, BigDecimal amount, String reference, boolean refund) throws IOException {
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2 || reference == null || reference.isBlank())
            throw new IllegalArgumentException("Invalid wallet transaction.");
        List<String> oldTransactions = readLines(transactionsFile);
        String transactionType = (refund ? "REFUND:" : "PAYMENT:") + reference;
        for (int i = 1; i < oldTransactions.size(); i++) {
            if (oldTransactions.get(i).isBlank()) continue;
            List<String> fields = parseCsv(oldTransactions.get(i));
            if (fields.size() == 5 && fields.get(0).equals(account.getId()) && fields.get(1).equals(transactionType))
                throw new IllegalArgumentException("Duplicate wallet transaction.");
        }
        List<Account> storedAccounts = loadAccounts();
        Account stored = storedAccounts.stream().filter(item -> item.getId().equals(account.getId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Account not found."));
        BigDecimal balance = stored.getWalletBalance();
        BigDecimal next;
        if (refund) next = balance.add(amount);
        else {
            if (balance.compareTo(amount) < 0) throw new IllegalArgumentException("Insufficient wallet balance.");
            next = balance.subtract(amount);
        }
        List<String> oldAccounts = readLines(accountsFile);
        try {
            stored.setWalletBalance(next);
            writeAccounts(storedAccounts);
            List<String> rows = new ArrayList<>(oldTransactions);
            if (rows.isEmpty()) rows.add(TRANSACTION_HEADER);
            rows.add(csv(account.getId(), transactionType, amount.toPlainString(), next.toPlainString(),
                    LocalDateTime.now().toString()));
            writeLines(transactionsFile, rows);
            account.setWalletBalance(next);
        } catch (IOException ex) {
            restore(accountsFile, oldAccounts);
            restore(transactionsFile, oldTransactions);
            throw ex;
        }
    }

    public synchronized List<Transaction> loadTransactions(String accountId) throws IOException {
        List<String> lines = readLines(transactionsFile);
        List<Transaction> result = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            List<String> fields = parseCsv(lines.get(i));
            if (fields.size() != 5) throw new IOException("Invalid wallet transaction record.");
            if (fields.get(0).equals(accountId)) {
                result.add(new Transaction(fields.get(1), new BigDecimal(fields.get(2)),
                        new BigDecimal(fields.get(3)), LocalDateTime.parse(fields.get(4))));
            }
        }
        return result;
    }

    private void writeAccounts(List<Account> accounts) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add(ACCOUNT_HEADER);
        for (Account a : accounts) {
            lines.add(csv(a.getId(), a.getFirstName(), a.getLastName(), a.getDateOfBirth(), a.getEmail(),
                    a.getPhone(), a.getContactMethod().name(), a.getPassword(), a.getWalletBalance().toPlainString()));
        }
        writeLines(accountsFile, lines);
    }

    private static List<String> readLines(Path file) throws IOException {
        if (!Files.exists(file)) return new ArrayList<>();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (!lines.isEmpty() && !lines.get(0).equals(ACCOUNT_HEADER)
                && !lines.get(0).equals(TRANSACTION_HEADER)) {
            throw new IOException("Unrecognized CSV header in " + file);
        }
        return lines;
    }

    private static void writeLines(Path file, List<String> lines) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    private static void restore(Path file, List<String> content) throws IOException {
        if (content.isEmpty()) Files.deleteIfExists(file);
        else writeLines(file, content);
    }

    private static String csv(String... fields) {
        List<String> escaped = new ArrayList<>(fields.length);
        for (String field : fields) {
            String value = field == null ? "" : field;
            escaped.add("\"" + value.replace("\"", "\"\"") + "\"");
        }
        return String.join(",", escaped);
    }

    private static List<String> parseCsv(String line) throws IOException {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted && ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                field.append('"'); i++;
            } else if (ch == '"') {
                quoted = !quoted;
            } else if (ch == ',' && !quoted) {
                fields.add(field.toString()); field.setLength(0);
            } else {
                field.append(ch);
            }
        }
        if (quoted) throw new IOException("Malformed CSV quoting.");
        fields.add(field.toString());
        return fields;
    }

    public static final class Account {
        private final String id;
        private final String firstName;
        private final String lastName;
        private final String dateOfBirth;
        private final String email;
        private final String phone;
        private final ContactMethod contactMethod;
        private final String password;
        private BigDecimal walletBalance;

        public Account(String id, String firstName, String lastName, String dateOfBirth, String email,
                       String phone, ContactMethod contactMethod, String password, BigDecimal walletBalance) {
            this.id = id; this.firstName = firstName; this.lastName = lastName; this.dateOfBirth = dateOfBirth;
            this.email = email; this.phone = phone; this.contactMethod = contactMethod; this.password = password;
            this.walletBalance = walletBalance;
        }
        public String getId() { return id; }
        public String getFirstName() { return firstName; }
        public String getLastName() { return lastName; }
        public String getFullName() { return firstName + " " + lastName; }
        public String getDateOfBirth() { return dateOfBirth; }
        public String getEmail() { return email; }
        public String getPhone() { return phone; }
        public ContactMethod getContactMethod() { return contactMethod; }
        public String getContactValue() { return contactMethod == ContactMethod.EMAIL ? email : phone; }
        public String getPassword() { return password; }
        public BigDecimal getWalletBalance() { return walletBalance; }
        public void setWalletBalance(BigDecimal balance) { walletBalance = balance; }
    }

    public record Transaction(String type, BigDecimal amount, BigDecimal balanceAfter, LocalDateTime timestamp) { }
}
