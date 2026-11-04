package drawn;

import drawn.model.user.ContactMethod;
import drawn.model.user.Customer;
import drawn.model.user.OrganizerLevel;
import drawn.storage.AccountStore;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;

/** Dependency-free checks for registration profile and persisted wallet behavior. */
public final class RegistrationWalletTest {
    private RegistrationWalletTest() { }

    public static void main(String[] args) throws Exception {
        LocalDate today = LocalDate.now();
        assert new Customer("c1", "First Person", ContactMethod.EMAIL, "first@example.test",
                today.minusYears(16)).getOrganizerLevel() == OrganizerLevel.BASIC;
        assert new Customer("c2", "Second Person", ContactMethod.EMAIL, "second@example.test",
                today.minusYears(18)).getOrganizerLevel() == OrganizerLevel.INTERMEDIATE;
        assert new Customer("c3", "Third Person", ContactMethod.PHONE, "+639123456789",
                today.minusYears(21)).getOrganizerLevel() == OrganizerLevel.ADVANCED;
        expectIllegalArgument(() -> new Customer("c4", "Young Person", ContactMethod.EMAIL,
                "young@example.test", today.minusYears(15)));
        expectIllegalArgument(() -> new Customer("c5", "Future Person", ContactMethod.EMAIL,
                "future@example.test", today.plusDays(1)));

        Path directory = Files.createTempDirectory("drawn-account-test-");
        try {
            AccountStore store = new AccountStore(directory);
            AccountStore.Account email = account("email-id", "Email", "User", "email@example.test", null,
                    ContactMethod.EMAIL, "plainPassword", "0.00");
            expectIllegalArgument(() -> registerWithAmount(store, email, new BigDecimal("499.99")));
            assert store.loadAccounts().isEmpty();
            store.register(email, new BigDecimal("500.00"));
            expectIllegalArgument(() -> register(store, account("duplicate-email", "Other", "Person",
                    "EMAIL@example.test", null, ContactMethod.EMAIL, "other", "0.00")));

            AccountStore.Account phone = account("phone-id", "Same", "Name", null, "+639123456789",
                    ContactMethod.PHONE, "differentPassword", "0.00");
            store.register(phone, new BigDecimal("500.00"));
            expectIllegalArgument(() -> register(store, account("duplicate-phone", "Another", "Person",
                    null, "+639123456789", ContactMethod.PHONE, "other", "0.00")));

            AccountStore reloaded = new AccountStore(directory);
            var loaded = reloaded.loadAccounts();
            assert loaded.size() == 2;
            assert loaded.get(0).getPassword().equals("plainPassword");
            assert loaded.get(0).getPhone().isEmpty();
            assert loaded.get(1).getEmail().isEmpty();
            assert loaded.get(1).getPhone().equals("+639123456789");

            reloaded.topUp(loaded.get(0), new BigDecimal("25.50"));
            AccountStore afterRestart = new AccountStore(directory);
            var persisted = afterRestart.loadAccounts().get(0);
            assert persisted.getWalletBalance().compareTo(new BigDecimal("525.50")) == 0;
            var history = afterRestart.loadTransactions("email-id");
            assert history.size() == 2;
            assert history.get(0).type().equals("INITIAL_FUNDING");
            assert history.get(1).type().equals("TOP_UP");
            expectIllegalArgument(() -> topUp(afterRestart, persisted, new BigDecimal("-1.00")));
        } finally {
            try (var paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ex) { throw new RuntimeException(ex); }
                });
            }
        }
        System.out.println("RegistrationWalletTest passed.");
    }

    private static AccountStore.Account account(String id, String first, String last, String email, String phone,
                                                 ContactMethod method, String password, String balance) {
        return new AccountStore.Account(id, first, last, LocalDate.now().minusYears(21).toString(), email,
                phone, method, password, new BigDecimal(balance));
    }

    private static void register(AccountStore store, AccountStore.Account account) {
        try { store.register(account, new BigDecimal("500.00")); }
        catch (java.io.IOException ex) { throw new RuntimeException(ex); }
    }

    private static void registerWithAmount(AccountStore store, AccountStore.Account account, BigDecimal amount) {
        try { store.register(account, amount); }
        catch (java.io.IOException ex) { throw new RuntimeException(ex); }
    }

    private static void topUp(AccountStore store, AccountStore.Account account, BigDecimal amount) {
        try { store.topUp(account, amount); }
        catch (java.io.IOException ex) { throw new RuntimeException(ex); }
    }

    private static void expectIllegalArgument(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected validation failure.
        }
    }
}
