# DrawN — Event Management & Ticketing System

**Tagline:** From drawing to doing.

DrawN is a Java event listing and ticketing project with a preserved console workflow and a JavaFX customer GUI foundation.

## Development status

The console app supports customer registration by email or Philippine phone number, contact-based login, customer event and venue requests, wallet payments, event publishing, and ticket sales. New workflow data is saved under `data/` as CSV files; existing account, wallet, and event-submission CSV formats remain readable. Venue requests stay pending until an administrator approves or declines them. Admin event lists read persisted customer events. Payments are simulated through the customer wallet.

The former session-only admin Add Event and simple ticket reservation controls have been removed. They had no persisted organizer, venue reservation, or wallet payment workflow. Customer-created events remain owned by their customer account and follow the persisted venue and ticket services. The JavaFX admin Events tab lists persisted customer events and only enables approval decisions for pending submissions.

Venue extension review updates the event and venue reservation schedule. An extension fee estimate is shown, but extension billing is not configured; approval does not charge a wallet or create a payment transaction.

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
java -ea -cp "target/classes;target/test-classes" drawn.service.admin.AdminDashboardServiceTest
java -ea -cp "target/classes;target/test-classes" drawn.service.admin.AdminDashboardFlowTest
java -ea -cp "target/classes;target/test-classes" drawn.service.admin.VenueServiceTest
java -ea -cp "target/classes;target/test-classes" drawn.service.customer.CustomerGuiWorkflowTest
java -ea -cp "target/classes;target/test-classes" drawn.storage.AtomicCsvFileTest
java -ea -cp "target/classes;target/test-classes" drawn.service.event.VenueExtensionReviewTest
```

The admin screen test (approve, decline with refund, every admin tab) needs JavaFX on the module path; add a folder name at the end to save screenshots:

```powershell
$fx = "$env:USERPROFILE\.m2\repository\org\openjfx\javafx-base\21.0.11\javafx-base-21.0.11-win.jar;$env:USERPROFILE\.m2\repository\org\openjfx\javafx-graphics\21.0.11\javafx-graphics-21.0.11-win.jar;$env:USERPROFILE\.m2\repository\org\openjfx\javafx-controls\21.0.11\javafx-controls-21.0.11-win.jar"
java -ea --module-path $fx --add-modules javafx.controls -cp "target/classes;target/test-classes" drawn.gui.admin.AdminViewSmokeTest
```

These assertion-based checks use temporary data directories and do not write to the project's `data/` CSV files. The organizer commission defaults to 10%. Set the Java system property `drawn.commission.percent` to a value from 0 to 100 to change it, for example `java -Ddrawn.commission.percent=12.5 -cp target/classes drawn.DrawN`.

## JavaFX GUI

`run-gui.bat` (or `mvn javafx:run`) opens the GUI. It covers the persisted customer workflows and the admin reservation, event review, venue, extension review, ticket, transaction, revenue, and decision screens:

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
| Admin Dashboard (`src/drawn/gui/admin`): persisted customer events, venue and extension reviews, and admin records | Admin review and venue management |

The GUI lives in `src/drawn/gui` and delegates work to customer, event, payment, venue, and admin services. The JavaFX screen does not create admin-owned events; the project does not define an organizer, venue payment, or ticket ownership policy for those records.

### Venue details preview data

The Venue Details screen uses clearly labeled fictional demo ratings and reviews for presentation layout only. They are not Google data and are not statements about the selected venue. The page links to Google Maps for current venue information; DrawN does not scrape or store Google review content. No Google API key or billing setup is used. Verified venue photos can be added under `src/drawn/gui/images/venues/` using the venue name as a lowercase, hyphenated `.jpg` or `.png` filename; if none exists, the page shows a neutral category placeholder.

The Wallet & Transactions page calculates totals from the signed-in account's existing wallet transaction ledger. Top-ups include `INITIAL_FUNDING` and `TOP_UP`; gross spending sums recorded `PAYMENT:` entries; refunds sum recorded `REFUND:` entries and are shown separately. The `Pending` filter replaces the separate venue-reservations filter and groups reservation records with their current approval and payment statuses.

The GUI smoke tests use temporary data folders (your `data/` is not touched). Pass a folder to `GuiSmokeTest` or `AdminViewSmokeTest` to also save screenshots:

```powershell
.\mvnw.cmd test-compile
java -ea --module-path $fx --add-modules javafx.controls -cp "target/classes;target/test-classes" drawn.gui.GuiSmokeTest screenshots
java -ea --module-path $fx --add-modules javafx.controls -cp "target/classes;target/test-classes" drawn.gui.views.CustomerPlanSmokeTest
```
