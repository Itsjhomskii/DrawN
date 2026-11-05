# DrawN — Event Management & Ticketing System

**Tagline:** From drawing to doing.

DrawN is a console-based Java project for event listings and ticket reservations, with customer registration and a simulated wallet.

## Development status

The console app supports customer registration by email or Philippine phone number, contact-based login, customer event and venue requests, wallet payments, optional event publishing, and ticket sales. New workflow data is saved additively under `data/` as CSV files; existing account, wallet, and event-submission CSV formats remain readable. Reservation requests stay pending until the admin-side code calls `ReservationService.approve` or `ReservationService.decline`; `pendingRequests` supplies the review queue. The admin dashboard is unchanged. The existing admin-created event list and simple ticket reservation flow remain available in memory for the current run. Payments are simulated through the customer wallet.

Demo admin login: `admin` / `234`. Customer accounts are created through registration. Customer passwords are currently stored as entered in `data/accounts.csv`.

## Requirements

- Java 17 or later
- No database or external service is currently used

## Compile and run

Double-click `run.bat` to compile the app and open its console menu. You can also run it from PowerShell in the project directory:

```powershell
$sources = Get-ChildItem -Recurse src -Filter *.java | ForEach-Object FullName
javac --release 17 -d out $sources
java -cp out drawn.DrawN
```

Compile and run the dependency-free checks from PowerShell:

```powershell
$sources = Get-ChildItem -Recurse src,test -Filter *.java | ForEach-Object FullName
javac --release 17 -d out $sources
java -ea -cp out drawn.UserModelTest
java -ea -cp out drawn.RegistrationWalletTest
java -ea -cp out drawn.service.event.EventServiceTest
java -ea -cp out drawn.service.event.Phase34WorkflowTest
```
