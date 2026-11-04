# DrawN — Event Management & Ticketing System

**Tagline:** From drawing to doing.

DrawN is a console-based Java project for event listings and ticket reservations, with customer registration and a simulated wallet.

## Development status

The console app supports customer registration by email or Philippine phone number, contact-based login, age-derived organizer levels, and an initial wallet deposit. Customer accounts and wallet transactions are saved under `data/` as CSV files. Event data and reservations remain in memory for the current run. Venue booking, payment verification, and support are planned for later phases.

Demo admin login: `admin` / `234`. Customer accounts are created through registration. Customer passwords are stored as entered in `data/accounts.csv`, as requested for this version.

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

The application is planned to save its complete state in `data/drawn-data.properties`. Until that persistence phase is implemented, data does not survive program termination.
