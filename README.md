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

## JavaFX GUI

`run-gui.bat` (or `mvn javafx:run`) opens the GUI. It covers every console workflow:

| GUI screen | Console menu it replaces |
|---|---|
| Log in / Register (admin logs in with `admin` / `234`) | Welcome menu |
| Home: account overview and published events | Customer dashboard |
| Explore Events: public list, private keyword, buy tickets | Events & Venues → Explore Events |
| My Events: details, cancel event | My Event Plans & Venue Requests |
| Create & Publish Event | Create & Publish Event (Approved Venue) |
| Venue Reservations / Request a Venue: quote, pay, cancel | Manage Venue Requests |
| My Tickets: buy, cancel with fee summary | Tickets |
| Wallet & Transactions: top-up, history, records | Wallet & Transactions |
| My Profile (sidebar profile menu) | My Profile |
| Admin Dashboard: list and add events | Admin Dashboard |

The GUI lives in `src/drawn/gui` and only calls existing services. Workflow steps that used to be private to `DrawN.java` now live in `drawn.service.customer.CustomerWorkflowService` and `drawn.service.admin.AdminService`, so the console and the GUI share one copy of the rules.

The GUI smoke test clicks through every screen against a temporary data folder (your `data/` is not touched). Pass a folder to also save a screenshot of each page:

```powershell
mvn test-compile
java -ea --module-path "$env:USERPROFILE\.m2\repository\org\openjfx\javafx-base\21.0.2\javafx-base-21.0.2-win.jar;$env:USERPROFILE\.m2\repository\org\openjfx\javafx-graphics\21.0.2\javafx-graphics-21.0.2-win.jar;$env:USERPROFILE\.m2\repository\org\openjfx\javafx-controls\21.0.2\javafx-controls-21.0.2-win.jar" --add-modules javafx.controls -cp "target/classes;target/test-classes" drawn.gui.GuiSmokeTest screenshots
```

The organizer commission defaults to 10%. Set the Java system property `drawn.commission.percent` to a value from 0 to 100 to change it, for example `java -Ddrawn.commission.percent=12.5 -cp out drawn.DrawN`.
