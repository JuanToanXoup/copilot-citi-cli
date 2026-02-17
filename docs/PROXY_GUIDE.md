# Proxy Configuration Guide

This guide covers how to use the Copilot CLI behind a proxy server, including corporate NTLM proxies.

## Quick Start

```bash
# Simple HTTP proxy
python3 copilot_client.py --proxy http://proxy.example.com:8080 agent "hello"

# Proxy with Basic auth
python3 copilot_client.py --proxy http://user:password@proxy.example.com:8080 agent "hello"

# Corporate SSL-intercepting proxy (disable cert verification)
python3 copilot_client.py --proxy http://proxy.example.com:8080 --no-ssl-verify agent "hello"
```

## CLI Flags

| Flag | Description |
|---|---|
| `--proxy URL` | Proxy URL in standard format: `http://[user:pass@]host:port` |
| `--no-ssl-verify` | Disable SSL certificate verification (for MITM/SSL-intercepting proxies) |

Both flags are global and work with all subcommands (`agent`, `chat`, `complete`, `models`, `mcp`).

## How It Works

The `--proxy` flag configures the proxy at two layers:

```
┌──────────────┐          ┌─────────────────────────┐          ┌───────────────┐
│  copilot CLI  │  stdio   │  copilot-language-server │  HTTPS   │  GitHub API   │
│  (Python)     │ ───────> │  (Node.js)               │ ──────> │  Copilot API  │
└──────────────┘          └─────────────────────────┘          └───────────────┘
       │                            │          │
       │ 1. Sets HTTP_PROXY         │          │
       │    HTTPS_PROXY env vars    │          │
       │                            │          │
       │ 2. Sends LSP config:       │          │
       │    settings.http.proxy     │          ▼
       │    settings.http           │    ┌───────────┐
       │      .proxyStrictSSL       │───>│   Proxy    │──> internet
       │    settings.http           │    └───────────┘
       │      .proxyAuthorization   │
       │                            │
```

**Layer 1: Environment variables** — `HTTP_PROXY` and `HTTPS_PROXY` are set on the subprocess so Node.js picks them up for all outbound HTTP requests.

**Layer 2: LSP configuration** — `workspace/didChangeConfiguration` sends structured proxy settings to the language server's internal HTTP pipeline:

```json
{
  "settings": {
    "http": {
      "proxy": "http://proxy.example.com:8080",
      "proxyStrictSSL": true,
      "proxyAuthorization": "Basic dXNlcjpwYXNz"
    }
  }
}
```

## Proxy Authentication

### Basic Auth

Embed credentials directly in the proxy URL:

```bash
python3 copilot_client.py --proxy http://john:s3cret@proxy.corp.com:8080 agent
```

The CLI automatically:
1. Extracts `john:s3cret` from the URL
2. Encodes it as `Basic am9objpzM2NyZXQ=`
3. Sends `proxyAuthorization` to the language server
4. Passes a clean URL (without credentials) as the proxy address

### NTLM Auth

The Copilot language server **does not support NTLM authentication** natively. NTLM requires a multi-step challenge-response handshake (Type 1 negotiate, Type 2 challenge, Type 3 authenticate) that the server's HTTP client does not implement.

**Solution: Use a local NTLM proxy bridge.**

A bridge proxy runs locally, handles NTLM authentication with your corporate proxy, and exposes a simple (no-auth or Basic-auth) proxy to the CLI.

#### Option A: CNTLM

[CNTLM](https://cntlm.sourceforge.net/) is a lightweight NTLM proxy.

```bash
# Install
brew install cntlm        # macOS
sudo apt install cntlm    # Ubuntu/Debian

# Run (listens on localhost:3128, authenticates upstream via NTLM)
cntlm -u username -d DOMAIN -p password proxy.corp.com:8080 -l 3128

# Use with CLI
python3 copilot_client.py --proxy http://localhost:3128 agent "hello"
```

For persistent use, configure `/etc/cntlm.conf`:

```ini
Username    john
Domain      CORP
Password    s3cret
Proxy       proxy.corp.com:8080
Listen      3128
```

Then run `cntlm -c /etc/cntlm.conf` (or as a system service).

#### Option B: Px

[Px](https://github.com/genotrance/px) is a cross-platform NTLM proxy that uses your system credentials automatically (no password in config).

```bash
# Install
pip install px-proxy

# Run (auto-detects system proxy settings and credentials)
px

# Use with CLI
python3 copilot_client.py --proxy http://localhost:3128 agent "hello"
```

#### Option C: Corporate PAC file

If your network uses a PAC file, extract the proxy host from it first:

```bash
# Download and inspect the PAC file
curl http://wpad.corp.com/wpad.dat

# Look for PROXY directives like:
#   return "PROXY proxy.corp.com:8080"

# Then use CNTLM or Px with that proxy host
cntlm -u user -d DOMAIN proxy.corp.com:8080 -l 3128
python3 copilot_client.py --proxy http://localhost:3128 agent
```

### Kerberos Auth

The language server has built-in Kerberos support via the `proxyKerberosServicePrincipal` setting. This can be configured via environment variables:

```bash
export GH_COPILOT_KERBEROS_SERVICE_PRINCIPAL=HTTP/proxy.corp.com
# or
export GITHUB_COPILOT_KERBEROS_SERVICE_PRINCIPAL=HTTP/proxy.corp.com

python3 copilot_client.py --proxy http://proxy.corp.com:8080 agent
```

## SSL / Certificate Issues

### Corporate SSL-intercepting proxies

Many corporate proxies perform SSL interception (MITM) using their own CA certificate. This causes SSL verification failures because Node.js doesn't trust the corporate CA.

**Quick fix** (less secure):

```bash
python3 copilot_client.py --proxy http://proxy:8080 --no-ssl-verify agent
```

This sets `proxyStrictSSL: false` and disables certificate verification.

**Proper fix** (recommended): Add your corporate CA to Node.js trust:

```bash
# Export your corporate CA certificate (ask IT for the .pem file)
# Then set NODE_EXTRA_CA_CERTS before running the CLI
export NODE_EXTRA_CA_CERTS=/path/to/corporate-ca.pem
python3 copilot_client.py --proxy http://proxy:8080 agent
```

### Self-signed proxy certificates

Same as above — either use `--no-ssl-verify` or add the certificate to `NODE_EXTRA_CA_CERTS`.

## Environment Variables

The language server also reads proxy configuration from standard environment variables as a fallback when no `--proxy` flag is provided:

| Variable | Purpose |
|---|---|
| `HTTP_PROXY` / `http_proxy` | Proxy for HTTP requests |
| `HTTPS_PROXY` / `https_proxy` | Proxy for HTTPS requests |
| `NO_PROXY` / `no_proxy` | Comma-separated list of hosts to bypass the proxy |
| `NODE_TLS_REJECT_UNAUTHORIZED=0` | Disable SSL verification (equivalent to `--no-ssl-verify`) |
| `NODE_EXTRA_CA_CERTS=/path/to.pem` | Add custom CA certificates |
| `GH_COPILOT_KERBEROS_SERVICE_PRINCIPAL` | Kerberos SPN for proxy auth |

```bash
# Using env vars instead of --proxy flag
export HTTPS_PROXY=http://proxy.corp.com:8080
export NODE_EXTRA_CA_CERTS=/etc/ssl/corporate-ca.pem
python3 copilot_client.py agent "hello"
```

## Troubleshooting

### "407 Proxy Authentication Required"

Your proxy requires credentials. Either:
- Embed them in the URL: `--proxy http://user:pass@proxy:8080`
- Use a local bridge (CNTLM/Px) for NTLM auth
- Set `GH_COPILOT_KERBEROS_SERVICE_PRINCIPAL` for Kerberos auth

### "UNABLE_TO_VERIFY_LEAF_SIGNATURE" or "SELF_SIGNED_CERT_IN_CHAIN"

Your proxy is intercepting SSL. Either:
- Use `--no-ssl-verify` (quick fix)
- Set `NODE_EXTRA_CA_CERTS` to your corporate CA (proper fix)

### "ECONNREFUSED" or "ETIMEDOUT"

The proxy host/port is wrong or unreachable. Verify with:
```bash
curl -x http://proxy:8080 https://api.github.com/zen
```

### Proxy works for `curl` but not for the CLI

The CLI uses Node.js which may not read system proxy settings. Always pass `--proxy` explicitly or set `HTTPS_PROXY`.

### MCP servers not working behind proxy

MCP servers (like Playwright) are local processes communicating via stdio — they don't go through the proxy. However, if an MCP server makes outbound HTTP requests (e.g., Playwright navigating to a website), it may need its own proxy config:

```json
{
  "playwright": {
    "command": "npx",
    "args": ["-y", "@playwright/mcp@latest"],
    "env": {
      "HTTPS_PROXY": "http://proxy:8080"
    }
  }
}
```

## Complete Examples

### Corporate network with NTLM + SSL interception

```bash
# 1. Start CNTLM bridge
cntlm -u john -d CORP -p password proxy.corp.com:8080 -l 3128

# 2. Run CLI through the bridge
python3 copilot_client.py \
  --proxy http://localhost:3128 \
  --no-ssl-verify \
  agent "explain this codebase"
```

### Corporate network with Basic auth proxy

```bash
python3 copilot_client.py \
  --proxy http://john:password@proxy.corp.com:8080 \
  agent "explain this codebase"
```

### Corporate network with Kerberos

```bash
export GH_COPILOT_KERBEROS_SERVICE_PRINCIPAL=HTTP/proxy.corp.com
python3 copilot_client.py \
  --proxy http://proxy.corp.com:8080 \
  agent "explain this codebase"
```

### Everything combined (proxy + MCP + custom CA)

```bash
export NODE_EXTRA_CA_CERTS=/etc/ssl/corporate-ca.pem

python3 copilot_client.py \
  --proxy http://localhost:3128 \
  --mcp mcp.json \
  -w ~/project \
  agent "navigate to our internal dashboard and check the status"
```
