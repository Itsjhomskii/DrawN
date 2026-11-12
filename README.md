# DrawN — Event Management & Ticketing System

**Tagline:** From drawing to doing.

DrawN is a Java event listing and ticketing project with a preserved console workflow and a JavaFX customer GUI foundation.

## Development status

The console app supports customer registration by email or Philippine phone number, contact-based login, customer event and venue requests, wallet payments, optional event publishing, and ticket sales. The customer menu includes profile, event, venue, ticket, organizer, and wallet features. New workflow data is saved additively under `data/` as CSV files; existing account, wallet, and event-submission CSV formats remain readable. Reservation requests stay pending until the admin-side code calls `ReservationService.approve` or `ReservationService.decline`; `pendingRequests` supplies the review queue. The admin dashboard is unchanged. The existing admin-created event list and simple ticket reservation flow remain available in memory for the current run. Payments are simulated through the customer wallet.

Admin login: `admin@drawn.com` / `admin`. Use the main login page for both admins and customers. Customer accounts are created through registration. Customer passwords are currently stored as entered in `data/accounts.csv`.

## Requirements

- Java 17 or later (the included Maven build is verified with JDK 21)
- Maven Wrapper 3.3.4 downloads Maven 3.10.0 automatically on first use
- No database or external service is used; persistence remains CSV-based
- JavaFX 21.0.11 is downloaded by Maven and is compatible with JDK 21

The project-local Maven Wrapper uses the installed Java JDK and downloads Maven into your user Maven cache; a global Maven installation is not required. The Windows wrapper can find Java on `PATH` when `JAVA_HOME` is unset.

## Compile and run

Run the console backup from `run.bat`, or start the JavaFX customer interface with `run-gui.bat`.

From PowerShell:

```powershell
.\mvnw.cmd compile exec:java '-Dexec.mainClass=drawn.DrawN'
.\mvnw.cmd javafx:run
.\mvnw.cmd javafx:run '-Djavafx.mainClass=drawn.gui.JavaFxSmokeTestApplication'
```

In VS Code, open this folder, choose **Terminal → New Terminal**, then run `.\mvnw.cmd javafx:run` or `run-gui.bat` in PowerShell. The standalone smoke window can be selected with the third command above. The GUI stylesheet is loaded from `src/drawn/gui/styles/theme.css` as a Maven resource.

Compile and run the existing assertion checks from PowerShell:

```powershell
.\mvnw.cmd test-compile
java -ea -cp "target/classes;target/test-classes" drawn.UserModelTest
java -ea -cp "target/classes;target/test-classes" drawn.RegistrationWalletTest
java -ea -cp "target/classes;target/test-classes" drawn.service.event.EventServiceTest
java -ea -cp "target/classes;target/test-classes" drawn.service.event.Phase34WorkflowTest
java -ea -cp "target/classes;target/test-classes" drawn.service.event.CustomerRecoveryAndEarningsTest
```

These assertion-based checks use temporary data directories and do not write to the project's `data/` CSV files. The organizer commission defaults to 10%. Set the Java system property `drawn.commission.percent` to a value from 0 to 100 to change it, for example `java -Ddrawn.commission.percent=12.5 -cp target/classes drawn.DrawN`.

## JavaFX GUI

`run-gui.bat` (or `mvn javafx:run`) opens the GUI. It covers every console workflow:

| GUI screen | Console menu it replaces |
|---|---|
| Log in / Register (admin logs in with `admin@drawn.com` / `admin`) | Welcome menu |
| Home: account overview and published events | Customer dashboard |
| Explore Events: public list, private keyword, buy tickets | Events & Venues → Explore Events |
| My Events: details, cancel event | My Event Plans & Venue Requests |
| Create & Publish Event | Create & Publish Event (Approved Venue) |
| Venue Reservations / Request a Venue: quote, pay, cancel | Manage Venue Requests |
| My Tickets: buy, cancel with fee summary | Tickets |
| Wallet & Transactions: top-up, history, records | Wallet & Transactions |
| My Profile (sidebar profile menu) | My Profile |
| Admin Dashboard (`src/drawn/gui/admin`): add events, approved public submissions | Admin Dashboard |

The GUI lives in `src/drawn/gui` and only calls existing services. The customer screens use `drawn.service.customer.CustomerWorkflowService`; the admin screen uses `drawn.service.admin.AdminService`.

The GUI smoke test clicks through every screen against a temporary data folder (your `data/` is not touched). Pass a folder to also save a screenshot of each page:

```powershell
.\mvnw.cmd test-compile
java -ea --module-path "$env:USERPROFILE\.m2\repository\org\openjfx\javafx-base\21.0.2\javafx-base-21.0.2-win.jar;$env:USERPROFILE\.m2\repository\org\openjfx\javafx-graphics\21.0.2\javafx-graphics-21.0.2-win.jar;$env:USERPROFILE\.m2\repository\org\openjfx\javafx-controls\21.0.2\javafx-controls-21.0.2-win.jar" --add-modules javafx.controls -cp "target/classes;target/test-classes" drawn.gui.GuiSmokeTest screenshots
```
