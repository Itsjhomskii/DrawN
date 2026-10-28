package drawn;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/** Console entry point for the DrawN event management and ticketing app. */
public final class DrawN {
    private static final String CUSTOMER_USERNAME = "jhomskii";
    private static final String CUSTOMER_PASSWORD = "123";
    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "234";

    private final Scanner input = new Scanner(System.in);
    private final List<Event> events = new ArrayList<>();

    private DrawN() {
    }

    public static void main(String[] args) {
        new DrawN().run();
    }

    private void run() {
        System.out.println("========================================");
        System.out.println("  DrawN - Event Management & Ticketing");
        System.out.println("========================================");

        boolean running = true;
        while (running) {
            System.out.println("\n--- DrawN Login ---");
            System.out.print("Username (or 0 to exit): ");
            String username = input.nextLine().trim();
            if (username.equals("0")) {
                running = false;
                continue;
            }

            System.out.print("Password: ");
            String password = input.nextLine();
            if (username.equals(CUSTOMER_USERNAME) && password.equals(CUSTOMER_PASSWORD)) {
                System.out.println("Welcome, Jhomari!");
                customerMenu();
            } else if (username.equals(ADMIN_USERNAME) && password.equals(ADMIN_PASSWORD)) {
                System.out.println("Welcome, admin!");
                adminMenu();
            } else {
                System.out.println("Incorrect username or password. Please try again.");
            }
        }

        System.out.println("Thanks for using DrawN. Goodbye!");
    }

    private void customerMenu() {
        boolean inMenu = true;
        while (inMenu) {
            System.out.println("\nCustomer dashboard");
            System.out.println("1. View available events");
            System.out.println("2. Reserve tickets");
            System.out.println("0. Log out");
            int choice = readInt("Choose an option: ", 0, 2);

            switch (choice) {
                case 1 -> listEvents();
                case 2 -> reserveTickets();
                case 0 -> inMenu = false;
                default -> throw new IllegalStateException("Unexpected menu option");
            }
        }
    }

    private void adminMenu() {
        boolean inMenu = true;
        while (inMenu) {
            System.out.println("\nAdmin dashboard");
            System.out.println("1. List events");
            System.out.println("2. Add event");
            System.out.println("0. Log out");
            int choice = readInt("Choose an option: ", 0, 2);

            switch (choice) {
                case 1 -> listEvents();
                case 2 -> addEvent();
                case 0 -> inMenu = false;
                default -> throw new IllegalStateException("Unexpected menu option");
            }
        }
    }

    private void addEvent() {
        System.out.println("\nAdd event");
        String name = readRequiredText("Event name: ");
        String date = readRequiredText("Date (for example, 2026-12-31): ");
        int capacity = readInt("Ticket capacity: ", 1, Integer.MAX_VALUE);
        BigDecimal price = readPrice("Ticket price (PHP): ");
        events.add(new Event(name, date, capacity, price));
        System.out.println("Event added.");
    }

    private void listEvents() {
        System.out.println("\nAvailable events");
        if (events.isEmpty()) {
            System.out.println("No events yet. Ask an admin to add one.");
            return;
        }

        for (int i = 0; i < events.size(); i++) {
            Event event = events.get(i);
            System.out.printf("%d. %s | %s | PHP %s | %d tickets left%n",
                    i + 1, event.name, event.date, event.price.toPlainString(), event.remaining);
        }
    }

    private void reserveTickets() {
        if (events.isEmpty()) {
            System.out.println("There are no events to reserve yet.");
            return;
        }

        listEvents();
        int eventNumber = readInt("Choose an event number (0 to cancel): ", 0, events.size());
        if (eventNumber == 0) {
            return;
        }

        Event event = events.get(eventNumber - 1);
        if (event.remaining == 0) {
            System.out.println("That event is sold out.");
            return;
        }

        String customerName = readRequiredText("Your name: ");
        int quantity = readInt("Number of tickets: ", 1, event.remaining);
        event.remaining -= quantity;
        BigDecimal total = event.price.multiply(BigDecimal.valueOf(quantity));
        System.out.printf("Reserved %d ticket(s) for %s. Total: PHP %s.%n",
                quantity, customerName, total.toPlainString());
        System.out.println("Payment verification is not available in this starter version.");
    }

    private String readRequiredText(String prompt) {
        while (true) {
            System.out.print(prompt);
            String value = input.nextLine().trim();
            if (!value.isEmpty()) {
                return value;
            }
            System.out.println("Please enter a value.");
        }
    }

    private int readInt(String prompt, int minimum, int maximum) {
        while (true) {
            System.out.print(prompt);
            String value = input.nextLine().trim();
            try {
                int number = Integer.parseInt(value);
                if (number >= minimum && number <= maximum) {
                    return number;
                }
            } catch (NumberFormatException ignored) {
                // Show the same helpful retry message for invalid text and out-of-range values.
            }
            System.out.printf("Enter a whole number from %d to %d.%n", minimum, maximum);
        }
    }

    private BigDecimal readPrice(String prompt) {
        while (true) {
            System.out.print(prompt);
            String value = input.nextLine().trim();
            try {
                BigDecimal price = new BigDecimal(value);
                if (price.signum() >= 0 && price.scale() <= 2) {
                    return price;
                }
            } catch (NumberFormatException ignored) {
                // Show the same helpful retry message for invalid text and invalid prices.
            }
            System.out.println("Enter a non-negative amount with up to 2 decimal places.");
        }
    }

    private static final class Event {
        private final String name;
        private final String date;
        private final BigDecimal price;
        private int remaining;

        private Event(String name, String date, int capacity, BigDecimal price) {
            this.name = name;
            this.date = date;
            this.price = price;
            this.remaining = capacity;
        }
    }
}
