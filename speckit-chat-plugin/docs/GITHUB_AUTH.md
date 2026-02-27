# GitHub Authentication in Copilot-Agent

## Overview

The copilot-agent does **not** implement authentication directly. It delegates auth to the IDE's GitHub session provider (the JetBrains plugin layer). The agent simply requests a session, receives a token, and uses it for API calls.

## Auth Flow

1. Agent calls `getSession()` on the IDE's session provider
2. IDE returns a session object with `apiUrl` and `accessToken`
3. If no session exists, a `NoGitHubToken` error is thrown
4. The access token is used as a Bearer token in API requests

```javascript
async getApiSession() {
  let e = await this.ctx.get(mn).getSession();
  if (!e) throw new $v; // NoGitHubToken
  return {
    apiUrl: e.apiUrl.replace(/\/$/, ""),
    accessToken: e.accessToken
  };
}
```

## Token Storage — `CopilotTokenStore`

The `CopilotTokenStore` class manages the active Copilot token:

- Stores the token in a private `_copilotToken` field
- **Event-driven**: fires `onDidStoreUpdate` when the token changes
- Extends `Disposable` for proper resource cleanup
- Subscribers throughout the system react to token updates

```javascript
class CopilotTokenStore extends Disposable {
  _onDidStoreUpdate = this._register(new Emitter());
  onDidStoreUpdate = this._onDidStoreUpdate.event;

  get copilotToken() {
    return this._copilotToken;
  }

  set copilotToken(e) {
    let prev = this._copilotToken?.token;
    this._copilotToken = e;
    if (prev !== e?.token) this._onDidStoreUpdate.fire();
  }
}
```

## API Call Authentication

| Concern | Mechanism |
|---|---|
| GitHub API auth | `Authorization: Bearer <accessToken>` |
| Proxy auth | `Proxy-Authorization: Basic <base64>` |
| API format | Both REST and GraphQL via `apiUrl` |

## Error Handling

| Error Code | Meaning |
|---|---|
| `Et.NoGitHubToken` | User is not authenticated / no session available |
| `Et.InvalidRequest` | GitHub API rejected the request |
| `Et.InternalError` | Unexpected auth failure |

When auth fails, the agent returns a tuple:

```javascript
if (n instanceof $v) // NoGitHubToken
  return [null, { code: Et.NoGitHubToken, message: n.message }];
```

## What the Agent Does NOT Handle

- **OAuth device flow** — handled by the IDE plugin
- **Login UI / browser redirect** — handled by the IDE plugin
- **Token refresh / renewal** — handled externally; the agent just reads the current session
- **Credential storage on disk** — managed by the IDE's credential store

A reference to `"Authentication Successful!"` HTML exists in the codebase, indicating the IDE handles a browser-based OAuth flow and displays a success page upon completion.

## Key Files

| File | Purpose |
|---|---|
| `dist/main.js` | Bundled agent code containing all auth logic (minified) |
| `dist/main.js.map` | Source map for debugging |
| `dist/language-server.js` | LSP server entry point |
| `dist/api/types.d.ts` | TypeScript type definitions for API contracts |
