package drawn.model.user;

import java.time.LocalDate;
import java.time.Period;

/** A customer who can create event bookings and ticket reservations. */
public final class Customer extends User {
    private final LocalDate dateOfBirth;
    private final ContactMethod contactMethod;
    private final String phoneNumber;

    public Customer(String id, String username, String name, String email) {
        super(id, username, name, email);
        this.dateOfBirth = null;
        this.contactMethod = email == null ? null : ContactMethod.EMAIL;
        this.phoneNumber = null;
    }

    public Customer(String id, String name, String email, LocalDate dateOfBirth) {
        super(id, id, name, email);
        if (dateOfBirth == null || dateOfBirth.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("Date of birth must be a valid date in the past");
        }
        if (Period.between(dateOfBirth, LocalDate.now()).getYears() < 16) {
            throw new IllegalArgumentException("Customers must be at least 16 years old");
        }
        this.dateOfBirth = dateOfBirth;
        this.contactMethod = ContactMethod.EMAIL;
        this.phoneNumber = null;
    }

    public Customer(String id, String name, ContactMethod contactMethod, String contactValue,
                    LocalDate dateOfBirth) {
        super(id, id, name, contactMethod == ContactMethod.EMAIL ? contactValue : null, true);
        if (contactMethod == null || contactValue == null || contactValue.isBlank()) {
            throw new IllegalArgumentException("A contact method and contact value are required");
        }
        if (dateOfBirth == null || dateOfBirth.isAfter(LocalDate.now())
                || Period.between(dateOfBirth, LocalDate.now()).getYears() < 16) {
            throw new IllegalArgumentException("Customer must have a valid date of birth and be at least 16");
        }
        this.dateOfBirth = dateOfBirth;
        this.contactMethod = contactMethod;
        this.phoneNumber = contactMethod == ContactMethod.PHONE ? contactValue : null;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public ContactMethod getContactMethod() {
        return contactMethod;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public int getAge() {
        if (dateOfBirth == null) {
            throw new IllegalStateException("Date of birth is not available for this customer");
        }
        return Period.between(dateOfBirth, LocalDate.now()).getYears();
    }

    public OrganizerLevel getOrganizerLevel() {
        int age = getAge();
        if (age < 18) return OrganizerLevel.BASIC;
        if (age < 21) return OrganizerLevel.INTERMEDIATE;
        return OrganizerLevel.ADVANCED;
    }

    @Override
    public String dashboardLabel() {
        return "Customer Dashboard";
    }
}
