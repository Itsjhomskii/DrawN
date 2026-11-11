# DrawN — Event Management & Ticketing System

**Tagline:** From drawing to doing.

DrawN is a Java event listing and ticketing project with a preserved console workflow and a JavaFX customer GUI foundation.

## Development status

The console app supports customer registration by email or Philippine phone number, contact-based login, customer event and venue requests, wallet payments, optional event publishing, and ticket sales. The customer menu includes profile, event, venue, ticket, organizer, and wallet features. New workflow data is saved additively under `data/` as CSV files; existing account, wallet, and event-submission CSV formats remain readable. Reservation requests stay pending until the admin-side code calls `ReservationService.approve` or `ReservationService.decline`; `pendingRequests` supplies the review queue. The admin dashboard is unchanged. The existing admin-created event list and simple ticket reservation flow remain available in memory for the current run. Payments are simulated through the customer wallet.

Demo admin login: `admin` / `234`. Customer accounts are created through registration. Customer passwords are currently stored as entered in `data/accounts.csv`.

## Requirements

- Java 17 or later (the included Maven build is verified with JDK 21)
- Maven 3.9+ for JavaFX dependencies
- No database or external service is used; persistence remains CSV-based

## Compile and run

Run the console backup from `run.bat`, or start the JavaFX customer interface with `run-gui.bat`.

From PowerShell:

```powershell
mvn compile exec:java -Dexec.mainClass=drawn.DrawN
mvn javafx:run
```

Compile and run the existing assertion checks from PowerShell:

```powershell
mvn test-compile
java -ea -cp target/classes;target/test-classes drawn.UserModelTest
java -ea -cp target/classes;target/test-classes drawn.RegistrationWalletTest
java -ea -cp target/classes;target/test-classes drawn.service.event.EventServiceTest
java -ea -cp target/classes;target/test-classes drawn.service.event.Phase34WorkflowTest
java -ea -cp target/classes;target/test-classes drawn.service.event.CustomerRecoveryAndEarningsTest
```

The organizer commission defaults to 10%. Set the Java system property `drawn.commission.percent` to a value from 0 to 100 to change it, for example `java -Ddrawn.commission.percent=12.5 -cp out drawn.DrawN`.
