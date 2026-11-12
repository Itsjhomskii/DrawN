package drawn;

import drawn.model.event.Event;

/** Shared dependency for customer console feature modules. */
abstract class CustomerConsoleModule {
    protected final DrawN host;

    CustomerConsoleModule(DrawN host) {
        this.host = host;
    }

    void printBuyerEventDetails(Event event) {
        System.out.println("\nEvent details");
        System.out.println("  Name: " + event.name());
        System.out.println("  Type: " + event.type());
        System.out.println("  Date: " + event.date());
        System.out.println("  Time: " + event.startTime() + " - " + event.endTime());
        System.out.println("  Venue: " + event.venue().name());
        System.out.println("  Description: " + event.description());
        System.out.println("----------------------------------------");
    }
}
