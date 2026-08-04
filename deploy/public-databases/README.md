# Public database stack

This stack runs isolated copies of MySQL, PostgreSQL, Redis, MongoDB, and
SQL Server. Credentials are stored in the untracked `.env` file.

Start and verify:

```bash
docker compose up -d
docker compose ps
```

Copy data from the existing `new-mysql`, `cp-matcher-postgres`, and
`cp-matcher-redis` containers:

```bash
./migrate-existing.sh
```

Host ports:

| Database | Port |
| --- | ---: |
| MySQL | 13306 |
| PostgreSQL | 15433 |
| Redis | 16380 |
| MongoDB | 27018 |
| SQL Server | 11434 |

Publishing Docker ports only exposes them on the Mac's network interfaces.
Internet access additionally requires router/NAT port forwarding from the
desired external ports to `192.168.3.45`, plus source-IP allow-listing.
Reserve `192.168.3.45` for this Mac in the router's DHCP settings, then add
TCP-only forwarding rules with the same external and internal ports shown
above. Do not forward the older unauthenticated/local service ports such as
`3306`, `5432`, or `6379`.

Connection users:

| Database | User | Authentication database |
| --- | --- | --- |
| MySQL | `root` | n/a |
| PostgreSQL | `dbx_admin` | `dbx` |
| Redis | `default` | n/a |
| MongoDB | `dbx_admin` | `admin` |
| SQL Server | `sa` | `master` |

Passwords are in `.env`. Keep this file private. Prefer a VPN or SSH tunnel;
if direct database ports must be forwarded, restrict every rule to trusted
source IPs and enable TLS before sending sensitive production data.

## Connect from a phone on the same Wi-Fi

Use `192.168.3.45` as the host. If the phone and database client support
mDNS, `kubandeMac-mini-3.local` remains usable if the DHCP address changes.
Do not use `localhost` or a container name from the phone.

| Database | Host | Port | Extra setting |
| --- | --- | ---: | --- |
| MySQL | `192.168.3.45` | 13306 | User `root` |
| PostgreSQL | `192.168.3.45` | 15433 | User `dbx_admin`, database `dbx` |
| Redis | `192.168.3.45` | 16380 | User `default` |
| MongoDB | `192.168.3.45` | 27018 | User `dbx_admin`, `authSource=admin` |
| SQL Server | `192.168.3.45` | 11434 | User `sa`, trust the development certificate |

The Mac and phone must be on the same LAN and the Wi-Fi must not enable
client/AP isolation. For access over cellular data or another Wi-Fi, install
Tailscale on both devices or configure a public TCP tunnel; Docker port
publishing alone cannot cross the router's NAT.

SQL Server's official Linux image supports only x86-64 hosts. This compose
file requests amd64 emulation on Apple Silicon, which is useful for
development but is not a Microsoft-supported production setup.
