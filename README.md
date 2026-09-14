# Rabitah

Rabitah is a Java 21 university social and academic desktop application. The repository contains a Spring Boot API and a non-modular JavaFX client. The implementation is being constructed incrementally according to `IMPLEMENTATION_STATUS.md`.

## Prerequisites

- Java 21
- Maven 3.9+
- Docker with Compose, or PostgreSQL 14+

## Local setup

1. Copy `.env.example` to `.env` and replace both sample security values.
2. Start PostgreSQL: `docker compose up -d postgres`.
3. Export the values from `.env` in your shell.
4. Start the API: `mvn -f Rabitah-Backend/pom.xml spring-boot:run`.
5. Confirm `http://localhost:8080/actuator/health` returns `UP`.
6. In another terminal, start JavaFX: `mvn -f Rabitah-Frontend/pom.xml javafx:run`.

## One-command launch

Rabitah runs on Linux, macOS, and Windows. JavaFX downloads the correct native libraries for the operating system when Maven starts it.

| Operating system | Start server + desktop app | Start a client for an existing campus server |
| --- | --- | --- |
| Linux (including Parrot/Debian) | `./run-linux.sh` | `./run-client-linux.sh` |
| macOS | `./run-macos.command` | `./run-client-macos.command` |
| Windows Command Prompt or PowerShell | `run-windows.bat` | `run-client-windows.bat` |

The macOS `.command` launchers can be double-clicked in Finder. If macOS reports that a cloned script is not executable, run `chmod +x run-macos.command run-client-macos.command` once in Terminal.

Each full launcher starts PostgreSQL, waits for the API to become healthy, and opens the desktop application. A later click opens another Rabitah window rather than attempting to start a second backend. They require Java 21, Maven 3.9+, and Docker (Docker Desktop on macOS/Windows). The local development SysAdmin credentials are `SYSADMIN` / `Rabitah123!`; override the password with the `RABITAH_SYSTEM_ADMIN_PASSWORD` environment variable outside local development.

For a student on another computer on the same local network, use the client launcher for their operating system from the table above. It discovers the Rabitah server automatically.

No address needs to be entered. The client sends a local-network discovery request on UDP port 45871, connects to the responding Rabitah server, and then receives approval changes over the `/ws/approvals` WebSocket. A ten-second API refresh remains as a fallback. The server and clients must be on the same local network, and the firewall must allow TCP 8080 and UDP 45871.

## Student approval flow

A roster student enters their numeric campus ID and a new password of at least eight characters on the normal sign-in screen. The first attempt creates an access request and does not grant access. SysAdmin reviews it under **Admin Approvals**. After approval, the student signs in with the same credentials. Declined students receive a clear denial message. Student posts and question-paper PDFs also remain hidden until SysAdmin approves them from the same inbox.

Student IDs use `BB00DDSRR`: `BB` is batch 21–24, `DD` is department (`11` MPE, `21` EEE, `41` CSE, `51` CEE), `S` is section (`1`/A or `2`/B), and `RR` is roll 01–60. For example, batch 23 / CSE / section 2 / roll 11 is `230041211`.

The base seed creates `SYSADMIN`, 1,920 deterministic roster entries, and 32 community rooms. To create local test accounts and an ignored CSV credential list, set `RABITAH_SEED_DEMO_STUDENT_ACCOUNTS=true`; it writes only newly created account credentials to `RABITAH_SEED_CREDENTIAL_EXPORT`. Never enable that option against a shared or production database.

## Verification

Run `mvn test` from the repository root. The backend uses Flyway as the only schema owner and Hibernate validates the migrated schema.

## Production deployment

The desktop client remains native JavaFX; deploy the Spring API and PostgreSQL to Railway, then distribute the client to students configured with the Railway API URL. Media is S3-compatible object storage in production, not Railway's ephemeral disk. See [Railway deployment](docs/railway-deployment.md).

## Eclipse

Use **File → Import → Existing Maven Projects**, select this repository, and import both child projects. Ensure Eclipse uses the Java 21 JDK. Run `RabitahBackendApplication` for the API and `RabitahApplication` for the desktop client.

## Available platform modules

The JavaFX shell provides a social feed with reactions/comments, notice board, searchable question repository with PDF uploads, persisted private chat, scoped community history, and read-only profile. Consult `IMPLEMENTATION_STATUS.md` for advanced specification items that remain outstanding.
