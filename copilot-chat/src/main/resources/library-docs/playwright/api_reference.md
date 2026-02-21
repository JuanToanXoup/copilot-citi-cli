# Playwright Java API Reference (Compact)

> Playwright automates Chromium, Firefox, and WebKit with a single API.
> Maven: `com.microsoft.playwright:playwright`

## Quick Start Pattern (Java)

```java
import com.microsoft.playwright.*;

try (Playwright pw = Playwright.create()) {
    Browser browser = pw.chromium().launch();
    BrowserContext context = browser.newContext();
    Page page = context.newPage();
    page.navigate("https://example.com");
    page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("shot.png")));
    browser.close();
}
```

---

## Browser

Represents a browser instance launched via `BrowserType.launch()`.

### newContext(options?)
Create isolated browser context (separate cookies/cache).
- `options` - proxy, storageState, viewport, locale, etc.
- Returns: `BrowserContext`

### newPage(options?)
Shortcut: create new page in a new context. Closing the page closes its context.
- Returns: `Page`

### close(options?)
Close browser and all pages. Like force-quitting.
- `reason` (String) - reported to interrupted operations

### contexts()
Returns `List<BrowserContext>` of all open contexts.

### isConnected()
Returns `boolean` - whether browser process is connected.

### version()
Returns `String` - browser version.

### browserType()
Returns `BrowserType` - chromium, firefox, or webkit.

---

## BrowserContext

Isolated session within a browser. Each context has its own cookies, storage, and pages.

### newPage()
Create a new page in this context.
- Returns: `Page`

### close(options?)
Close context and all its pages.
- `reason` (String) - optional closure reason

### pages()
Returns `List<Page>` of all open pages in this context.

### cookies(urls?)
Get cookies, optionally filtered by URLs.
- `urls` (String | List<String>) - URL filter
- Returns: `List<Cookie>` with name, value, domain, path, expires, httpOnly, secure, sameSite

### addCookies(cookies)
Add cookies to context. All pages in context receive them.
- `cookies` (List<Cookie>) - name, value, url/domain/path/expires/httpOnly/secure/sameSite

### clearCookies(options?)
Remove cookies with optional filtering.
- `name` (String | Pattern), `domain` (String | Pattern), `path` (String | Pattern)

### setDefaultTimeout(timeout)
Set default timeout (ms) for all methods. Pass 0 to disable.

### setDefaultNavigationTimeout(timeout)
Set default navigation timeout (ms) for goto, reload, goBack, goForward.

### route(url, handler, options?)
Intercept/modify network requests matching URL pattern.
- `url` (String | Pattern | Predicate) - URL matcher
- `handler` (Consumer<Route>) - request handler
- `times` (int) - max invocations

### unroute(url, handler?)
Remove previously registered route.

### exposeFunction(name, callback)
Add function on `window` object in all frames.
- `name` (String), `callback` (Function)

### storageState(options?)
Export cookies + localStorage snapshot.
- `path` (Path) - save to file
- Returns: storage state String/Object

### waitForPage(callback, options?)
Wait for a new page to be created during callback execution.
- `predicate` (Predicate<Page>), `timeout` (double ms)
- Returns: `Page`

### tracing
Property returning `Tracing` object for trace recording.

### browser()
Returns owning `Browser` or null.

---

## Page

Represents a single tab/popup. Most interaction happens here.

### Navigation

#### navigate(url, options?) / goto(url, options?)
Navigate to URL. Returns response or null.
- `url` (String) - full URL with scheme
- `waitUntil` ("load" | "domcontentloaded" | "networkidle")
- `timeout` (double ms), `referer` (String)

#### reload(options?)
Reload page. Same options as navigate.

#### goBack(options?) / goForward(options?)
History navigation. Returns response or null.

#### waitForLoadState(state?, options?)
Wait for load state.
- `state` ("load" | "domcontentloaded" | "networkidle")
- `timeout` (double ms)

#### waitForURL(url, options?)
Wait until page URL matches pattern.
- `url` (String | Pattern | Predicate)

### Locator Creation

#### locator(selector, options?)
Create a Locator for querying elements. **Preferred approach.**
- `selector` (String) - CSS/text/XPath selector
- `has` (Locator), `hasNot` (Locator), `hasText` (String | Pattern)
- Returns: `Locator`

#### getByRole(role, options?)
Locate by ARIA role. **Recommended for accessibility.**
- `role` (AriaRole) - BUTTON, LINK, HEADING, TEXTBOX, CHECKBOX, etc.
- `name` (String | Pattern), `exact` (boolean)
- `checked`, `disabled`, `expanded`, `level`, `pressed`, `selected`

#### getByText(text, options?)
Locate by visible text content.
- `text` (String | Pattern), `exact` (boolean)

#### getByLabel(text, options?)
Locate form control by associated label.
- `text` (String | Pattern), `exact` (boolean)

#### getByPlaceholder(text, options?)
Locate input by placeholder.
- `text` (String | Pattern), `exact` (boolean)

#### getByTestId(testId)
Locate by `data-testid` attribute.
- `testId` (String | Pattern)

#### getByAltText(text, options?)
Locate by alt text (images).
- `text` (String | Pattern), `exact` (boolean)

#### getByTitle(text, options?)
Locate by title attribute.
- `text` (String | Pattern), `exact` (boolean)

### User Interaction (on Page directly -- prefer Locator methods instead)

#### click(selector, options?)
Click element after actionability checks.
- `selector` (String), `button` ("left"|"right"|"middle"), `clickCount` (int)
- `delay` (double ms), `position` (Position), `timeout` (double ms)

#### fill(selector, value, options?)
Clear input and type value, triggers input event.
- `selector` (String), `value` (String), `timeout` (double ms)

#### press(selector, key, options?)
Focus element then press key.
- `key` (String) - e.g. "Enter", "ArrowDown", "Control+a"

### Content & Inspection

#### title()
Returns `String` - document.title.

#### url()
Returns `String` - current page URL.

#### content()
Returns `String` - full HTML including doctype.

#### innerHTML(selector, options?)
Returns element's innerHTML.

#### innerText(selector, options?)
Returns element's innerText.

#### getAttribute(selector, name, options?)
Returns attribute value or null.

#### inputValue(selector, options?)
Returns value of input/textarea/select.

### JavaScript Evaluation

#### evaluate(expression, arg?)
Run JS in page context, return serializable result.
- `expression` (String) - JS code/function
- `arg` (Object) - argument passed to function

#### evaluateHandle(expression, arg?)
Like evaluate but returns JSHandle.

### Screenshots & PDF

#### screenshot(options?)
Capture page screenshot.
- `path` (Path), `fullPage` (boolean), `clip` (Clip {x, y, width, height})
- `type` ("png" | "jpeg"), `quality` (int)

#### pdf(options?)
Generate PDF (Chromium only).
- `path` (Path), `format` (String - "A4", "Letter"), `landscape` (boolean), `scale` (double)

### Page State

#### close(options?)
Close the page.
- `runBeforeUnload` (boolean)

#### isClosed()
Returns `boolean`.

#### context()
Returns owning `BrowserContext`.

#### mainFrame()
Returns main `Frame`.

---

## Locator

Represents a way to find element(s) on a page. Locators are strict by default (fail if multiple matches). Created via `page.locator()` or `page.getByRole()` etc.

### Interaction

#### click(options?)
Click element. Waits for actionability.
- `button` ("left"|"right"|"middle"), `clickCount` (int), `delay` (double ms)
- `position` (Position), `force` (boolean), `timeout` (double ms)

#### dblclick(options?)
Double-click element.

#### fill(value, options?)
Set input value and trigger input event.
- `value` (String), `force` (boolean), `timeout` (double ms)

#### clear(options?)
Clear input field.

#### press(key, options?)
Focus and press key combination.
- `key` (String) - e.g. "Enter", "Control+c"

#### pressSequentially(text, options?)
Type text character by character (replaces deprecated `type()`).
- `text` (String), `delay` (double ms)

#### hover(options?)
Hover over element.
- `position` (Position), `force` (boolean), `timeout` (double ms)

#### focus(options?)
Focus the element.

#### check(options?) / uncheck(options?)
Set checkbox/radio checked or unchecked.
- `position`, `force`, `timeout`

#### setChecked(checked, options?)
Set checkbox/radio to specific state.
- `checked` (boolean)

#### selectOption(values, options?)
Select option(s) in `<select>`.
- `values` (String | String[] | SelectOption | SelectOption[])
- Returns: `List<String>` selected values

#### setInputFiles(files, options?)
Upload files to file input.
- `files` (Path | Path[] | FilePayload | FilePayload[])

#### dragTo(target, options?)
Drag this element to target locator.
- `target` (Locator)

#### tap(options?)
Tap gesture (requires `hasTouch` context option).

### Content Retrieval

#### textContent(options?)
Returns `String | null` - node.textContent.

#### innerText(options?)
Returns `String` - element.innerText.

#### innerHTML(options?)
Returns `String` - element.innerHTML.

#### inputValue(options?)
Returns `String` - value of input/textarea/select.

#### getAttribute(name, options?)
Returns `String | null` - attribute value.

#### allInnerTexts()
Returns `List<String>` - innerText of all matching elements.

#### allTextContents()
Returns `List<String>` - textContent of all matching elements.

### State Checks

#### isVisible()
Immediate check, no waiting. Returns `boolean`.

#### isHidden()
Immediate check. Returns `boolean`.

#### isEnabled(options?) / isDisabled(options?)
Returns `boolean`.

#### isChecked(options?)
Returns `boolean` for checkbox/radio.

#### isEditable(options?)
Returns `boolean`.

#### count()
Returns `int` - number of matching elements.

### Filtering & Composition

#### locator(selector, options?)
Create child locator within this locator.
- `selector` (String), `has`, `hasNot`, `hasText`, `hasNotText`

#### filter(options?)
Narrow down by text or nested locator.
- `hasText` (String | Pattern), `has` (Locator), `hasNot` (Locator), `visible` (boolean)

#### and(locator)
Match elements that satisfy both locators.

#### or(locator)
Match elements that satisfy either locator.

#### first() / last()
Returns Locator for first/last match.

#### nth(index)
Returns Locator for zero-based index match.

#### all()
Returns `List<Locator>` for all current matches. Does not wait.

### Sub-locators (same as Page)

#### getByRole / getByText / getByLabel / getByPlaceholder / getByTestId / getByAltText / getByTitle
Same signatures as Page methods but scoped within this locator.

### Utility

#### waitFor(options?)
Wait for element to reach state.
- `state` ("visible" | "hidden" | "attached" | "detached"), `timeout` (double ms)

#### screenshot(options?)
Screenshot of element only.
- `path` (Path), `timeout` (double ms)

#### scrollIntoViewIfNeeded(options?)
Scroll element into viewport if not visible.

#### evaluate(expression, arg?) / evaluateAll(expression, arg?)
Run JS on first matching element / all matching elements.

#### boundingBox(options?)
Returns `BoundingBox {x, y, width, height}` or null.

#### highlight()
Visually highlight element for debugging.

#### page()
Returns owning `Page`.

---

## Common Java Patterns

```java
// Preferred: use Locators with getByRole
page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Submit")).click();

// Fill a form
page.getByLabel("Username").fill("admin");
page.getByLabel("Password").fill("secret");
page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Login")).click();

// Wait and assert
page.waitForURL("**/dashboard");
assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Welcome"))).isVisible();

// Filter locators
Locator rows = page.locator("tr").filter(new Locator.FilterOptions().setHasText("Active"));

// Chained locators
page.locator(".product-card").filter(new Locator.FilterOptions()
    .setHas(page.getByText("In Stock"))).first().click();

// Screenshots
page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("full.png")).setFullPage(true));

// Network interception
context.route("**/api/data", route -> {
    route.fulfill(new Route.FulfillOptions().setBody("{\"mock\":true}").setContentType("application/json"));
});

// Storage state (persist login)
String state = context.storageState(new BrowserContext.StorageStateOptions().setPath(Paths.get("state.json")));
Browser.NewContextOptions opts = new Browser.NewContextOptions().setStorageStatePath(Paths.get("state.json"));
BrowserContext ctx = browser.newContext(opts);
```

---

## Key AriaRole Values

ALERT, BUTTON, CHECKBOX, COMBOBOX, DIALOG, GRID, HEADING, IMG, LINK, LIST, LISTBOX, LISTITEM, MENU, MENUITEM, NAVIGATION, OPTION, PROGRESSBAR, RADIO, ROW, SEARCHBOX, SEPARATOR, SLIDER, SPINBUTTON, STATUS, TAB, TABLE, TABPANEL, TEXTBOX, TOOLBAR, TREE, TREEITEM

---

## Selector Syntax Quick Reference

| Syntax | Example | Description |
|--------|---------|-------------|
| CSS | `"button.primary"` | Standard CSS selector |
| Text | `"text=Log in"` | Match by text content |
| XPath | `"xpath=//button"` | XPath expression |
| Role | `getByRole(AriaRole.BUTTON)` | ARIA role (preferred) |
| Test ID | `getByTestId("submit")` | data-testid attribute |
| Chained | `"article >> .title"` | Descendant within scope |

---

## Frame

Represents a frame within a page (main frame or iframe). Every `Page` has a main frame accessible via `page.mainFrame()`. Iframes are represented as child frames. Frame shares many methods with `Page`.

### frameLocator(selector)
Create a FrameLocator targeting an iframe matched by selector. FrameLocator is not a Frame; it is a locator-like object that auto-enters the iframe for subsequent locator operations.
- `selector` (String) - CSS selector matching an `<iframe>` element
- Returns: `FrameLocator`

```java
// Locate a button inside an iframe
page.frameLocator("#my-iframe").getByRole(AriaRole.BUTTON, new FrameLocator.GetByRoleOptions().setName("Submit")).click();

// Nested iframes
page.frameLocator("#outer").frameLocator("#inner").locator("button").click();
```

### locator(selector, options?)
Create a Locator scoped to this frame.
- `selector` (String) - CSS/text/XPath selector
- `has` (Locator), `hasNot` (Locator), `hasText` (String | Pattern)
- Returns: `Locator`

### navigate(url, options?)
Navigate the frame to a URL.
- `url` (String) - full URL
- `waitUntil` ("load" | "domcontentloaded" | "networkidle")
- `timeout` (double ms), `referer` (String)
- Returns: `Response` or null

### waitForLoadState(state?, options?)
Wait for the frame to reach a specific load state.
- `state` ("load" | "domcontentloaded" | "networkidle")
- `timeout` (double ms)

### evaluate(expression, arg?)
Run JavaScript in the frame context and return a serializable result.
- `expression` (String) - JS code or function
- `arg` (Object) - argument passed to function
- Returns: `Object`

### content()
Returns `String` - the full HTML content of the frame including doctype.

### url()
Returns `String` - the frame's current URL.

### name()
Returns `String` - the frame's `name` attribute or empty string for the main frame.

### parentFrame()
Returns `Frame` or null - the parent frame (null for the main frame).

### childFrames()
Returns `List<Frame>` - all direct child frames.

### isDetached()
Returns `boolean` - true if the frame has been detached from the DOM.

```java
// Working with frames
Frame mainFrame = page.mainFrame();
List<Frame> childFrames = mainFrame.childFrames();

for (Frame frame : childFrames) {
    System.out.println("Frame: " + frame.name() + " URL: " + frame.url());
}

// Evaluate JS in a specific frame
Frame frame = page.frame("my-frame-name");
if (frame != null) {
    String title = (String) frame.evaluate("() => document.title");
}
```

---

## Dialog

Represents a JavaScript dialog: `alert`, `confirm`, `prompt`, or `beforeunload`. Dialogs are emitted via the `page.onDialog` event. **You must handle dialogs; unhandled dialogs are auto-dismissed.**

### Handling Dialogs

Register a listener via `page.onDialog(dialog -> { ... })`. The listener fires before any Playwright action resolves, so register it before triggering the dialog.

```java
// Accept an alert dialog
page.onDialog(dialog -> {
    System.out.println("Dialog message: " + dialog.message());
    dialog.accept();
});
page.evaluate("() => alert('Hello!')");

// Handle a confirm dialog
page.onDialog(dialog -> {
    if (dialog.type().equals("confirm")) {
        dialog.accept();
    } else {
        dialog.dismiss();
    }
});

// Handle a prompt dialog with input
page.onDialog(dialog -> {
    if (dialog.type().equals("prompt")) {
        dialog.accept("My answer");
    }
});
page.evaluate("() => prompt('Enter name:')");
```

### accept(promptText?)
Accept the dialog. For prompts, provide the text to enter.
- `promptText` (String) - text to enter in prompt dialog (ignored for alert/confirm)
- Returns: `void`

### dismiss()
Dismiss the dialog (equivalent to pressing Cancel/Escape).
- Returns: `void`

### message()
Returns `String` - the dialog message text.

### type()
Returns `String` - the dialog type: `"alert"`, `"confirm"`, `"prompt"`, or `"beforeunload"`.

### defaultValue()
Returns `String` - the default value for prompt dialogs, empty string for others.

---

## Route (Network Interception)

Represents an intercepted network request. Created via `page.route()` or `context.route()`. Allows aborting, continuing with modifications, or fulfilling with custom responses.

### Setting Up Routes

```java
// Intercept at page level
page.route("**/api/users", route -> {
    route.fulfill(new Route.FulfillOptions()
        .setStatus(200)
        .setContentType("application/json")
        .setBody("[{\"name\":\"mock\"}]"));
});

// Intercept at context level (applies to all pages)
context.route("**/*.png", route -> route.abort());

// URL patterns: glob, regex, or predicate
page.route(Pattern.compile(".*/api/.*"), route -> route.continue_());
page.route(url -> url.contains("analytics"), route -> route.abort());
```

### route.abort(errorCode?)
Abort the request.
- `errorCode` (String) - optional error code: `"aborted"`, `"accessdenied"`, `"addressunreachable"`, `"blockedbyclient"`, `"blockedbyresponse"`, `"connectionaborted"`, `"connectionclosed"`, `"connectionfailed"`, `"connectionrefused"`, `"connectionreset"`, `"internetdisconnected"`, `"namenotresolved"`, `"timedout"`, `"failed"`. Defaults to `"failed"`.
- Returns: `void`

```java
// Block all images
page.route("**/*.{png,jpg,jpeg,gif,svg}", route -> route.abort());
```

### route.continue_(overrides?)
Continue the request with optional modifications.
- `url` (String) - override URL
- `method` (String) - override HTTP method
- `headers` (Map<String, String>) - override headers (merged with existing)
- `postData` (String | byte[]) - override POST data
- Returns: `void`

```java
// Add custom header to all API requests
page.route("**/api/**", route -> {
    Map<String, String> headers = new HashMap<>(route.request().headers());
    headers.put("X-Custom-Header", "test-value");
    route.continue_(new Route.ResumeOptions().setHeaders(headers));
});
```

### route.fulfill(response)
Fulfill the request with a custom response, bypassing the server entirely.
- `status` (int) - HTTP status code (default 200)
- `headers` (Map<String, String>) - response headers
- `contentType` (String) - sets Content-Type header
- `body` (String) - response body as string
- `bodyBytes` (byte[]) - response body as bytes
- `path` (Path) - serve response body from file
- `response` (APIResponse) - use an existing APIResponse to fulfill
- Returns: `void`

```java
// Return mock JSON
page.route("**/api/data", route -> {
    route.fulfill(new Route.FulfillOptions()
        .setStatus(200)
        .setContentType("application/json")
        .setBody("{\"items\": [], \"total\": 0}"));
});

// Serve from file
page.route("**/config.json", route -> {
    route.fulfill(new Route.FulfillOptions()
        .setPath(Paths.get("test-data/mock-config.json")));
});

// Modify existing response
page.route("**/api/users", route -> {
    APIResponse response = route.fetch();
    String body = response.text();
    // Modify body as needed
    route.fulfill(new Route.FulfillOptions()
        .setResponse(response)
        .setBody(body.replace("real", "modified")));
});
```

### route.fetch(options?)
Fetch the actual response from the server without fulfilling the route. Useful for modifying responses.
- `url`, `method`, `headers`, `postData` - same override options as `continue_`
- Returns: `APIResponse`

### route.request()
Returns `Request` - the request object being intercepted.

### Request Object

The `Request` object represents an HTTP request. Accessible from `route.request()`, `response.request()`, or via page events.

#### request.url()
Returns `String` - the request URL.

#### request.method()
Returns `String` - the HTTP method (GET, POST, PUT, etc.).

#### request.headers()
Returns `Map<String, String>` - request headers (all header names are lower-case).

#### request.postData()
Returns `String` or null - POST request body.

#### request.postDataBuffer()
Returns `byte[]` or null - POST body as raw bytes.

#### request.isNavigationRequest()
Returns `boolean` - true if this is a navigation request (page load, anchor click, etc.).

#### request.resourceType()
Returns `String` - the resource type: `"document"`, `"stylesheet"`, `"image"`, `"media"`, `"font"`, `"script"`, `"texttrack"`, `"xhr"`, `"fetch"`, `"eventsource"`, `"websocket"`, `"manifest"`, `"other"`.

#### request.redirectedFrom()
Returns `Request` or null - the request that redirected to this one.

#### request.redirectedTo()
Returns `Request` or null - the request this one redirected to.

#### request.failure()
Returns `String` or null - error text if request failed.

#### request.timing()
Returns `Timing` - timing information with `startTime`, `domainLookupStart`, `domainLookupEnd`, `connectStart`, `secureConnectionStart`, `connectEnd`, `requestStart`, `responseStart`, `responseEnd`.

#### request.allHeaders()
Returns `Map<String, String>` - all headers including those added by the browser. Requires response to be available.

### Response Object

The `Response` object represents an HTTP response. Returned by `page.navigate()`, `page.waitForResponse()`, etc.

#### response.url()
Returns `String` - the response URL.

#### response.status()
Returns `int` - HTTP status code (200, 404, etc.).

#### response.statusText()
Returns `String` - HTTP status text ("OK", "Not Found", etc.).

#### response.headers()
Returns `Map<String, String>` - response headers (all header names are lower-case).

#### response.allHeaders()
Returns `Map<String, String>` - all response headers including those added by the browser.

#### response.body()
Returns `byte[]` - the response body.

#### response.text()
Returns `String` - the response body as text.

#### response.json()
Returns `Object` - the response body parsed as JSON (using default deserialization).

#### response.ok()
Returns `boolean` - true if status is 200-299.

#### response.request()
Returns `Request` - the request that produced this response.

#### response.finished()
Returns `String` or null - null if response finished successfully, error message otherwise.

```java
// Wait for specific API response
Response response = page.waitForResponse("**/api/users", () -> {
    page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Load")).click();
});
System.out.println("Status: " + response.status());
System.out.println("Body: " + response.text());

// Listen for request/response events
page.onRequest(request -> System.out.println(">> " + request.method() + " " + request.url()));
page.onResponse(response -> System.out.println("<< " + response.status() + " " + response.url()));
```

---

## Download

Represents a file download initiated by the page. Downloads are emitted via `page.onDownload` event or captured with `page.waitForDownload()`.

```java
// Wait for a download triggered by a click
Download download = page.waitForDownload(() -> {
    page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Export CSV")).click();
});
System.out.println("Filename: " + download.suggestedFilename());
download.saveAs(Paths.get("downloads/" + download.suggestedFilename()));
```

### path()
Returns `Path` - path to the downloaded file in a temporary directory. Available after download completes successfully. File is deleted when the browser context is closed.
- Returns: `Path`

### saveAs(path)
Copy the downloaded file to the specified path. Creates parent directories if needed.
- `path` (Path) - destination path
- Returns: `void`

### suggestedFilename()
Returns `String` - the suggested filename from the `Content-Disposition` header or the download URL.

### url()
Returns `String` - the URL of the download.

### cancel()
Cancel the download. Will cause `failure()` to return `"canceled"`.
- Returns: `void`

### failure()
Returns `String` or null - download error if any, null on success. Call after download completes.

### createReadStream()
Returns `InputStream` - readable stream for the downloaded file.

### page()
Returns `Page` - the page that initiated the download.

```java
// Listen for all downloads
page.onDownload(download -> {
    System.out.println("Download started: " + download.url());
    if (download.failure() == null) {
        download.saveAs(Paths.get("test-downloads/" + download.suggestedFilename()));
    }
});

// Configure download behavior in context
BrowserContext context = browser.newContext(new Browser.NewContextOptions()
    .setAcceptDownloads(true));
```

---

## Tracing

Record traces for post-mortem debugging. Traces can be viewed in the Playwright Trace Viewer (`npx playwright show-trace trace.zip`). Accessed via `context.tracing`.

### context.tracing.start(options?)
Start recording a trace.
- `screenshots` (boolean) - capture screenshots during trace (default false)
- `snapshots` (boolean) - capture DOM snapshots (default false)
- `sources` (boolean) - include source files in trace (default false)
- `title` (String) - trace title shown in viewer
- `name` (String) - trace name; when specified, intermediate trace files are saved to `tracesDir/name`
- Returns: `void`

### context.tracing.stop(options?)
Stop tracing and export the trace file.
- `path` (Path) - save trace to this file (typically `.zip`)
- Returns: `void`

### context.tracing.startChunk(options?)
Start a new trace chunk. Use when you want multiple trace segments within a single `start()`/`stop()` lifecycle.
- `title` (String) - chunk title
- `name` (String) - trace name for intermediate files
- Returns: `void`

### context.tracing.stopChunk(options?)
Stop the current trace chunk and export it.
- `path` (Path) - save chunk to this file
- Returns: `void`

```java
// Basic tracing
BrowserContext context = browser.newContext();
context.tracing().start(new Tracing.StartOptions()
    .setScreenshots(true)
    .setSnapshots(true)
    .setSources(true));

Page page = context.newPage();
page.navigate("https://example.com");
// ... perform actions ...

context.tracing().stop(new Tracing.StopOptions()
    .setPath(Paths.get("trace.zip")));

// Chunked tracing (multiple trace files per context)
context.tracing().start(new Tracing.StartOptions().setScreenshots(true).setSnapshots(true));

context.tracing().startChunk();
page.navigate("https://example.com/login");
page.getByLabel("Username").fill("admin");
context.tracing().stopChunk(new Tracing.StopOptions().setPath(Paths.get("trace-login.zip")));

context.tracing().startChunk();
page.navigate("https://example.com/dashboard");
context.tracing().stopChunk(new Tracing.StopOptions().setPath(Paths.get("trace-dashboard.zip")));

context.tracing().stop();
```

---

## APIRequestContext

Allows sending HTTP requests directly without a browser page. Useful for API testing, setting up preconditions, or validating server state. Created via `playwright.request().newContext()` or accessed from `page.request()` / `context.request()`.

```java
// Create standalone API context
APIRequestContext api = playwright.request().newContext(new APIRequest.NewContextOptions()
    .setBaseURL("https://api.example.com")
    .setExtraHTTPHeaders(Map.of("Authorization", "Bearer token123")));

// Use page-scoped API context (shares cookies with the page)
APIResponse response = page.request().get("/api/profile");
```

### get(url, options?)
Send HTTP GET request.
- `url` (String) - request URL (relative to baseURL if configured)
- Returns: `APIResponse`

### post(url, options?)
Send HTTP POST request.
- `url` (String) - request URL
- Returns: `APIResponse`

### put(url, options?)
Send HTTP PUT request.
- `url` (String) - request URL
- Returns: `APIResponse`

### patch(url, options?)
Send HTTP PATCH request.
- `url` (String) - request URL
- Returns: `APIResponse`

### delete(url, options?)
Send HTTP DELETE request.
- `url` (String) - request URL
- Returns: `APIResponse`

### head(url, options?)
Send HTTP HEAD request.
- `url` (String) - request URL
- Returns: `APIResponse`

### fetch(urlOrRequest, options?)
Send an HTTP request. Can also re-fetch a `Request` object captured from network events.
- `urlOrRequest` (String | Request) - URL or existing Request object
- Returns: `APIResponse`

### dispose()
Dispose the API request context and abort all pending requests.
- Returns: `void`

### Common Request Options

All HTTP methods accept these options:

- `headers` (Map<String, String>) - additional request headers
- `data` (String | byte[] | Object) - JSON-serializable request body (sets Content-Type to `application/json` if object)
- `form` (FormData) - form-encoded data (sets Content-Type to `application/x-www-form-urlencoded`)
- `multipart` (FormData) - multipart form data (sets Content-Type to `multipart/form-data`)
- `params` (Map<String, Object>) - URL query parameters
- `timeout` (double) - request timeout in milliseconds (default 30000)
- `failOnStatusCode` (boolean) - throw on non-2xx/3xx responses (default false)
- `ignoreHTTPSErrors` (boolean) - ignore HTTPS errors (default false)
- `maxRedirects` (int) - maximum number of redirects to follow (default 20, 0 to not follow)
- `maxRetries` (int) - maximum number of retries on network errors (default 0)

```java
// POST with JSON body
APIResponse response = api.post("/api/users", RequestOptions.create()
    .setData(Map.of("name", "John", "email", "john@example.com")));
assertEquals(201, response.status());

// POST with form data
APIResponse response = api.post("/api/login", RequestOptions.create()
    .setForm(FormData.create()
        .set("username", "admin")
        .set("password", "secret")));

// POST with multipart (file upload)
APIResponse response = api.post("/api/upload", RequestOptions.create()
    .setMultipart(FormData.create()
        .set("file", Paths.get("report.pdf"))
        .set("description", "Monthly report")));

// GET with query parameters
APIResponse response = api.get("/api/users", RequestOptions.create()
    .setParams(Map.of("page", 1, "limit", 50)));

// Full API test example
try (APIRequestContext api = playwright.request().newContext(new APIRequest.NewContextOptions()
        .setBaseURL("https://api.example.com")
        .setExtraHTTPHeaders(Map.of("Authorization", "Bearer token")))) {

    // Create
    APIResponse createResp = api.post("/api/items", RequestOptions.create()
        .setData(Map.of("title", "New Item")));
    assertTrue(createResp.ok());

    // Read
    APIResponse getResp = api.get("/api/items/1");
    Map<String, Object> item = (Map<String, Object>) getResp.json();

    // Delete
    APIResponse delResp = api.delete("/api/items/1");
    assertEquals(204, delResp.status());

    api.dispose();
}
```

---

## PlaywrightAssertions

Web-first assertions that auto-retry until the condition is met or timeout expires. Import via `import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat`. These provide clear error messages and auto-wait, making them preferred over manual checks.

### Page Assertions

#### assertThat(page).hasTitle(title, options?)
Assert that the page has the expected title.
- `title` (String | Pattern) - expected title or pattern
- `timeout` (double) - assertion timeout in ms

#### assertThat(page).hasURL(url, options?)
Assert that the page URL matches.
- `url` (String | Pattern) - expected URL or pattern
- `timeout` (double) - assertion timeout in ms

```java
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

assertThat(page).hasTitle("Dashboard - MyApp");
assertThat(page).hasTitle(Pattern.compile("Dashboard.*"));
assertThat(page).hasURL("https://example.com/dashboard");
assertThat(page).hasURL(Pattern.compile(".*/dashboard"));
```

### Locator Assertions

#### assertThat(locator).isVisible(options?)
Assert that element is visible.
- `timeout` (double) - assertion timeout in ms

#### assertThat(locator).isHidden(options?)
Assert that element is hidden or not present in DOM.

#### assertThat(locator).isEnabled(options?)
Assert that element is enabled (not disabled).

#### assertThat(locator).isDisabled(options?)
Assert that element is disabled.

#### assertThat(locator).isChecked(options?)
Assert that checkbox/radio is checked.
- `checked` (boolean) - expected checked state (default true)

#### assertThat(locator).isEditable(options?)
Assert that element is editable (not readonly, not disabled).

#### assertThat(locator).hasText(text, options?)
Assert that element has exact text content (whitespace-normalized).
- `text` (String | Pattern) - expected text
- `text` (String[] | Pattern[]) - expected text for a list of elements
- `ignoreCase` (boolean), `useInnerText` (boolean), `timeout` (double)

#### assertThat(locator).containsText(text, options?)
Assert that element text contains the expected substring.
- `text` (String | Pattern) - expected substring
- `text` (String[] | Pattern[]) - expected substrings for a list of elements
- `ignoreCase` (boolean), `useInnerText` (boolean), `timeout` (double)

#### assertThat(locator).hasAttribute(name, value, options?)
Assert that element has attribute with expected value.
- `name` (String) - attribute name
- `value` (String | Pattern) - expected attribute value
- `timeout` (double)

#### assertThat(locator).hasClass(className, options?)
Assert that element has the expected CSS class(es).
- `className` (String | Pattern) - expected class attribute value
- `className` (String[] | Pattern[]) - expected classes for a list of elements
- `timeout` (double)

#### assertThat(locator).hasCSS(name, value, options?)
Assert that element has the expected computed CSS property value.
- `name` (String) - CSS property name
- `value` (String | Pattern) - expected CSS value
- `timeout` (double)

#### assertThat(locator).hasId(id, options?)
Assert that element has the expected `id` attribute.
- `id` (String | Pattern) - expected id
- `timeout` (double)

#### assertThat(locator).hasValue(value, options?)
Assert that input/textarea/select has the expected value.
- `value` (String | Pattern) - expected value
- `timeout` (double)

#### assertThat(locator).hasValues(values, options?)
Assert that multi-select has the expected selected values.
- `values` (String[] | Pattern[]) - expected values
- `timeout` (double)

#### assertThat(locator).hasCount(count, options?)
Assert that the locator matches the expected number of elements.
- `count` (int) - expected element count
- `timeout` (double)

#### assertThat(locator).hasRole(role, options?)
Assert that element has the expected ARIA role.
- `role` (AriaRole) - expected role
- `timeout` (double)

### Negation

#### assertThat(locator).not()
Negate the assertion. Returns the same assertion object with inverted condition.

```java
// Positive assertions
assertThat(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Submit"))).isVisible();
assertThat(page.getByLabel("Email")).hasValue("user@example.com");
assertThat(page.locator(".items")).hasCount(5);
assertThat(page.getByTestId("status")).hasText("Active");
assertThat(page.getByTestId("status")).containsText("Act");
assertThat(page.locator("input")).hasAttribute("type", "email");
assertThat(page.locator(".alert")).hasClass(Pattern.compile(".*error.*"));
assertThat(page.locator(".price")).hasCSS("color", "rgb(255, 0, 0)");

// Negative assertions with not()
assertThat(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Submit"))).not().isVisible();
assertThat(page.getByLabel("Email")).not().hasValue("");
assertThat(page.locator(".error")).not().isVisible();
assertThat(page.locator(".spinner")).not().isAttached();

// Assertion with custom timeout
assertThat(page.getByText("Success")).isVisible(
    new LocatorAssertions.IsVisibleOptions().setTimeout(10000));
```

### Response Assertions

#### assertThat(response).isOK()
Assert that response status is in the 200-299 range.

```java
Response response = page.navigate("https://example.com");
assertThat(response).isOK();
```

---

## BrowserType

Represents a browser engine: `playwright.chromium()`, `playwright.firefox()`, or `playwright.webkit()`. Used to launch browser instances.

### launch(options?)
Launch a new browser instance.
- Returns: `Browser`

### launchPersistentContext(userDataDir, options?)
Launch browser with a persistent user profile directory. Returns a context directly (no separate browser object).
- `userDataDir` (Path) - path to user data directory
- Returns: `BrowserContext`

### connect(wsEndpoint, options?)
Connect to an existing browser instance via WebSocket.
- `wsEndpoint` (String) - WebSocket URL to connect to
- `headers` (Map<String, String>) - additional HTTP headers for the WebSocket handshake
- `slowMo` (double) - slow down operations by specified ms
- `timeout` (double) - connection timeout in ms (default 30000)
- Returns: `Browser`

### connectOverCDP(endpointURL, options?)
Connect to an existing browser instance over Chrome DevTools Protocol (Chromium only).
- `endpointURL` (String) - CDP endpoint URL
- `headers` (Map<String, String>) - additional HTTP headers
- `slowMo` (double) - slow down operations by specified ms
- `timeout` (double) - connection timeout in ms (default 30000)
- Returns: `Browser`

### name()
Returns `String` - browser type name: `"chromium"`, `"firefox"`, or `"webkit"`.

### executablePath()
Returns `String` - path to the browser executable Playwright uses.

### LaunchOptions

Common options for `launch()` and `launchPersistentContext()`:

- `headless` (boolean) - run browser in headless mode (default true)
- `args` (List<String>) - additional browser command-line arguments
- `channel` (String) - browser distribution channel: `"chrome"`, `"chrome-beta"`, `"chrome-dev"`, `"chrome-canary"`, `"msedge"`, `"msedge-beta"`, `"msedge-dev"`, `"msedge-canary"`
- `chromiumSandbox` (boolean) - enable Chromium sandboxing (default false)
- `devtools` (boolean) - open DevTools panel (Chromium only, non-headless)
- `downloadsPath` (Path) - directory for downloads (defaults to temp)
- `executablePath` (Path) - use custom browser executable
- `proxy` (Proxy) - network proxy settings
  - `server` (String) - proxy URL e.g. `"http://proxy:8080"`
  - `bypass` (String) - comma-separated domains to bypass proxy
  - `username` (String), `password` (String) - proxy auth credentials
- `slowMo` (double) - slow down every operation by specified ms (useful for debugging)
- `timeout` (double) - browser launch timeout in ms (default 30000)
- `ignoreDefaultArgs` (List<String>) - browser args to exclude from defaults
- `env` (Map<String, String>) - environment variables for the browser process
- `firefoxUserPrefs` (Map<String, Object>) - Firefox user preferences
- `handleSIGHUP` (boolean), `handleSIGINT` (boolean), `handleSIGTERM` (boolean) - handle process signals (default true)
- `tracesDir` (Path) - directory for trace files

```java
// Launch headless Chromium
Browser browser = playwright.chromium().launch();

// Launch headed Chrome with slow motion
Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
    .setHeadless(false)
    .setChannel("chrome")
    .setSlowMo(100));

// Launch with proxy
Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
    .setProxy(new Proxy("http://myproxy.com:8080")
        .setUsername("user")
        .setPassword("pass")));

// Launch persistent context (retains profile data)
BrowserContext context = playwright.chromium().launchPersistentContext(
    Paths.get("user-data-dir"),
    new BrowserType.LaunchPersistentContextOptions()
        .setHeadless(false)
        .setChannel("chrome"));

// Connect to remote browser
Browser browser = playwright.chromium().connect("ws://localhost:3000/ws");
```

---

## FileChooser

Represents a file chooser dialog triggered by a file input element. Captured via `page.onFileChooser` event or `page.waitForFileChooser()`.

### Handling File Choosers

```java
// Wait for file chooser and set files
FileChooser fileChooser = page.waitForFileChooser(() -> {
    page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Upload")).click();
});
fileChooser.setFiles(Paths.get("myfile.pdf"));

// Listen for file choosers
page.onFileChooser(fc -> {
    fc.setFiles(Paths.get("photo.jpg"));
});
```

### setFiles(files, options?)
Set files on the file input element.
- `files` (Path | Path[] | FilePayload | FilePayload[]) - files to upload
- `noWaitAfter` (boolean) - skip waiting for navigation (default false)
- `timeout` (double) - timeout in ms
- Returns: `void`

```java
// Upload single file
fileChooser.setFiles(Paths.get("report.pdf"));

// Upload multiple files
fileChooser.setFiles(new Path[] {
    Paths.get("file1.pdf"),
    Paths.get("file2.pdf")
});

// Upload with FilePayload (in-memory content)
fileChooser.setFiles(new FilePayload("data.txt", "text/plain", "File content".getBytes()));

// Clear file selection
fileChooser.setFiles(new Path[0]);
```

### element()
Returns `ElementHandle` - the `<input type="file">` element that triggered the chooser.

### isMultiple()
Returns `boolean` - whether the file input accepts multiple files (`<input multiple>`).

### page()
Returns `Page` - the page the file chooser belongs to.

---

## Video

Record video of page activity. Video recording is configured at context creation time via `recordVideo` option. Accessed via `page.video()`.

### Setup

```java
// Enable video recording for all pages in the context
BrowserContext context = browser.newContext(new Browser.NewContextOptions()
    .setRecordVideoDir(Paths.get("videos/"))
    .setRecordVideoSize(1280, 720));

Page page = context.newPage();
page.navigate("https://example.com");
// ... perform actions ...

// IMPORTANT: close page or context before accessing video path
page.close();
```

### page.video().path()
Returns `Path` - path to the video file. Video is available after the page is closed.
- Returns: `Path`

### page.video().saveAs(path)
Save the video to the specified location. Video is available after the page is closed.
- `path` (Path) - destination file path
- Returns: `void`

### page.video().delete()
Delete the video file. Video is available after the page is closed.
- Returns: `void`

```java
BrowserContext context = browser.newContext(new Browser.NewContextOptions()
    .setRecordVideoDir(Paths.get("videos/")));
Page page = context.newPage();
page.navigate("https://example.com");
page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Login")).click();

// Must close page before saving
page.close();
Path videoPath = page.video().path();
page.video().saveAs(Paths.get("test-results/login-test.webm"));

// Or close entire context (also finalizes videos)
context.close();
```

---

## Mouse & Keyboard

Low-level input APIs for simulating mouse and keyboard events. Accessible via `page.mouse()` and `page.keyboard()`.

### Mouse (`page.mouse()`)

#### mouse.click(x, y, options?)
Click at coordinates.
- `x` (double), `y` (double) - viewport coordinates
- `button` ("left" | "right" | "middle") - mouse button (default "left")
- `clickCount` (int) - number of clicks (default 1; use 2 for double-click)
- `delay` (double) - time in ms between mousedown and mouseup (default 0)
- Returns: `void`

#### mouse.dblclick(x, y, options?)
Double-click at coordinates.
- `x` (double), `y` (double) - viewport coordinates
- `button` ("left" | "right" | "middle"), `delay` (double)
- Returns: `void`

#### mouse.down(options?)
Press mouse button (without releasing).
- `button` ("left" | "right" | "middle"), `clickCount` (int)
- Returns: `void`

#### mouse.up(options?)
Release mouse button.
- `button` ("left" | "right" | "middle"), `clickCount` (int)
- Returns: `void`

#### mouse.move(x, y, options?)
Move mouse to coordinates.
- `x` (double), `y` (double) - target coordinates
- `steps` (int) - number of intermediate move events (default 1; higher values create smoother movement)
- Returns: `void`

#### mouse.wheel(deltaX, deltaY)
Scroll using the mouse wheel.
- `deltaX` (double) - horizontal scroll pixels (positive = right)
- `deltaY` (double) - vertical scroll pixels (positive = down)
- Returns: `void`

```java
// Drag and drop using mouse
page.mouse().move(100, 100);
page.mouse().down();
page.mouse().move(300, 300, new Mouse.MoveOptions().setSteps(10));
page.mouse().up();

// Right-click at position
page.mouse().click(200, 150, new Mouse.ClickOptions().setButton(MouseButton.RIGHT));

// Scroll down
page.mouse().wheel(0, 500);

// Double-click
page.mouse().dblclick(200, 150);
```

### Keyboard (`page.keyboard()`)

#### keyboard.press(key, options?)
Press a key (dispatches keydown, keypress/input, and keyup).
- `key` (String) - key name: `"Enter"`, `"Tab"`, `"Escape"`, `"Backspace"`, `"Delete"`, `"ArrowUp"`, `"ArrowDown"`, `"ArrowLeft"`, `"ArrowRight"`, `"Home"`, `"End"`, `"PageUp"`, `"PageDown"`, `"F1"`-`"F12"`, or a single character. Supports modifier prefixes: `"Control+a"`, `"Shift+ArrowDown"`, `"Meta+c"`.
- `delay` (double) - time in ms between keydown and keyup (default 0)
- Returns: `void`

#### keyboard.type(text, options?)
Type text character by character. Each character generates keydown, keypress, input, and keyup events.
- `text` (String) - text to type
- `delay` (double) - time in ms between key presses (default 0)
- Returns: `void`

#### keyboard.insertText(text)
Insert text directly (dispatches only the `input` event, no keydown/keyup). Useful for special characters not representable with key presses.
- `text` (String) - text to insert
- Returns: `void`

#### keyboard.down(key)
Press key down (dispatch keydown event only). Key stays pressed until `keyboard.up()`.
- `key` (String) - key name
- Returns: `void`

#### keyboard.up(key)
Release key (dispatch keyup event).
- `key` (String) - key name
- Returns: `void`

```java
// Press Enter
page.keyboard().press("Enter");

// Keyboard shortcut: Select All + Copy
page.keyboard().press("Control+a");
page.keyboard().press("Control+c");

// Type text character by character (slow, triggers individual key events)
page.keyboard().type("Hello World", new Keyboard.TypeOptions().setDelay(50));

// Hold Shift and press arrow keys (text selection)
page.keyboard().down("Shift");
page.keyboard().press("ArrowRight");
page.keyboard().press("ArrowRight");
page.keyboard().press("ArrowRight");
page.keyboard().up("Shift");

// Insert emoji or special characters
page.keyboard().insertText("\u2603"); // snowman

// Combined example: focus field, clear it, type new value
page.locator("#search").click();
page.keyboard().press("Control+a");
page.keyboard().type("new search query");
page.keyboard().press("Enter");
```

---

## ElementHandle (Legacy)

> **Prefer `Locator` over `ElementHandle`.** ElementHandle references a specific DOM element and does not auto-wait or auto-retry. If the DOM changes, the handle becomes stale. Locators re-query the DOM on every operation, making them more reliable.

### Migration Guide

| ElementHandle (legacy) | Locator (preferred) |
|------------------------|---------------------|
| `page.querySelector("button")` | `page.locator("button")` |
| `elementHandle.click()` | `locator.click()` |
| `elementHandle.textContent()` | `locator.textContent()` |
| `page.querySelectorAll(".item")` | `page.locator(".item").all()` |
| `page.waitForSelector(".loaded")` | `page.locator(".loaded").waitFor()` |

```java
// AVOID: ElementHandle approach (stale references, no auto-retry)
ElementHandle button = page.querySelector("button.submit");
if (button != null) {
    button.click(); // May throw if element was removed/re-rendered
}

// PREFER: Locator approach (auto-waits, auto-retries, strict)
page.locator("button.submit").click();

// AVOID: querying multiple elements with ElementHandle
List<ElementHandle> items = page.querySelectorAll(".list-item");
for (ElementHandle item : items) {
    System.out.println(item.textContent());
}

// PREFER: Locator approach
List<String> texts = page.locator(".list-item").allTextContents();
```

ElementHandle methods still available but deprecated: `click()`, `dblclick()`, `fill()`, `press()`, `type()`, `check()`, `uncheck()`, `selectOption()`, `setInputFiles()`, `textContent()`, `innerText()`, `innerHTML()`, `getAttribute()`, `inputValue()`, `isVisible()`, `isHidden()`, `isEnabled()`, `isDisabled()`, `isChecked()`, `isEditable()`, `boundingBox()`, `screenshot()`, `scrollIntoViewIfNeeded()`, `hover()`, `focus()`, `evaluate()`, `waitForSelector()`, `querySelector()`, `querySelectorAll()`.

---

## Additional Common Patterns

### Waiting for Network Events

```java
// Wait for a specific request
Request request = page.waitForRequest("**/api/submit", () -> {
    page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Submit")).click();
});

// Wait for a specific response
Response response = page.waitForResponse(
    resp -> resp.url().contains("/api/data") && resp.status() == 200,
    () -> { page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Load")).click(); }
);

// Wait for no network activity
page.waitForLoadState(LoadState.NETWORKIDLE);
```

### Multiple Pages / Popups

```java
// Handle popup windows
Page popup = page.waitForPopup(() -> {
    page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Open in new tab")).click();
});
popup.waitForLoadState();
System.out.println("Popup URL: " + popup.url());
```

### Console and Error Logging

```java
// Capture console messages
page.onConsoleMessage(msg -> System.out.println("Console: " + msg.type() + " " + msg.text()));

// Capture uncaught exceptions
page.onPageError(error -> System.out.println("Page Error: " + error));
```

### Geolocation, Permissions, and Locale

```java
BrowserContext context = browser.newContext(new Browser.NewContextOptions()
    .setGeolocation(40.7128, -74.0060)
    .setPermissions(Arrays.asList("geolocation"))
    .setLocale("en-US")
    .setTimezoneId("America/New_York"));
```

### Authentication State Reuse

```java
// Save auth state after login
BrowserContext context = browser.newContext();
Page page = context.newPage();
page.navigate("https://example.com/login");
page.getByLabel("Username").fill("admin");
page.getByLabel("Password").fill("password");
page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Login")).click();
page.waitForURL("**/dashboard");
context.storageState(new BrowserContext.StorageStateOptions().setPath(Paths.get("auth.json")));

// Reuse auth state in subsequent tests
BrowserContext authedContext = browser.newContext(new Browser.NewContextOptions()
    .setStorageStatePath(Paths.get("auth.json")));
Page authedPage = authedContext.newPage();
authedPage.navigate("https://example.com/dashboard"); // already logged in
```

### Retry and Timeout Configuration

```java
// Set timeouts at various levels
browser.newContext(new Browser.NewContextOptions().setNavigationTimeout(60000));
context.setDefaultTimeout(15000);            // all operations
context.setDefaultNavigationTimeout(30000);  // navigation only
page.setDefaultTimeout(10000);               // page-level override
```
