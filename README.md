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
