# DrawN — Event Management & Ticketing System

**Tagline:** From drawing to doing.

DrawN is a console-based Java project for managing event bookings, bookable spaces, public-event tickets, manual payment verification, and customer support.

## Development status

The project is being built incrementally. The current console app has a login screen, lets an admin add and list events, and lets customers browse events and reserve tickets. Event data and reservations are kept in memory for the current run. Venue booking, payment verification, support, and persistent storage are planned for later phases.

Demo logins: customer `jhomskii` / `123`; admin `admin` / `234`.

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
