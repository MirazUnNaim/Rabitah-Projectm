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

Linux (including Parrot/Debian):

```bash
./run-linux.sh
```

Windows Command Prompt or PowerShell:

```bat
run-windows.bat
```

Both launchers start PostgreSQL, wait for the API to become healthy, and then open the desktop application. They require Java 21, Maven 3.9+, and Docker. The local development SysAdmin credentials are `SYSADMIN` / `Rabitah123!`; override the password with the `RABITAH_SYSTEM_ADMIN_PASSWORD` environment variable outside local development.

For a student on another computer on the same local network, run only the client. It discovers the Rabitah server automatically:

```bash
./run-client-linux.sh
```

```bat
run-client-windows.bat
```

No address needs to be entered. The client sends a local-network discovery request on UDP port 45871, connects to the responding Rabitah server, and then receives approval changes over the `/ws/approvals` WebSocket. A ten-second API refresh remains as a fallback. The server and clients must be on the same local network, and the firewall must allow TCP 8080 and UDP 45871.

## Student approval flow

A roster student enters their student ID and a new password of at least eight characters on the normal sign-in screen. The first attempt creates an access request and does not grant access. SysAdmin reviews it under **Admin Approvals**. After approval, the student signs in with the same credentials. Declined students receive a clear denial message. Student posts and question-paper PDFs also remain hidden until SysAdmin approves them from the same inbox.

The base seed creates `SYSADMIN`, 720 deterministic roster entries, and 24 community rooms. When `RABITAH_DEMO_PASSWORD` is set it also creates active student accounts `CSE1A003`, `EEE2B003`, and `CEE3A003`, plus sample feed, notice, community, and academic data. Use the passwords supplied through the environment; no runtime secret is committed.

## Verification

Run `mvn test` from the repository root. The backend uses Flyway as the only schema owner and Hibernate validates the migrated schema.

## Production deployment

The desktop client remains native JavaFX; deploy the Spring API and PostgreSQL to Railway, then distribute the client to students configured with the Railway API URL. Media is S3-compatible object storage in production, not Railway's ephemeral disk. See [Railway deployment](docs/railway-deployment.md).

## Eclipse

Use **File → Import → Existing Maven Projects**, select this repository, and import both child projects. Ensure Eclipse uses the Java 21 JDK. Run `RabitahBackendApplication` for the API and `RabitahApplication` for the desktop client.

## Available platform modules

The JavaFX shell provides a social feed with reactions/comments, notice board, searchable question repository with PDF uploads, persisted private chat, scoped community history, and read-only profile. Consult `IMPLEMENTATION_STATUS.md` for advanced specification items that remain outstanding.
