package drawn.storage;

import drawn.model.user.ContactMethod;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** CSV-backed customer accounts and simulated wallet transactions. */
public final class AccountStore {
    private static final String ACCOUNT_HEADER = "id,firstName,lastName,dateOfBirth,email,phone,contactMethod,password,walletBalance";
    private static final String TRANSACTION_HEADER = "accountId,type,amount,balanceAfter,timestamp";
    private static final String WALLET_HEADER = "operationId,accountId,type,amount,balanceBefore,balanceAfter,timestamp";

    private final Path accountsFile;
    private final Path transactionsFile;
    private final Path walletLedgerFile;

    public AccountStore(Path dataDirectory) {
        this.accountsFile = dataDirectory.resolve("accounts.csv");
        this.transactionsFile = dataDirectory.resolve("wallet-transactions.csv");
        this.walletLedgerFile = dataDirectory.resolve("wallet-ledger.csv");
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
        recoverWalletLedger(result);
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
        appendWallet("register:" + account.getId(), account.getId(), "INITIAL_FUNDING", initialAmount,
                BigDecimal.ZERO, initialAmount);
        accounts.add(account); writeAccountsAtomic(accounts);
        appendHistory(account.getId(), "INITIAL_FUNDING", initialAmount, initialAmount);
    }

    public synchronized void topUp(Account account, BigDecimal amount) throws IOException {
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2
                || amount.compareTo(new BigDecimal("100000.00")) > 0) throw new IllegalArgumentException("Invalid top-up amount.");
        String operationId = "topup:" + java.util.UUID.randomUUID();
        List<Account> accounts = loadAccounts();
        Account stored = accounts.stream().filter(item -> item.getId().equals(account.getId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Account not found."));
        BigDecimal newBalance = stored.getWalletBalance().add(amount);
        appendWallet(operationId, account.getId(), "TOP_UP", amount, stored.getWalletBalance(), newBalance);
        stored.setWalletBalance(newBalance); writeAccountsAtomic(accounts);
        appendHistory(account.getId(), "TOP_UP", amount, newBalance); account.setWalletBalance(newBalance);
    }

    public synchronized void charge(Account account, BigDecimal amount, String reference) throws IOException {
        changeBalance(account, amount, reference, false);
    }

    public synchronized void refund(Account account, BigDecimal amount, String reference) throws IOException {
        changeBalance(account, amount, reference, true);
    }

    public synchronized boolean hasWalletTransaction(String accountId, String type) throws IOException {
        List<String> rows = readLines(transactionsFile);
        for (int i = 1; i < rows.size(); i++) {
            if (rows.get(i).isBlank()) continue;
            List<String> fields;
            try { fields=parseCsv(rows.get(i)); } catch(IOException ex){if(i==rows.size()-1)continue;throw ex;}
            if (fields.size() == 5 && fields.get(0).equals(accountId) && fields.get(1).equals(type)) return true;
        }
        return false;
    }

    public synchronized Account findAccount(String accountId) throws IOException {
        return loadAccounts().stream().filter(a -> a.getId().equals(accountId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Account not found."));
    }

    private void changeBalance(Account account, BigDecimal amount, String reference, boolean refund) throws IOException {
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2 || reference == null || reference.isBlank())
            throw new IllegalArgumentException("Invalid wallet transaction.");
        String transactionType = (refund ? "REFUND:" : "PAYMENT:") + reference;
        List<Account> storedAccounts = loadAccounts();
        Account stored = storedAccounts.stream().filter(item -> item.getId().equals(account.getId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Account not found."));
        String operationId = transactionType + ":" + account.getId();
        if (walletOperationExists(operationId)) {
            if (!hasWalletTransaction(account.getId(), transactionType)) appendHistory(account.getId(), transactionType, amount, stored.getWalletBalance());
            account.setWalletBalance(stored.getWalletBalance()); return;
        }
        BigDecimal balance = stored.getWalletBalance();
        BigDecimal next;
        if (refund) next = balance.add(amount);
        else {
            if (balance.compareTo(amount) < 0) throw new IllegalArgumentException("Insufficient wallet balance.");
            next = balance.subtract(amount);
        }
        appendWallet(operationId, account.getId(), transactionType, amount, balance, next);
        stored.setWalletBalance(next); writeAccountsAtomic(storedAccounts);
        appendHistory(account.getId(), transactionType, amount, next); account.setWalletBalance(next);
    }

    public synchronized List<Transaction> loadTransactions(String accountId) throws IOException {
        List<String> lines = readLines(transactionsFile);
        List<Transaction> result = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            List<String> fields;
            try { fields=parseCsv(lines.get(i)); } catch(IOException ex){if(i==lines.size()-1)continue;throw ex;}
            if (fields.size() != 5) { if(i==lines.size()-1)continue; throw new IOException("Invalid wallet transaction record."); }
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

    private void writeAccountsAtomic(List<Account> accounts) throws IOException {
        List<String> lines = new ArrayList<>(); lines.add(ACCOUNT_HEADER);
        for (Account a : accounts) lines.add(csv(a.getId(), a.getFirstName(), a.getLastName(), a.getDateOfBirth(),
                a.getEmail(), a.getPhone(), a.getContactMethod().name(), a.getPassword(), a.getWalletBalance().toPlainString()));
        Files.createDirectories(accountsFile.getParent());
        Path tmp = accountsFile.resolveSibling(accountsFile.getFileName() + ".tmp");
        Files.write(tmp, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try { Files.move(tmp, accountsFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (IOException atomicFailure) {
            try { Files.move(tmp, accountsFile, StandardCopyOption.REPLACE_EXISTING); }
            catch (IOException replacementFailure) {
                try { Files.write(accountsFile, lines, StandardCharsets.UTF_8); Files.deleteIfExists(tmp); }
                catch (IOException directFailure) { directFailure.addSuppressed(atomicFailure); directFailure.addSuppressed(replacementFailure); throw directFailure; }
            }
        }
    }

    private void recoverWalletLedger(List<Account> accounts) throws IOException {
        List<String> rows;
        if (!Files.exists(walletLedgerFile)) {
            // Existing snapshots are the migration baseline; subsequent balances come only from this ledger.
            rows = new ArrayList<>(); rows.add(WALLET_HEADER);
            for (Account account : accounts) rows.add(csv("seed:" + account.getId(), account.getId(), "SEED",
                    account.getWalletBalance().toPlainString(), "0", account.getWalletBalance().toPlainString(), LocalDateTime.now().toString()));
            if (!accounts.isEmpty()) writeLines(walletLedgerFile, rows);
        }
        if (!Files.exists(walletLedgerFile)) return;
        rows = Files.readAllLines(walletLedgerFile, StandardCharsets.UTF_8);
        if (rows.isEmpty() || !rows.get(0).equals(WALLET_HEADER)) throw new IOException("Unrecognized wallet ledger header.");
        java.util.Map<String, BigDecimal> latest = new java.util.HashMap<>();
        for (int i=1;i<rows.size();i++) { if(rows.get(i).isBlank())continue; List<String> f;
            try { f=parseCsv(rows.get(i)); } catch(IOException ex){if(i==rows.size()-1)continue;throw ex;}
            if(f.size()!=7){if(i==rows.size()-1)continue;throw new IOException("Invalid wallet ledger entry.");}
            try {
                if(f.get(0).isBlank()||f.get(1).isBlank())throw new IllegalArgumentException();
                new BigDecimal(f.get(3));new BigDecimal(f.get(4));
                latest.put(f.get(1), new BigDecimal(f.get(5)));LocalDateTime.parse(f.get(6));
            } catch(RuntimeException ex){if(i==rows.size()-1)continue;throw new IOException("Invalid wallet ledger entry.",ex);}
        }
        boolean changed=false;
        for(Account account:accounts) if(latest.containsKey(account.getId()) && account.getWalletBalance().compareTo(latest.get(account.getId()))!=0){account.setWalletBalance(latest.get(account.getId()));changed=true;}
        if(changed) writeAccountsAtomic(accounts);
    }

    private void appendWallet(String operationId, String accountId, String type, BigDecimal amount,
                              BigDecimal before, BigDecimal after) throws IOException {
        if(walletOperationExists(operationId))return;
        Files.createDirectories(walletLedgerFile.getParent());
        if(!Files.exists(walletLedgerFile))Files.writeString(walletLedgerFile,WALLET_HEADER+System.lineSeparator(),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        else if(!Files.readString(walletLedgerFile,StandardCharsets.UTF_8).endsWith("\n"))Files.writeString(walletLedgerFile,System.lineSeparator(),StandardCharsets.UTF_8,StandardOpenOption.APPEND);
        String row=csv(operationId,accountId,type,amount.toPlainString(),before.toPlainString(),after.toPlainString(),LocalDateTime.now().toString())+System.lineSeparator();
        try(var channel=java.nio.channels.FileChannel.open(walletLedgerFile,StandardOpenOption.WRITE,StandardOpenOption.APPEND)){
            channel.write(StandardCharsets.UTF_8.encode(row)); channel.force(true);
        }
    }
    private boolean walletOperationExists(String operationId) throws IOException {
        if(!Files.exists(walletLedgerFile))return false;
        List<String> rows=Files.readAllLines(walletLedgerFile,StandardCharsets.UTF_8);
        for(int i=0;i<rows.size();i++){String row=rows.get(i);if(row.isBlank()||row.equals(WALLET_HEADER))continue;try{
            List<String> f=parseCsv(row);if(f.size()!=7)continue;
            new BigDecimal(f.get(3));new BigDecimal(f.get(4));new BigDecimal(f.get(5));LocalDateTime.parse(f.get(6));
            if(f.get(0).equals(operationId))return true;
        }catch(IOException|RuntimeException ex){if(i!=rows.size()-1){if(ex instanceof IOException io)throw io;throw new IOException("Invalid wallet ledger entry.",ex);}}}return false;
    }
    private void appendHistory(String accountId,String type,BigDecimal amount,BigDecimal after)throws IOException{
        Files.createDirectories(transactionsFile.getParent());
        if(!Files.exists(transactionsFile))Files.writeString(transactionsFile,TRANSACTION_HEADER+System.lineSeparator(),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        else if(!Files.readString(transactionsFile,StandardCharsets.UTF_8).endsWith("\n"))Files.writeString(transactionsFile,System.lineSeparator(),StandardCharsets.UTF_8,StandardOpenOption.APPEND);
        String row=csv(accountId,type,amount.toPlainString(),after.toPlainString(),LocalDateTime.now().toString())+System.lineSeparator();
        try(var channel=java.nio.channels.FileChannel.open(transactionsFile,StandardOpenOption.WRITE,StandardOpenOption.APPEND)){
            channel.write(StandardCharsets.UTF_8.encode(row));channel.force(true);
        }
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
