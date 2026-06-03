# Deploying anvil

This directory documents the supported anvil deploy patterns. The goal
is "running anvil somewhere I control" without standing up a public
service unless you actually want one.

## The deploy spectrum

```
laptop lein run  →  systemd local  →  Tailscale  →  VPS private  →  VPS public
[← dogfood ────────────────────────────────────────────── deploy →]
```

For the dogfood end (no public exposure), the recipe below is what
anvil itself runs on the SuperBadLabs dogfood host.

---

## Recipe: systemd + automatic redeploy on master

This is the pattern installed on the SuperBadLabs anvil dogfood
machine. anvil runs as a systemd service; a timer polls `master`
every 5 minutes and rebuilds + hot-swaps when something new lands.

### One-time setup

```bash
# 1. Install Java 21+, Leiningen, Babashka, git
sudo apt-get update && sudo apt-get install -y openjdk-21-jdk git
curl -fsSL https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein \
  -o /usr/local/bin/lein && sudo chmod +x /usr/local/bin/lein
curl -fsSL https://raw.githubusercontent.com/babashka/babashka/master/install \
  | sudo bash

# 2. Install chengis-core 0.1.0 into local Maven cache (one-time)
git clone --depth 1 --branch v0.1.0 \
  https://github.com/SuperBadLabs/chengis-core.git /tmp/cc
(cd /tmp/cc && lein install)
rm -rf /tmp/cc

# 3. Clone anvil as the deploy checkout
sudo install -d -o $USER -g $USER /var/lib/anvil
git clone --depth 50 https://github.com/SuperBadLabs/anvil.git /var/lib/anvil/source

# 4. Build the initial uberjar + install it
cd /var/lib/anvil/source
lein uberjar
sudo install -d /opt/anvil
sudo cp target/uberjar/anvil-*-standalone.jar /opt/anvil/anvil.jar

# 5. Install the redeploy script
sudo install -m 755 -o $USER scripts/redeploy.bb /opt/anvil/redeploy.bb

# 6. Install the systemd units (edit User= if you're not srikanth)
sudo cp ops/systemd/anvil.service /etc/systemd/system/
sudo cp ops/systemd/anvil-redeploy.service /etc/systemd/system/
sudo cp ops/systemd/anvil-redeploy.timer /etc/systemd/system/
sudo systemctl daemon-reload

# 7. Make ~/anvil-dogfood data dir
mkdir -p $HOME/anvil-dogfood/{data,logs,workspaces,artifacts}

# 8. Enable + start
sudo systemctl enable --now anvil.service
sudo systemctl enable --now anvil-redeploy.timer

# 9. Open the firewall to your LAN subnet
# (Adjust to your subnet; this example is for 192.168.4.0/22)
sudo ufw allow from 192.168.4.0/22 to any port 8765 proto tcp \
     comment "anvil dogfood — LAN only"

# 10. Bookmark
echo "anvil up at http://$(hostname -I | awk '{print $1}'):8765"
```

### Operational handles

```bash
sudo systemctl status anvil                  # is anvil running?
sudo systemctl status anvil-redeploy.timer   # is the redeploy poller on?
sudo systemctl list-timers anvil-redeploy.timer  # when does it fire next?

sudo journalctl -u anvil -f                  # live anvil logs
sudo journalctl -u anvil-redeploy -n 20      # redeploy history

# Force a redeploy NOW (without waiting 5 min)
sudo systemctl start anvil-redeploy.service

# Manually rebuild + hot-swap from your own dev checkout
cd /your/anvil/checkout
lein uberjar
sudo cp target/uberjar/anvil-*-standalone.jar /opt/anvil/anvil.jar
sudo systemctl restart anvil
```

### What the redeploy script does

`/opt/anvil/redeploy.bb` (sourced from `scripts/redeploy.bb`):

1. Refuses to run if the deploy checkout (`/var/lib/anvil/source`) has
   local edits. The check is a guard against silently overwriting work.
2. `git fetch origin master`. If `HEAD == origin/master`, no-op.
3. `git merge --ff-only origin/master`.
4. `lein uberjar`. If the build fails, log + keep the current jar.
5. Backs up the current jar to `/opt/anvil/anvil.jar.previous`.
6. Copies the new jar to `/opt/anvil/anvil.jar`.
7. `sudo systemctl restart anvil`.
8. Health-checks `http://127.0.0.1:8765/`. If it doesn't return 200
   within 30 seconds, rolls back to the backed-up jar and restarts.

### Why a separate deploy checkout?

`/var/lib/anvil/source` is *only* touched by the redeploy script. Your
development checkout (anywhere you like — `~/code/anvil`, `/tmp/work`,
etc.) is independent. The script's "refuses if dirty" guard would
otherwise conflict with active development.

---

## Going public

The recipe above stays on a LAN. To put anvil on the public internet,
see the upstream `docs/anvil-ui/reverse-proxy-auth.md` (from the
monorepo era; will land in this repo as part of TR5.2 anvil-doc
migration). Three patterns: nginx + auth_request + oauth2-proxy,
Caddy + forward_auth, or Cloudflare Access.

Per the v0.3 board's revised AV3-9, **public anvil deploy is not
supported until v0.3.0 ships** — the first impression must include
the seven parity features. The recipe above is the supported dogfood
posture until then.
