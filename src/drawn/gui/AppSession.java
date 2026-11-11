package drawn.gui;

import drawn.model.user.Customer;
import drawn.storage.AccountStore;

/** The authenticated customer for the current JavaFX stage only. */
public final class AppSession {
    private AccountStore.Account account;
    private Customer customer;

    public void start(AccountStore.Account account, Customer customer) {
        this.account = account;
        this.customer = customer;
    }

    public void clear() { account = null; customer = null; }
    public boolean isAuthenticated() { return account != null && customer != null; }
    public AccountStore.Account account() { return account; }
    public Customer customer() { return customer; }
}
