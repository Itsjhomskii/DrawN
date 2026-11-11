package drawn.service.customer;

import drawn.model.user.ContactMethod;
import drawn.model.user.Customer;
import drawn.storage.AccountStore;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.util.Locale;
import java.util.Objects;

/** Shared customer authentication and registration adapter for console and GUI entry points. */
public final class CustomerAccessService {
    private static final BigDecimal MINIMUM_INITIAL_FUNDING = new BigDecimal("500.00");
    private final AccountStore accounts;

    public CustomerAccessService(AccountStore accounts) {
        this.accounts = Objects.requireNonNull(accounts, "Account store is required.");
    }

    public AuthenticatedCustomer authenticate(String contact, String password) throws IOException {
        if (password == null || password.isEmpty()) throw new IllegalArgumentException("Please enter your password.");
        String normalized = normalizeLoginContact(contact);
        AccountStore.Account account = accounts.loadAccounts().stream()
                .filter(candidate -> candidate.getContactValue().equalsIgnoreCase(normalized))
                .filter(candidate -> candidate.getPassword().equals(password))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Invalid login credentials."));
        return new AuthenticatedCustomer(account, customerFor(account));
    }

    public AuthenticatedCustomer register(String firstName, String lastName, LocalDate dateOfBirth,
                                          ContactMethod method, String contact, String password,
                                          BigDecimal initialFunding) throws IOException {
        requireName(firstName, "First name");
        requireName(lastName, "Last name");
        validateDateOfBirth(dateOfBirth);
        if (method == null) throw new IllegalArgumentException("Choose an email address or phone number.");
        if (password == null || password.isBlank()) throw new IllegalArgumentException("Password cannot be blank.");
        if (initialFunding == null || initialFunding.compareTo(MINIMUM_INITIAL_FUNDING) < 0 || initialFunding.scale() > 2)
            throw new IllegalArgumentException("Minimum initial funding is PHP 500.00.");

        String normalized = method == ContactMethod.EMAIL ? normalizeEmail(contact) : normalizePhone(contact);
        String email = method == ContactMethod.EMAIL ? normalized : "";
        String phone = method == ContactMethod.PHONE ? normalized : "";
        AccountStore.Account account = new AccountStore.Account(normalized, firstName.trim(), lastName.trim(),
                dateOfBirth.toString(), email, phone, method, password, initialFunding);
        accounts.register(account, initialFunding);
        return new AuthenticatedCustomer(account, customerFor(account));
    }

    public AccountStore.Account refreshAccount(String accountId) throws IOException {
        return accounts.findAccount(accountId);
    }

    public static String normalizeLoginContact(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Please enter your email address or phone number.");
        return value.contains("@") ? normalizeEmail(value) : normalizePhone(value);
    }

    public static String normalizeEmail(String value) {
        String email = value == null ? "" : value.trim();
        if (!email.matches("(?i)^[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9-]+(?:\\.[a-z0-9-]+)+$"))
            throw new IllegalArgumentException("Enter a valid email address.");
        return email.toLowerCase(Locale.ROOT);
    }

    public static String normalizePhone(String value) {
        String phone = value == null ? "" : value.trim();
        if (!phone.matches("\\d{10}")) throw new IllegalArgumentException("Enter exactly 10 phone digits.");
        return "+63" + phone;
    }

    private static Customer customerFor(AccountStore.Account account) {
        return new Customer(account.getId(), account.getFullName(), account.getContactMethod(),
                account.getContactValue(), LocalDate.parse(account.getDateOfBirth()));
    }

    private static void validateDateOfBirth(LocalDate dateOfBirth) {
        if (dateOfBirth == null || dateOfBirth.isAfter(LocalDate.now()))
            throw new IllegalArgumentException("Date of birth cannot be in the future.");
        if (Period.between(dateOfBirth, LocalDate.now()).getYears() < 16)
            throw new IllegalArgumentException("Customers must be at least 16 years old.");
    }

    private static void requireName(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " cannot be blank.");
    }

    public record AuthenticatedCustomer(AccountStore.Account account, Customer customer) { }
}
