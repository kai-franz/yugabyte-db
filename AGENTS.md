### Agents in YugabyteDB Anywhere (YBA)

This document explains the Yugabyte Node Agent used by YugabyteDB Anywhere (YBA): what it is, how it works, how to install and operate it, and how to contribute and test changes.

- Audience: operators, SREs, and developers working with YBA-managed on-prem nodes.
- Applies to: on-prem and manually provisioned nodes managed by YBA.


## Overview

- The Node Agent runs on every database node and provides a secure control and telemetry channel between the node and YBA.
- It is installed as a `systemd` service named `yb-node-agent.service` and exposes a gRPC server on the node.
- It handles tasks such as registration with YBA, node configuration, controlled execution of node tasks, and surfacing logs/metrics.

Key characteristics:
- Default listen port: 9070 (configurable).
- Configuration stored as YAML under the Node Agent home.
- TLS and JWT-based auth to protect APIs and correlate with YBA.


## Architecture at a glance

- YBA <-> Node Agent: The agent authenticates to YBA using JWT derived from provisioned certs. Some operations require an API token.
- Filesystem layout is rooted under the Node Agent home directory, by default: `~yugabyte/node-agent` (see code for exact resolution rules and registry override).
- Important subdirectories:
  - `~/node-agent/config` – config files (for example, `config.yml`).
  - `~/node-agent/cert` – certificates and keys used by the agent.
  - `~/node-agent/logs` – log files (`node_agent.log`, `grpc.log`).
  - `~/node-agent/pkg` – installed binaries, templates, and scripts; includes the installer `node-agent-installer.sh`.

Relevant code (for maintainers):
- Config/paths and keys: `managed/node-agent/util/common.go`, `managed/node-agent/util/config.go`
- TLS/JWT helpers: `managed/node-agent/util/certs_util.go`
- CLI entrypoints: `managed/node-agent/cmd/cli` and `managed/node-agent/cli/**`
- Systemd templates and provisioning modules: `managed/node-agent/resources/ynp/**`


## Installation

There are two common paths to install and configure the Node Agent.

### 1) YBA-automated install (recommended)

- When using on-prem providers with “manual provisioning”, YBA can push and configure the agent for you.
- Ensure network prerequisites (see Networking) and platform credentials are set up. Then enable/trigger automatic Node Agent install from YBA where supported.

### 2) Manual install (quickstart)

Prerequisites:
- Linux host with a `yugabyte` user (or desired service user) and `systemd`.
- Outbound connectivity to YBA (or explicit “disable egress” mode; see Configuration).
- Sudo/root for service install and system changes.

Steps (high level):
1. Copy the installer to the node (or obtain a release bundle produced by YBA):
   - Script exists in the repo for reference: `managed/node-agent/resources/node-agent-installer.sh`
2. Run the installer as root (or with sudo) to lay down `~/node-agent` and register the service:
   ```bash
   sudo bash node-agent-installer.sh
   ```
3. Configure the agent (see “Configuration”), then start it:
   ```bash
   sudo systemctl daemon-reload
   sudo systemctl enable yb-node-agent.service
   sudo systemctl start yb-node-agent.service
   sudo systemctl status yb-node-agent.service
   ```


## Configuration

Configuration is persisted in YAML under the config directory (by default `~/node-agent/config/config.yml`). Keys are validated and read by the agent at runtime.

Common keys (source of truth in `managed/node-agent/util/common.go`):
- Platform/YBA:
  - `platform.url` – YBA base URL (scheme://host[:port]).
  - `platform.cuuid` – YBA customer UUID.
  - `platform.userId` – YBA user ID used during config/registration flows.
  - `platform.puuid` – YBA provider UUID (on-prem provider when applicable).
  - `platform.certs` – path to agent’s cert directory (for JWT/TLS).
  - `platform.skip_verify_cert` – boolean to skip TLS verification (use cautiously).
  - `platform.version` – recorded YBA version for compatibility.
- Node:
  - `node.ip` – node’s reachable address (IP or FQDN) as seen by YBA.
  - `node.bind_ip` – bind IP for the agent (defaults to `node.ip` if IP).
  - `node.port` – listen port (default `9070`).
  - `node.name` – node name (display/identity).
  - Region/zone/instance metadata: `node.region`, `node.zone`, `node.azid`, `node.instance_type`.
  - Logging: `node.log` (default `node_agent.log`), `node.log_level`, rotation keys.
  - Metrics: `node.disable_metrics_tls` (default true in code, enable TLS in secured environments).

Location helpers used by the agent (resolved automatically, shown here for operator awareness):
- Home directory: `~yugabyte/node-agent`
- Config directory: `~/node-agent/config`
- Certs directory: `~/node-agent/cert`
- Logs directory: `~/node-agent/logs`
- Installer/upgrade script: `~/node-agent/pkg/bin/node-agent-installer.sh`


## Networking

- Default listen port: `9070/TCP` from YBA to the node (or bidirectionally depending on topology/policies).
- Ensure the agent can reach YBA (egress) unless using “disable egress” install mode.
- If using TLS verification, make sure CA trust is configured as needed (see `platform.ca_cert_path` and certificate deployment).


## Operating the service

Systemd:
```bash
sudo systemctl start yb-node-agent.service
sudo systemctl stop yb-node-agent.service
sudo systemctl restart yb-node-agent.service
sudo systemctl status yb-node-agent.service
```

Logs:
- File logs: `~/node-agent/logs/node_agent.log`
- gRPC logs: `~/node-agent/logs/grpc.log`

Check version with the CLI:
```bash
sudo -u yugabyte ~/node-agent/pkg/bin/node-agent version
```


## CLI quick reference

The CLI entrypoint is installed under `~/node-agent/pkg/bin/node-agent` and provides helper commands for configuration, registration, and local testing.

- Interactive or silent configuration (egress enabled):
  ```bash
  # Interactive (prompts)
  node-agent node configure \
    --api_token <YBA_API_TOKEN> \
    --url https://<yba-host> \
    --node_port 9070

  # Silent (no prompts)
  node-agent node configure --silent \
    --api_token <YBA_API_TOKEN> \
    --url https://<yba-host> \
    --node_name <node-name> \
    --node_ip <ip-or-fqdn> \
    --node_port 9070 \
    --provider_id <provider-id-or-name> \
    --instance_type <type> \
    --region_name <region-id> \
    --zone_name <zone-id>
  ```

- Configure with egress disabled (e.g., YBA drives registration; certs pre-provisioned):
  ```bash
  node-agent node configure --disable_egress \
    --id <node-agent-uuid> \
    --customer_id <customer-uuid> \
    --cert_dir <path-to-agent-certs> \
    --node_name <node-name> \
    --node_ip <ip-or-fqdn> \
    --node_port 9070 \
    [--bind_ip <bind-ip>] \
    [--skip_verify_cert]
  ```

- Explicit register/unregister flows:
  ```bash
  node-agent node register \
    -t <YBA_API_TOKEN> \
    -n <ip-or-fqdn> \
    -u https://<yba-host> \
    [--skip_verify_cert]

  node-agent node unregister \
    [-t <YBA_API_TOKEN>] \
    -i <node-agent-uuid> \
    -n <ip-or-fqdn> \
    -u https://<yba-host> \
    [--skip_verify_cert]
  ```

- Local server start for testing (not for production; use systemd for normal ops):
  ```bash
  node-agent server start
  ```


## Troubleshooting

- Service won’t start
  - Check `sudo systemctl status yb-node-agent.service` and journal logs.
  - Review `~/node-agent/logs/node_agent.log` and `~/node-agent/logs/grpc.log`.
  - Verify `node.port` is free and reachable; confirm `bind_ip` is correct.

- Registration/configuration issues
  - Ensure `platform.url` is the base URL (scheme://host[:port]) and reachable from the node.
  - If TLS verification fails, either fix CA trust (`platform.ca_cert_path`) or use `--skip_verify_cert` temporarily to confirm connectivity.
  - Validate that customer/provider IDs and region/zone identifiers exist in YBA and are correct.

- Certificate or JWT problems
  - Confirm the agent cert/key exists under `~/node-agent/cert/` and file permissions are correct.
  - If keys rotated in YBA, re-run configuration or upgrade the certs on the node and restart the service.

- Upgrades
  - The installed `node-agent-installer.sh` at `~/node-agent/pkg/bin` can upgrade in place.
  - Always restart the service after upgrade: `sudo systemctl restart yb-node-agent.service`.


## Developer notes

Build and test the Node Agent components locally:

- Minimal README: `managed/node-agent/README.md`
- Fast test entrypoint:
  ```bash
  cd managed/node-agent
  ./build.sh test
  ```
- Entry binaries and CLI:
  - Main: `managed/node-agent/cmd/cli/main.go`
  - CLI commands: `managed/node-agent/cli/**`

Key implementation areas:
- Config and constants: `managed/node-agent/util/common.go`, `managed/node-agent/util/config.go`
- TLS/JWT and logging: `managed/node-agent/util/certs_util.go`, `managed/node-agent/util/logging.go`
- Provisioning templates and systemd units: `managed/node-agent/resources/ynp/**`

Contribution tips:
- Prefer explicit, readable code; avoid catching and ignoring errors.
- Keep default values and config keys consistent with `util/common.go`.
- Update this document if you add or change user-facing flags, ports, service names, or directory layout.


## Security considerations

- Treat YBA API tokens as secrets; rotate as part of normal security hygiene.
- Use TLS verification in production; avoid `--skip_verify_cert` except for diagnostics.
- Limit OS privileges to what’s required; the service uses `systemd` and may need specific groups (for example, `systemd-journal`) per templates.


## Appendix: Useful paths and defaults

- Home: `~yugabyte/node-agent`
- Config dir: `~/node-agent/config` (default config name `config.yml`)
- Certs dir: `~/node-agent/cert`
- Logs dir: `~/node-agent/logs`
- Installer/upgrade: `~/node-agent/pkg/bin/node-agent-installer.sh`
- Default listen port: `9070`
- Systemd service: `yb-node-agent.service`

