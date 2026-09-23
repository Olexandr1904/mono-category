# Monobank Budget Tracker

Self-hosted spending tracker for a single Monobank user. Transactions arrive by
webhook, get bucketed into categories by MCC, and Telegram tells you when a
category approaches or passes its monthly limit.

Design notes live in [docs/superpowers/specs/](docs/superpowers/specs/); the UI
is specified in [docs/design-handoff.md](docs/design-handoff.md), which the
source comments cite by section number.

## Requirements

- A Monobank personal API token from <https://api.monobank.ua/>
- A Telegram bot token from [@BotFather](https://t.me/BotFather)
- A Fly.io account

## Deploy

```bash
fly launch --no-deploy            # answer no to Postgres and Redis
fly volumes create budget_data --size 1 --region fra   # same region as primary_region in fly.toml
fly secrets set \
  ADMIN_PASSWORD="$(openssl rand -base64 18)" \
  ENCRYPTION_KEY="$(openssl rand -base64 32)"
fly deploy
```

Save both secrets in a password manager. `ENCRYPTION_KEY` decrypts the stored
API tokens — **a volume snapshot is useless without it**, and losing it means
re-entering both tokens.

`ADMIN_PASSWORD` is the only thing between the internet and a full transaction
history, so generate it rather than choosing it. Rotating it (`fly secrets set
ADMIN_PASSWORD=…`) also invalidates every session cookie issued under the old one.

## First run

1. Open `https://<your-app>.fly.dev` and log in with `ADMIN_PASSWORD`.
2. **Settings** → paste the Monobank and Telegram tokens → Save. Saving the Monobank
   token for the first time already runs an initial sync of the current month, which
   also stamps the 60-second cooldown — the **Sync transactions** button will show a
   countdown right away. That is expected, not an error.
3. Press **Generate pairing code**, then send `/start <code>` to your bot.
4. Press **Register Telegram webhook** and **Register Monobank webhook**.
5. **Categories** → create categories, set limits and MCC codes. Use **Sync
   transactions** later, once the cooldown clears, to pull anything new on demand.

When a purchase arrives with an MCC you have not mapped, the bot asks which
category it belongs to. Your answer binds that MCC for good and rewrites the
matching history.

### Sharing the bot with someone else

The bot talks to exactly one chat, and that chat can be a group. To let a
partner see the alerts and answer the category questions too, create a Telegram
group, add the bot to it, then press **Reconnect** in Settings and send
`/start <code>` **in the group**. Everything — limit alerts, category questions
with their buttons, the commands below — moves there, and anyone in the group can
answer. The chat it left is told where the alerts went, so a move is never a bot
that quietly stopped talking.

### Commands

| Command | What it does |
|---|---|
| `/status [YYYY-MM]` | spending for the month, largest category first; no month means this one |
| `/left` | how much room is left in each category with a limit, tightest first |
| `/limit` | change a monthly limit: pick a category from the buttons, then reply with the amount |
| `/help` | the same list |

`/limit` asks for the amount as a **reply** to its own message, and in a group it
mentions whoever pressed the button so only their reply box opens. Answer it by
replying — a number typed into the room as an ordinary message never reaches the
bot at all (see privacy mode below). `0` removes the limit.

The bot needs no admin rights in the group, and Telegram's default privacy mode
is fine: it still receives commands and replies to its own messages, which is all
it reads. In a group it answers only the commands above, addressed to it, and
stays silent for everything else — including the same commands addressed to a
different bot in the room.

## Configuration

| Variable | Required | Purpose |
|---|---|---|
| `ADMIN_PASSWORD` | yes | web UI login |
| `ENCRYPTION_KEY` | yes | base64 of 32 random bytes; AES-GCM key for stored tokens |
| `DB_PATH` | no | defaults to `/data/budget.db` |
| `PORT` | no | defaults to `8080` |
| `PUBLIC_URL` | no | the URL registered with Monobank and Telegram. Unset by default (see below); when set, request headers are never consulted for it |
| `ALLOWED_HOSTS` | no | comma-separated hostnames accepted when deriving the public URL from a request. Ignored if `PUBLIC_URL` is set |
| `DEV_INSECURE_COOKIES` | no | set to `1` for local HTTP development; the session cookie is `Secure` by default and will not survive plain HTTP. Also disables HSTS |

Everything else — both API tokens, the chat pairing, categories, limits — is
configured through the web UI.

`PUBLIC_URL` and `ALLOWED_HOSTS` are unset in the shipped `fly.toml`, and the
app derives its public URL from the proxy's `X-Forwarded-Host`, validated
against a strict hostname shape. That is correct on Fly out of the box. Once
your app has a stable hostname, setting both is worth doing: it stops the URL
that Monobank and Telegram push to from depending on a request header at all.

```toml
[env]
  PUBLIC_URL = "https://your-app.fly.dev"
  ALLOWED_HOSTS = "your-app.fly.dev"
```

## Operational notes

- **Exactly one machine.** SQLite lives on a Fly volume that mounts to a single
  machine; a second writer corrupts state. `max_machines_running = 1` enforces
  this and must not be raised.
- **The machine never sleeps.** Monobank disables a webhook after three failed
  deliveries, and a cold JVM misses its 5-second timeout.
- **Hourly sync is the safety net.** Even if the webhook is disabled, the next
  hourly statement pull reconciles the data.
- **Timezone is Europe/Kyiv**, fixed in code. Months are calendar months in Kyiv
  time regardless of the container's `TZ`.
- **Sessions can be revoked.** Pressing **Log out** invalidates every session
  cookie everywhere, not just in the browser that pressed it, and cookies expire
  server-side after 30 days regardless of what the browser kept.
- **Login is rate limited.** Ten wrong passwords from one address lock that
  address out for 15 minutes; a shallower global window caps the damage from an
  attacker rotating addresses without letting one lock the owner out for long.
- **The pairing code expires.** It is good for 15 minutes and five attempts,
  then it has to be regenerated from Settings. While it is live, sending it from
  any chat moves the bot to that chat — that is how a group gets connected, and
  it is why the code is a credential worth the same care as the password. With no
  live code out, a paired bot ignores every chat but its own.
- **The container runs unprivileged.** `docker-entrypoint.sh` hands `/data` to a
  non-root user and drops to it before starting the JVM.
- **Netty is pinned ahead of Ktor's own version** in `build.gradle.kts` because
  the version Ktor 3.0.3 selects has open HTTP request smuggling advisories.
  Re-check OSV before changing that constraint or bumping Ktor.

## Development

```bash
./gradlew test                    # the whole suite
ADMIN_PASSWORD=dev \
ENCRYPTION_KEY="$(openssl rand -base64 32)" \
DB_PATH=./dev.db \
DEV_INSECURE_COOKIES=1 ./gradlew run    # http://localhost:8080
```

Tests never contact the real Monobank or Telegram — both are behind interfaces
with `MockEngine` fixtures.

## Licence

This project is MIT licensed — see [LICENSE](LICENSE).

The bundled IBM Plex fonts under `src/main/resources/static/fonts/` are
copyright IBM Corp. and licensed under the SIL Open Font License 1.1 — see
[OFL.txt](src/main/resources/static/fonts/OFL.txt). They are redistributed
here, not relicensed.

## Not an official Monobank product

This is an unofficial, independently built tool. It is not affiliated with,
endorsed by, or supported by Universal Bank or Monobank. It talks to Monobank's
public personal API with a token you issue yourself, and it can be switched off
by revoking that token.
