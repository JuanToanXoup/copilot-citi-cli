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
