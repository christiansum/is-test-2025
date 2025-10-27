# Integration Suite (Extractor / Processor / Uploader)

Autocontained integration stack with **Spring Boot 3.5.7 + Apache Camel**, **Redis (Pub/Sub)**, **SQLite**, and an internal **SFTP** server (SSH key auth only).
Everything runs via **Docker Compose**. You can also point to your own Redis or SFTP by editing the service-level `.env` files.

---

## 0) Prerequisites

-   Docker & Docker Compose (v2)
-   Git, GitBash(Windows) to run commands
-   OpenSSH tools available in your shell (Linux/macOS/WSL/PowerShell)

---

## 1) Clone

```bash
git clone https://github.com/christiansum/is-test-2025
cd is-test-2025
```

---

## 2) One-time setup: keys & crypto

Create a folder for keys if it doesn’t exist:

```bash
mkdir -p keys upload
```

### 2.1 Generate SFTP RSA key pair (client side)

Linux/macOS/WSL/PowerShell:

```bash
ssh-keygen -t rsa -b 4096 -m PEM -f ./keys/id_rsa -N "ISTest"
# Private  : ./keys/id_rsa
# Public   : ./keys/id_rsa.pub
```

-   The **SFTP container** needs the **public** key.
-   The **uploader service** uses the **private** key (+ optional passphrase).
-   Keep the value "ISTest" or the one that you choose; you’ll paste them into `is-uploader-srv/.env`.

### 2.2 Generate `known_hosts` for the internal SFTP

After Compose is up the first time, the SFTP will listen on host port **2222**. Precompute host key entry:

```bash
# If ssh-keyscan is available:
ssh-keyscan -p 2222 localhost > ./keys/known_hosts
```

> You can also regenerate this any time after `docker compose up -d sftp`.

### 2.3 Generate AES Key / IV (hex)

```bash
# AES-256 key (64 hex chars)
openssl rand -hex 32
# IV 128-bit (32 hex chars)
openssl rand -hex 16
```

Keep both values; you’ll paste them into `is-uploader-srv/.env`.

---

## 3) Configure env files (one per service)

Each service has its **own** `.env` (do not share). Copy the sample inside each service path and adjust as needed.

The values below, belong to autocontain configuration currently placed in .env.example on each service.

### 3.1 `is-extractor-srv/.env`

```env
# ==== Redis (Pub/Sub) ====
REDIS_HOST=redis
REDIS_PORT=6379
REDIS_PASSWORD=redisStrongP@ss123
REDIS_CHANNEL=files.events
REDIS_DLQ_CHANNEL=files.dlq

# ==== List of API Endpoints to get processed (CSV format)====
#EXTRACTORS=users,products

EXTRACTORS=users


# ---- EndPoint USERS ----
USERS_URL=https://dummyjson.com/users
USERS_ARRAY_FIELD=users
USERS_LIMIT=100
USERS_CRON=0+0+2+*+*+?        # 02:00AM daily


# ---- EndPoint PRODUCTS (Example to be used in the future) ----
#PRODUCTS_URL=https://dummyjson.com/products
#PRODUCTS_ARRAY_FIELD=products
#PRODUCTS_LIMIT=100
#PRODUCTS_CRON=0+30+2+*+*+?    # 02:30 daily

```

### 3.2 `is-processor-srv/.env`

```env
REDIS_HOST=redis
REDIS_PORT=6379
REDIS_PASSWORD=redisStrongP@ss123
REDIS_CHANNEL=files.events
REDIS_DLQ_CHANNEL=files.dlq

# Scheduler (Quartz)
UPLOADER_CRON=0+30+2+*+*+?+*   # daily 2.30AM
DATA_DIR=/data

# Retries
RETRIES=3
REDELIVERY_DELAY_MS=5000
BACKOFF_MULTIPLIER=3.0
```

> Put your `departments.csv` at repo root (it’s mounted to `/data/departments.csv`).

### 3.3 `is-uploader-srv/.env`

```env
REDIS_HOST=redis
REDIS_PORT=6379
REDIS_PASSWORD=redisStrongP@ss123
REDIS_CHANNEL=files.events
REDIS_DLQ_CHANNEL=files.dlq

# SQLite
DB_URL=jdbc:sqlite:/data/db/files.db
#DB_USERNAME=
#DB_PASSWORD=

# Crypto (AES/CBC/PKCS5Padding)
CRYPTO_ALGO=AES/CBC/PKCS5Padding
CRYPTO_KEY_HEX=<PASTE_YOUR_64_HEX_KEY>
CRYPTO_IV_HEX=<PASTE_YOUR_32_HEX_IV>

# SFTP ( SSH key )
SFTP_HOST=sftp
SFTP_PORT=22
SFTP_USER=camel
SFTP_KEY_PATH=/run/secrets/sftp_key
SFTP_KEY_PASSPHRASE=ISTest
SFTP_REMOTE_DIR=/upload

# SFTP Security
SFTP_STRICT_HOST_KEY_CHECKING=true
SFTP_KNOWN_HOSTS=/run/secrets/known_hosts   # monta tu known_hosts aquí (ro)

# Scheduler (Quartz)
UPLOADER_CRON=0+*+3+*+*+?+*   # daily 3.00AM
DATA_DIR=/data

# Retries
RETRIES=3
REDELIVERY_DELAY_MS=5000
BACKOFF_MULTIPLIER=3.0

```

---

## 4) Compose: what’s included (already wired)

-   **redis**: with password via `--requirepass`.
-   **sftp**: `atmoz/sftp`, user `${SFTP_USER}`, public key mounted from `./keys/id_rsa.pub`.
-   **sqlite**: file DB at `/var/sqlite/files.db` (mounted volume).
-   **is-extractor-srv** / **is-processor-srv** / **is-uploader-srv**: Spring Boot + Camel apps, each reading its **own** `.env`.

The Compose also injects **secrets** into the uploader:

-   `sftp_key` → `./keys/id_rsa` (private key, mode 0400 inside the container).
-   `known_hosts` → `./keys/known_hosts`.

> No extra actions required here besides generating keys and `.env` files.

---

## 5) Bring everything up

```bash
docker compose build
docker compose up -d
docker compose ps
```

Watch logs:

```bash
docker compose logs -f is-extractor-srv
docker compose logs -f is-Processor-srv
docker compose logs -f is-uploader-srv
```

---

## 6) What each service does

-   **is-extractor-srv**: scheduled (daily) full pull from DummyJSON (users), paginated with resume (state), writes `raw_users/*.jsonl`, publishes events to Redis.
-   **is-transformer-srv**: validates schema, enriches with `departments.csv`, routes invalids to `dlq/*.jsonl`, writes `processed_users/*.jsonl`, republishes events.
-   **is-uploader-srv**: saves file metadata to **SQLite** (`/var/sqlite/files.db`), **encrypts (AES)** and uploads files to **SFTP** using **RSA key** auth. Also runs a sweep every 5 minutes.

All share a common data volume at `/data`.

---

## 7) Default ports

| Service            | Port (host) |
| ------------------ | ----------- |
| is-extractor-srv   | 8080        |
| is-transformer-srv | 8081        |
| is-uploader-srv    | 8082        |
| redis              | 6379        |
| sftp               | 2222 → 22   |

---

## 8) Use your own Redis / SFTP (optional)

Point to **external Redis** by changing each service’s `.env`:

```env
REDIS_HOST=my-redis.company.net
REDIS_PORT=6379
REDIS_PASSWORD=<your-pass>
```

Point to **external SFTP** (uploader):

```env
SFTP_HOST=my-sftp.company.net
SFTP_PORT=22
SFTP_USER=myuser
SFTP_KEY_PATH=/run/secrets/sftp_key       # keep using Docker secret for your private key
SFTP_KNOWN_HOSTS=/run/secrets/known_hosts # create your known_hosts accordingly
```

Recreate only the service you changed:

```bash
docker compose up -d --build is-uploader-srv
```

---

## 9) Verify quickly

-   Files appear under the shared volume after extractor/transformer run:

    -   `./volumes/suite_data/raw_users/*.jsonl`
    -   `./volumes/suite_data/processed_users/*.jsonl`
    -   `./volumes/suite_data/dlq/*.jsonl`

-   SQLite has rows:

    ```bash
    docker compose exec sqlite sqlite3 /var/sqlite/files.db ".tables"
    docker compose exec sqlite sqlite3 /var/sqlite/files.db "select * from files limit 5;"
    ```

-   SFTP receives encrypted files (`*.enc`) under `/upload`:

    ```bash
    sftp -i ./keys/id_rsa -P 2222 camel@localhost
    sftp> ls /upload
    ```

---

## 10) Build or run a single service

```bash
docker compose build is-uploader-srv
docker compose up -d is-uploader-srv
docker compose logs -f is-uploader-srv
```

---
