# Selenium WebDriver Java API Reference

> Compact reference for AI agents. Covers Selenium 4.x Java API.

## Maven Dependency

```xml
<dependency>
  <groupId>org.seleniumhq.selenium</groupId>
  <artifactId>selenium-java</artifactId>
  <version>4.27.0</version>
</dependency>
```

---

## 1. WebDriver — `org.openqa.selenium.WebDriver`

Core interface for browser automation. Implementations: `ChromeDriver`, `FirefoxDriver`, `EdgeDriver`, `SafariDriver`.

### Instantiation

```java
// Selenium 4+ — Selenium Manager auto-downloads drivers
WebDriver driver = new ChromeDriver();
WebDriver driver = new FirefoxDriver();
WebDriver driver = new EdgeDriver();

// With options
ChromeOptions options = new ChromeOptions();
options.addArguments("--headless=new", "--no-sandbox", "--disable-gpu");
options.addArguments("--window-size=1920,1080");
WebDriver driver = new ChromeDriver(options);
```

### Navigation

| Method | Description |
|---|---|
| `driver.get(String url)` | Navigate to URL (waits for page load) |
| `driver.getCurrentUrl()` | Returns current URL as `String` |
| `driver.getTitle()` | Returns page title as `String` |
| `driver.getPageSource()` | Returns page HTML source as `String` |
| `driver.navigate().to(String url)` | Navigate to URL |
| `driver.navigate().back()` | Browser back |
| `driver.navigate().forward()` | Browser forward |
| `driver.navigate().refresh()` | Reload current page |

### Window / Frame Management

```java
driver.manage().window().maximize();
driver.manage().window().setSize(new Dimension(1024, 768));
String original = driver.getWindowHandle();       // current handle
Set<String> all = driver.getWindowHandles();      // all handles
driver.switchTo().window(handle);
driver.switchTo().newWindow(WindowType.TAB);      // Selenium 4+
driver.switchTo().frame(indexOrNameOrElement);     // enter frame
driver.switchTo().parentFrame();                  // up one frame
driver.switchTo().defaultContent();               // top-level
Alert alert = driver.switchTo().alert();          // getText(), accept(), dismiss(), sendKeys()
```

### Cookies

```java
driver.manage().addCookie(new Cookie("key", "value"));
driver.manage().getCookieNamed("key");   // Cookie
driver.manage().getCookies();            // Set<Cookie>
driver.manage().deleteCookieNamed("key");
driver.manage().deleteAllCookies();
```

### Timeouts

```java
driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10));
driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30));
driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(30));
```

### Screenshots & JavaScript

```java
File src = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
JavascriptExecutor js = (JavascriptExecutor) driver;
js.executeScript("return document.title;");
js.executeScript("arguments[0].click();", element);
js.executeScript("window.scrollTo(0, document.body.scrollHeight);");
```

### Cleanup

```java
driver.close();   // Close current window/tab
driver.quit();    // Close all windows and end session — ALWAYS call in finally/teardown
```

---

## 2. Locators — `org.openqa.selenium.By`

### Finding Elements

```java
WebElement el = driver.findElement(By.xxx("value"));       // first match, throws NoSuchElementException
List<WebElement> els = driver.findElements(By.xxx("value")); // all matches, empty list if none
// Also callable on WebElement to search within subtree:
WebElement child = parentElement.findElement(By.xxx("value"));
```

### By Strategies

| Strategy | Method | Matches | Example |
|---|---|---|---|
| ID | `By.id("val")` | `id` attribute | `By.id("login-btn")` |
| Name | `By.name("val")` | `name` attribute | `By.name("username")` |
| Class Name | `By.className("val")` | `class` attribute (single) | `By.className("btn-primary")` |
| Tag Name | `By.tagName("val")` | HTML tag | `By.tagName("input")` |
| Link Text | `By.linkText("val")` | Exact `<a>` text | `By.linkText("Sign In")` |
| Partial Link Text | `By.partialLinkText("val")` | Partial `<a>` text | `By.partialLinkText("Sign")` |
| CSS Selector | `By.cssSelector("val")` | CSS selector | `By.cssSelector("div.main > input[type='text']")` |
| XPath | `By.xpath("val")` | XPath expression | `By.xpath("//input[@data-testid='email']")` |

### Relative Locators (Selenium 4+)

```java
import static org.openqa.selenium.support.locators.RelativeLocator.with;

// Find element near another element
WebElement el = driver.findElement(
    with(By.tagName("input")).above(By.id("password"))
);
// Methods: .above(), .below(), .toLeftOf(), .toRightOf(), .near()
// Can chain: with(By.tagName("button")).below(By.id("email")).toRightOf(By.id("cancel"))
```

### CSS Selectors: `#id` `.class` `tag` `[attr='val']` `parent > child` `ancestor desc` `:nth-child(n)` `:not(.x)`

### XPath: `//tag[@attr='val']` `//tag[contains(@attr,'v')]` `//tag[text()='t']` `//tag[contains(text(),'t')]` `(//tag)[1]` `//tag[@a='x' and @b='y']` `//tag[starts-with(@id,'p')]`

---

## 3. WebElement — `org.openqa.selenium.WebElement`

### Methods

| Method | Returns | Description |
|---|---|---|
| `click()` | `void` | Click the element |
| `sendKeys(CharSequence... keys)` | `void` | Type text / send keys |
| `clear()` | `void` | Clear input/textarea value |
| `submit()` | `void` | Submit the form |
| `getText()` | `String` | Visible inner text |
| `getTagName()` | `String` | HTML tag name (lowercase) |
| `getAttribute(String name)` | `String` | HTML attribute value |
| `getDomAttribute(String name)` | `String` | Attribute from DOM (Selenium 4+) |
| `getDomProperty(String name)` | `String` | JS property value (Selenium 4+) |
| `getCssValue(String prop)` | `String` | Computed CSS value |
| `getSize()` | `Dimension` | Element width/height |
| `getLocation()` | `Point` | Element x/y position |
| `getRect()` | `Rectangle` | Combined location + size |
| `isDisplayed()` | `boolean` | Is element visible? |
| `isEnabled()` | `boolean` | Is element enabled? |
| `isSelected()` | `boolean` | Is checkbox/radio selected? |
| `getScreenshotAs(OutputType<X>)` | `X` | Screenshot of element |
| `getShadowRoot()` | `SearchContext` | Access shadow DOM (Selenium 4+) |
| `getAriaRole()` | `String` | ARIA role (Selenium 4+) |
| `getAccessibleName()` | `String` | Accessible name (Selenium 4+) |

### Special Keys

```java
import org.openqa.selenium.Keys;

element.sendKeys("text", Keys.ENTER);
element.sendKeys(Keys.TAB);
element.sendKeys(Keys.CONTROL, "a");  // Select all
element.sendKeys(Keys.BACK_SPACE);
// Keys: ENTER, RETURN, TAB, ESCAPE, BACK_SPACE, DELETE, SPACE,
//       ARROW_UP, ARROW_DOWN, ARROW_LEFT, ARROW_RIGHT,
//       HOME, END, PAGE_UP, PAGE_DOWN,
//       SHIFT, CONTROL, ALT, COMMAND (Mac), F1-F12
```

---

## 4. Waits

### Implicit Wait (global)

```java
driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10));
```

### Explicit Wait — `WebDriverWait`

```java
import org.openqa.selenium.support.ui.WebDriverWait;
import org.openqa.selenium.support.ui.ExpectedConditions;

WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
WebElement el = wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("result")));
```

### Common ExpectedConditions

| Condition | Returns | Use Case |
|---|---|---|
| `presenceOfElementLocated(By)` | `WebElement` | Element in DOM |
| `visibilityOfElementLocated(By)` | `WebElement` | Element visible |
| `visibilityOf(WebElement)` | `WebElement` | Known element visible |
| `elementToBeClickable(By)` | `WebElement` | Element clickable |
| `elementToBeClickable(WebElement)` | `WebElement` | Known element clickable |
| `invisibilityOfElementLocated(By)` | `Boolean` | Element gone/hidden |
| `textToBePresentInElement(WebElement, String)` | `Boolean` | Text appears |
| `textToBePresentInElementLocated(By, String)` | `Boolean` | Text appears |
| `titleIs(String)` | `Boolean` | Exact title match |
| `titleContains(String)` | `Boolean` | Partial title match |
| `urlContains(String)` | `Boolean` | URL contains text |
| `urlToBe(String)` | `Boolean` | Exact URL match |
| `frameToBeAvailableAndSwitchToIt(By)` | `WebDriver` | Frame ready |
| `alertIsPresent()` | `Alert` | Alert is showing |
| `numberOfElementsToBe(By, int)` | `List<WebElement>` | Exact count |
| `numberOfElementsToBeMoreThan(By, int)` | `List<WebElement>` | Min count |
| `stalenessOf(WebElement)` | `Boolean` | Element detached |
| `attributeToBe(By, String, String)` | `Boolean` | Attribute value matches |
| `attributeContains(By, String, String)` | `Boolean` | Attribute contains text |

### Custom Wait Condition

```java
wait.until(driver -> driver.findElement(By.id("status")).getText().equals("Done"));
```

### Fluent Wait

```java
Wait<WebDriver> fw = new FluentWait<>(driver)
    .withTimeout(Duration.ofSeconds(30)).pollingEvery(Duration.ofMillis(500))
    .ignoring(NoSuchElementException.class);
WebElement el = fw.until(d -> d.findElement(By.id("loaded")));
```

---

## 5. Actions API — `org.openqa.selenium.interactions.Actions`

Chainable API for complex user interactions. Call `.perform()` to execute.

```java
Actions actions = new Actions(driver);
```

### Mouse Actions

```java
actions.click(element).perform();
actions.doubleClick(element).perform();
actions.contextClick(element).perform();           // right-click
actions.moveToElement(element).perform();           // hover
actions.moveToElement(element, xOff, yOff).perform(); // hover with offset
actions.clickAndHold(element).perform();
actions.release().perform();
actions.moveByOffset(x, y).perform();

// Drag and drop
actions.dragAndDrop(source, target).perform();
actions.dragAndDropBy(source, xOff, yOff).perform();
actions.clickAndHold(source).moveToElement(target).release().perform();

// Scroll (Selenium 4+)
actions.scrollToElement(element).perform();
actions.scrollByAmount(deltaX, deltaY).perform();
```

### Keyboard Actions

```java
actions.sendKeys(element, "text").perform();
actions.sendKeys(Keys.ENTER).perform();
actions.keyDown(Keys.SHIFT).sendKeys("abc").keyUp(Keys.SHIFT).perform(); // ABC
actions.keyDown(Keys.CONTROL).sendKeys("c").keyUp(Keys.CONTROL).perform(); // Ctrl+C
```

### Chaining

```java
actions
    .moveToElement(menu)
    .pause(Duration.ofMillis(500))
    .click(submenuItem)
    .perform();
```

---

## 6. Select (Dropdowns) — `org.openqa.selenium.support.ui.Select`

For `<select>` elements only.

```java
import org.openqa.selenium.support.ui.Select;

Select select = new Select(driver.findElement(By.id("dropdown")));
```

### Selection Methods

| Method | Description |
|---|---|
| `selectByVisibleText(String text)` | Select by displayed text |
| `selectByValue(String value)` | Select by `value` attribute |
| `selectByIndex(int index)` | Select by zero-based index |
| `deselectAll()` | Deselect all (multi-select only) |
| `deselectByVisibleText(String)` | Deselect by text |
| `deselectByValue(String)` | Deselect by value |
| `deselectByIndex(int)` | Deselect by index |

### Inspection Methods

| Method | Returns | Description |
|---|---|---|
| `getOptions()` | `List<WebElement>` | All options |
| `getAllSelectedOptions()` | `List<WebElement>` | All selected options |
| `getFirstSelectedOption()` | `WebElement` | First/only selected option |
| `isMultiple()` | `boolean` | Is multi-select? |

---

## 7. Page Object Model (POM)

```java
public class LoginPage {
    @FindBy(id = "username") private WebElement usernameInput;
    @FindBy(id = "password") private WebElement passwordInput;
    @FindBy(css = "button[type='submit']") private WebElement loginBtn;

    public LoginPage(WebDriver driver) { PageFactory.initElements(driver, this); }
    public void login(String user, String pass) {
        usernameInput.sendKeys(user); passwordInput.sendKeys(pass); loginBtn.click();
    }
}
// Annotations: @FindBy, @FindBys (AND logic), @FindAll (OR logic)
```

---

## 8. Common Exceptions

`NoSuchElementException` (not found) | `StaleElementReferenceException` (detached from DOM) | `ElementNotInteractableException` (hidden/disabled) | `ElementClickInterceptedException` (obscured) | `TimeoutException` (wait expired) | `NoSuchFrameException` | `NoSuchWindowException` | `InvalidSelectorException` | `NoAlertPresentException` | `SessionNotCreatedException` (driver/browser mismatch) | `WebDriverException` (base)

---

## 9. Common Patterns

### Wait-then-Act

```java
WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
WebElement el = wait.until(ExpectedConditions.elementToBeClickable(By.id("btn")));
el.click();
```

### Safe Element Check (no exception)

```java
List<WebElement> els = driver.findElements(By.id("optional"));
if (!els.isEmpty()) {
    els.get(0).click();
}
```

### Scroll Into View

```java
((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView(true);", element);
```

### Wait for Page Load Complete

```java
wait.until(d -> ((JavascriptExecutor) d)
    .executeScript("return document.readyState").equals("complete"));
```

### Handle Stale Element

```java
// Re-find element after DOM changes
wait.until(ExpectedConditions.stalenessOf(oldElement));
WebElement fresh = driver.findElement(By.id("same-id"));
```

### Upload File

```java
WebElement upload = driver.findElement(By.cssSelector("input[type='file']"));
upload.sendKeys("/absolute/path/to/file.pdf");
```

### Switch to New Window After Click

```java
String original = driver.getWindowHandle();
element.click();
wait.until(ExpectedConditions.numberOfWindowsToBe(2));
for (String h : driver.getWindowHandles())
    if (!h.equals(original)) { driver.switchTo().window(h); break; }
```

### Shadow DOM (Selenium 4+)

```java
SearchContext shadow = driver.findElement(By.id("host")).getShadowRoot();
WebElement inner = shadow.findElement(By.cssSelector(".inner"));
```

---

## 10. Browser Options

```java
ChromeOptions options = new ChromeOptions();
options.addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage",
    "--disable-gpu", "--window-size=1920,1080");
options.setPageLoadStrategy(PageLoadStrategy.NORMAL); // NORMAL, EAGER, NONE
options.setAcceptInsecureCerts(true);
Map<String, Object> prefs = new HashMap<>();
prefs.put("download.default_directory", "/tmp/downloads");
options.setExperimentalOption("prefs", prefs);
// Firefox: FirefoxOptions ffOpts = new FirefoxOptions(); ffOpts.addArguments("-headless");
```

---

## 11. Remote WebDriver / Selenium Grid

```java
ChromeOptions options = new ChromeOptions();
WebDriver driver = new RemoteWebDriver(
    new URL("http://localhost:4444"), options
);
```

---

## Import Cheat Sheet

```java
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.FluentWait;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.FindBy;
import org.openqa.selenium.support.PageFactory;
import static org.openqa.selenium.support.locators.RelativeLocator.with;

import java.time.Duration;
```

---

## 12. DevTools Protocol (CDP) — Selenium 4+

Chrome DevTools Protocol access via `ChromeDriver`. Enables network interception, console logs, performance metrics, geolocation mocking, and more.

### Getting Started

```java
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.devtools.DevTools;
import org.openqa.selenium.devtools.v129.network.Network;
import org.openqa.selenium.devtools.v129.log.Log;
import org.openqa.selenium.devtools.v129.performance.Performance;
import org.openqa.selenium.devtools.v129.emulation.Emulation;

ChromeDriver driver = new ChromeDriver();
DevTools devTools = driver.getDevTools();
devTools.createSession();
```

> **Note:** Replace `v129` with the CDP version matching your Chrome browser version. Selenium ships adapters for recent versions.

### Network Interception

```java
// Enable network tracking
devTools.send(Network.enable(Optional.empty(), Optional.empty(), Optional.empty()));

// Listen to requests
devTools.addListener(Network.requestWillBeSent(), request -> {
    System.out.println("Request: " + request.getRequest().getUrl());
    System.out.println("Method:  " + request.getRequest().getMethod());
});

// Listen to responses
devTools.addListener(Network.responseReceived(), response -> {
    System.out.println("URL:    " + response.getResponse().getUrl());
    System.out.println("Status: " + response.getResponse().getStatus());
});

// Block specific URLs (request interception)
devTools.send(Network.setBlockedURLs(List.of("*.png", "*.jpg", "*.gif", "*.css")));

// Intercept and modify requests (Fetch domain)
import org.openqa.selenium.devtools.v129.fetch.Fetch;

devTools.send(Fetch.enable(Optional.empty(), Optional.empty()));
devTools.addListener(Fetch.requestPaused(), req -> {
    String url = req.getRequest().getUrl();
    if (url.contains("analytics")) {
        devTools.send(Fetch.failRequest(req.getRequestId(),
            Network.ErrorReason.BLOCKEDBYCLIENT));
    } else {
        devTools.send(Fetch.continueRequest(
            req.getRequestId(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty()));
    }
});
```

### Console Logs

```java
devTools.send(Log.enable());
devTools.addListener(Log.entryAdded(), entry -> {
    System.out.println("[" + entry.getLevel() + "] " + entry.getText());
    System.out.println("  Source: " + entry.getSource());
    System.out.println("  URL:    " + entry.getUrl().orElse("N/A"));
});

// Also capture console.log via Runtime domain
import org.openqa.selenium.devtools.v129.runtime.Runtime;

devTools.send(Runtime.enable());
devTools.addListener(Runtime.consoleAPICalled(), event -> {
    event.getArgs().forEach(arg ->
        System.out.println("console." + event.getType() + ": " + arg.getValue().orElse(""))
    );
});
```

### Performance Metrics

```java
devTools.send(Performance.enable(Optional.empty()));
List<Performance.Metric> metrics = devTools.send(Performance.getMetrics());
for (var metric : metrics) {
    System.out.println(metric.getName() + " = " + metric.getValue());
}
// Common metrics: Timestamp, Documents, Frames, JSEventListeners,
//   Nodes, LayoutCount, RecalcStyleCount, JSHeapUsedSize, JSHeapTotalSize
```

### Geolocation Mocking

```java
// Override geolocation to New York City
devTools.send(Emulation.setGeolocationOverride(
    Optional.of(40.7128),   // latitude
    Optional.of(-74.0060),  // longitude
    Optional.of(100.0)      // accuracy in meters
));
driver.get("https://www.google.com/maps");

// Clear override
devTools.send(Emulation.clearGeolocationOverride());
```

### Network Conditions (Throttling)

```java
// Emulate slow 3G
devTools.send(Network.emulateNetworkConditions(
    false,           // offline
    150,             // latency (ms)
    500 * 1024,      // download throughput (bytes/sec) — 500 KB/s
    200 * 1024,      // upload throughput (bytes/sec) — 200 KB/s
    Optional.of(Network.ConnectionType.CELLULAR3G)
));

// Simulate offline
devTools.send(Network.emulateNetworkConditions(
    true, 0, 0, 0, Optional.empty()
));
```

### Device Emulation

```java
// Emulate mobile viewport
devTools.send(Emulation.setDeviceMetricsOverride(
    375, 812, 3.0, true,   // width, height, deviceScaleFactor, mobile
    Optional.empty(), Optional.empty(), Optional.empty(),
    Optional.empty(), Optional.empty(), Optional.empty(),
    Optional.empty(), Optional.empty(), Optional.empty(),
    Optional.empty()
));
```

### Authentication (Basic Auth)

```java
// Handle Basic Auth via CDP — Selenium 4+
((HasAuthentication) driver).register(
    uri -> uri.getHost().contains("secure-site.com"),
    UsernameAndPassword.of("admin", "password123")
);
driver.get("https://secure-site.com/protected");
```

---

## 13. BiDi (WebDriver BiDi) — Selenium 4.8+

WebDriver BiDi is the next-generation protocol replacing CDP with a cross-browser standard. Selenium 4.8+ provides partial BiDi support.

### Overview

| Feature | CDP | BiDi |
|---|---|---|
| Browser support | Chromium only | Cross-browser standard |
| Protocol | Chrome DevTools Protocol | W3C WebDriver BiDi |
| Direction | One-way + events | Bidirectional |
| Future | Vendor-specific | W3C standard |

### Browsing Context

```java
import org.openqa.selenium.bidi.browsingcontext.BrowsingContext;
import org.openqa.selenium.bidi.browsingcontext.NavigationResult;
import org.openqa.selenium.bidi.browsingcontext.ReadinessState;
import org.openqa.selenium.WindowType;

// Create a new browsing context (tab)
BrowsingContext context = new BrowsingContext(driver, WindowType.TAB);

// Navigate
NavigationResult nav = context.navigate("https://example.com",
    ReadinessState.COMPLETE);
System.out.println("Navigation ID: " + nav.getNavigationId());

// Capture screenshot (returns base64)
String screenshot = context.captureScreenshot();

// Close context
context.close();
```

### Network Interception (BiDi)

```java
import org.openqa.selenium.bidi.network.*;

// Requires BiDi-enabled browser
try (Network network = new Network(driver)) {
    // Add request intercept
    String interceptId = network.addIntercept(
        new AddInterceptParameters(InterceptPhase.BEFORE_REQUEST_SENT)
    );

    // Listen for intercepted requests
    network.onBeforeRequestSent(event -> {
        System.out.println("Request: " + event.getRequest().getUrl());
    });

    // Continue intercepted request
    network.onBeforeRequestSent(event -> {
        network.continueRequest(new ContinueRequestParameters(event.getRequest().getRequestId()));
    });

    driver.get("https://example.com");

    // Remove intercept
    network.removeIntercept(interceptId);
}
```

### Log Event Listening (BiDi)

```java
import org.openqa.selenium.bidi.log.*;

try (LogInspector logInspector = new LogInspector(driver)) {
    // Listen for console log entries
    logInspector.onConsoleEntry(entry -> {
        System.out.println("[" + entry.getLevel() + "] " + entry.getText());
        System.out.println("  Method: " + entry.getMethod());
    });

    // Listen for JavaScript errors
    logInspector.onJavaScriptException(entry -> {
        System.out.println("JS Error: " + entry.getText());
        System.out.println("  Stack: " + entry.getStackTrace());
    });

    driver.get("https://example.com");
    ((JavascriptExecutor) driver).executeScript("console.log('Hello BiDi');");
}
```

### Script Evaluation (BiDi)

```java
import org.openqa.selenium.bidi.script.*;

try (Script script = new Script(driver)) {
    // Evaluate JavaScript expression
    EvaluateResult result = script.evaluateFunction(
        context.getId(),
        "() => document.title",
        true,   // awaitPromise
        Optional.empty()
    );
    System.out.println("Title: " + result.getResult().getValue().orElse(""));
}
```

---

## 14. Logging

Selenium provides access to various browser and driver logs.

### Log Types

| Constant | Value | Description |
|---|---|---|
| `LogType.BROWSER` | `"browser"` | Browser console logs (JS errors, console.log) |
| `LogType.DRIVER` | `"driver"` | WebDriver internal logs |
| `LogType.PERFORMANCE` | `"performance"` | Performance/network events (Chrome) |
| `LogType.CLIENT` | `"client"` | Client-side Selenium logs |
| `LogType.SERVER` | `"server"` | Server-side logs (Grid) |

### Retrieving Logs

```java
import org.openqa.selenium.logging.*;

// Get browser console logs
LogEntries logs = driver.manage().logs().get(LogType.BROWSER);
for (LogEntry entry : logs) {
    System.out.println(entry.getTimestamp() + " [" + entry.getLevel() + "] " + entry.getMessage());
}

// Get available log types
Set<String> logTypes = driver.manage().logs().getAvailableLogTypes();
```

### LogEntry Fields

| Method | Returns | Description |
|---|---|---|
| `getLevel()` | `Level` | Log level: `SEVERE`, `WARNING`, `INFO`, `FINE`, etc. |
| `getMessage()` | `String` | Log message text |
| `getTimestamp()` | `long` | Epoch timestamp in milliseconds |

### Enable Performance Logging

```java
ChromeOptions options = new ChromeOptions();
LoggingPreferences logPrefs = new LoggingPreferences();
logPrefs.enable(LogType.BROWSER, Level.ALL);
logPrefs.enable(LogType.PERFORMANCE, Level.ALL);
options.setCapability("goog:loggingPrefs", logPrefs);

WebDriver driver = new ChromeDriver(options);
driver.get("https://example.com");

// Retrieve performance logs (network, rendering, etc.)
LogEntries perfLogs = driver.manage().logs().get(LogType.PERFORMANCE);
for (LogEntry entry : perfLogs) {
    // Each message is a JSON string with Chrome DevTools event data
    System.out.println(entry.getMessage());
}
```

### Filter Logs by Severity

```java
LogEntries logs = driver.manage().logs().get(LogType.BROWSER);
List<LogEntry> errors = logs.getAll().stream()
    .filter(e -> e.getLevel().intValue() >= Level.SEVERE.intValue())
    .collect(Collectors.toList());
if (!errors.isEmpty()) {
    System.out.println("Found " + errors.size() + " JS errors on page!");
    errors.forEach(e -> System.out.println("  " + e.getMessage()));
}
```

---

## 15. Selenium Grid

Selenium Grid allows running tests on remote machines, enabling parallel execution across multiple browsers and OS combinations.

### Architecture Overview

| Component | Role |
|---|---|
| **Hub** | Central point that receives test requests and routes to Nodes |
| **Node** | Machine that runs browser instances; registers with Hub |
| **Router** | Routes requests to correct Node (Grid 4 component) |
| **Distributor** | Assigns sessions to Nodes (Grid 4 component) |
| **Session Map** | Tracks active sessions (Grid 4 component) |

### Standalone Mode (Single Machine)

```bash
# Download selenium-server JAR
java -jar selenium-server-4.27.0.jar standalone

# Default URL: http://localhost:4444
# Grid console: http://localhost:4444/ui
```

### Hub and Node Mode

```bash
# Start Hub
java -jar selenium-server-4.27.0.jar hub

# Start Node (on same or different machine)
java -jar selenium-server-4.27.0.jar node --hub http://hub-ip:4444
```

### Docker — Standalone Chrome

```bash
docker run -d -p 4444:4444 -p 7900:7900 --shm-size="2g" \
    selenium/standalone-chrome:latest
# VNC viewer available at http://localhost:7900 (password: secret)
```

### Docker Compose — Hub + Multiple Nodes

```yaml
# docker-compose.yml
version: "3"
services:
  hub:
    image: selenium/hub:latest
    ports:
      - "4442:4442"
      - "4443:4443"
      - "4444:4444"

  chrome-node:
    image: selenium/node-chrome:latest
    shm_size: 2gb
    depends_on:
      - hub
    environment:
      - SE_EVENT_BUS_HOST=hub
      - SE_EVENT_BUS_PUBLISH_PORT=4442
      - SE_EVENT_BUS_SUBSCRIBE_PORT=4443
      - SE_NODE_MAX_SESSIONS=4

  firefox-node:
    image: selenium/node-firefox:latest
    shm_size: 2gb
    depends_on:
      - hub
    environment:
      - SE_EVENT_BUS_HOST=hub
      - SE_EVENT_BUS_PUBLISH_PORT=4442
      - SE_EVENT_BUS_SUBSCRIBE_PORT=4443
      - SE_NODE_MAX_SESSIONS=4

  edge-node:
    image: selenium/node-edge:latest
    shm_size: 2gb
    depends_on:
      - hub
    environment:
      - SE_EVENT_BUS_HOST=hub
      - SE_EVENT_BUS_PUBLISH_PORT=4442
      - SE_EVENT_BUS_SUBSCRIBE_PORT=4443
```

```bash
docker compose up -d --scale chrome-node=3
```

### RemoteWebDriver with Grid

```java
import org.openqa.selenium.remote.RemoteWebDriver;

ChromeOptions chromeOpts = new ChromeOptions();
chromeOpts.addArguments("--headless=new");

WebDriver driver = new RemoteWebDriver(
    new URL("http://localhost:4444"), chromeOpts
);

// Get session ID
SessionId sessionId = ((RemoteWebDriver) driver).getSessionId();
System.out.println("Session: " + sessionId);

// Execute on remote
driver.get("https://example.com");
System.out.println(driver.getTitle());
driver.quit();
```

### Grid Status API

```bash
# Check grid status
curl http://localhost:4444/status

# Response includes: ready (boolean), nodes[], sessions[]
```

```java
// Programmatic status check
import java.net.http.*;

HttpClient client = HttpClient.newHttpClient();
HttpRequest request = HttpRequest.newBuilder()
    .uri(URI.create("http://localhost:4444/status"))
    .GET().build();
HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
System.out.println(response.body()); // JSON with grid status
```

### Grid Configuration (TOML)

```toml
# grid-config.toml
[node]
detect-drivers = false
max-sessions = 6

[[node.driver-configuration]]
display-name = "Chrome"
stereotype = '{"browserName": "chrome", "browserVersion": "stable"}'
max-sessions = 4

[[node.driver-configuration]]
display-name = "Firefox"
stereotype = '{"browserName": "firefox"}'
max-sessions = 2
```

```bash
java -jar selenium-server-4.27.0.jar node --config grid-config.toml
```

---

## 16. Advanced Waits & Conditions

### Custom ExpectedCondition Implementation

```java
import org.openqa.selenium.support.ui.ExpectedCondition;

// Custom condition as a class
public class ElementHasClass implements ExpectedCondition<Boolean> {
    private final By locator;
    private final String cssClass;

    public ElementHasClass(By locator, String cssClass) {
        this.locator = locator;
        this.cssClass = cssClass;
    }

    @Override
    public Boolean apply(WebDriver driver) {
        WebElement el = driver.findElement(locator);
        String classes = el.getAttribute("class");
        return classes != null && classes.contains(cssClass);
    }

    @Override
    public String toString() {
        return "element " + locator + " to have class '" + cssClass + "'";
    }
}

// Usage
wait.until(new ElementHasClass(By.id("status"), "active"));
```

### Wait for AJAX / jQuery Calls to Complete

```java
// Wait for jQuery AJAX
wait.until(driver -> (Boolean) ((JavascriptExecutor) driver)
    .executeScript("return jQuery.active == 0"));

// Wait for generic XHR (inject tracking)
((JavascriptExecutor) driver).executeScript(
    "window.__pendingXHR = 0;" +
    "var origOpen = XMLHttpRequest.prototype.open;" +
    "XMLHttpRequest.prototype.open = function() {" +
    "  window.__pendingXHR++;" +
    "  this.addEventListener('loadend', function() { window.__pendingXHR--; });" +
    "  origOpen.apply(this, arguments);" +
    "};");
// Then wait:
wait.until(d -> (Long) ((JavascriptExecutor) d)
    .executeScript("return window.__pendingXHR") == 0);
```

### Wait for Angular to Stabilize

```java
// Angular (v2+) — wait for zone.js stability
wait.until(driver -> {
    try {
        return (Boolean) ((JavascriptExecutor) driver).executeScript(
            "return window.getAllAngularTestabilities().findIndex(" +
            "  t => !t.isStable()) === -1;");
    } catch (JavascriptException e) {
        return true; // Not an Angular app
    }
});

// AngularJS (v1.x) — wait for $http and $timeout
wait.until(driver -> (Boolean) ((JavascriptExecutor) driver).executeScript(
    "return angular.element(document).injector().get('$http').pendingRequests.length === 0"));
```

### Wait for React to Finish Rendering

```java
// Wait for React root to have rendered content
wait.until(driver -> {
    Object result = ((JavascriptExecutor) driver).executeScript(
        "var root = document.getElementById('root');" +
        "return root && root._reactRootContainer != null && root.children.length > 0;");
    return Boolean.TRUE.equals(result);
});
```

### Wait for Element Attribute to Change

```java
// Wait for attribute to reach specific value
wait.until(ExpectedConditions.attributeToBe(By.id("progress"), "aria-valuenow", "100"));

// Wait for attribute to contain value
wait.until(ExpectedConditions.attributeContains(By.id("status"), "class", "completed"));

// Custom: wait for attribute to change from its current value
String oldValue = driver.findElement(By.id("counter")).getAttribute("data-count");
wait.until(driver2 -> {
    String current = driver2.findElement(By.id("counter")).getAttribute("data-count");
    return !current.equals(oldValue);
});
```

### Wait for Download to Complete

```java
// Wait for file to appear and stop growing
public static void waitForDownload(Path downloadDir, String fileNamePattern,
                                    Duration timeout) throws Exception {
    long deadline = System.currentTimeMillis() + timeout.toMillis();
    Path file = null;
    while (System.currentTimeMillis() < deadline) {
        try (var files = Files.list(downloadDir)) {
            file = files.filter(f -> f.getFileName().toString().matches(fileNamePattern))
                        .findFirst().orElse(null);
        }
        if (file != null) {
            // Ensure file is not still being written
            long size1 = Files.size(file);
            Thread.sleep(500);
            long size2 = Files.size(file);
            if (size1 == size2 && size1 > 0) return;
        }
        Thread.sleep(500);
    }
    throw new TimeoutException("Download not completed: " + fileNamePattern);
}
```

### Wait for Element Count to Stabilize

```java
// Useful for dynamically loading lists
wait.until(driver -> {
    int count1 = driver.findElements(By.cssSelector(".item")).size();
    try { Thread.sleep(300); } catch (InterruptedException ignored) {}
    int count2 = driver.findElements(By.cssSelector(".item")).size();
    return count1 == count2 && count1 > 0;
});
```

---

## 17. File Downloads

### ChromeOptions — Auto-Download Configuration

```java
ChromeOptions options = new ChromeOptions();
Map<String, Object> prefs = new HashMap<>();
prefs.put("download.default_directory", "/tmp/selenium-downloads");
prefs.put("download.prompt_for_download", false);
prefs.put("download.directory_upgrade", true);
prefs.put("safebrowsing.enabled", true);
// Disable "keep" / "discard" download warning for certain file types
prefs.put("plugins.always_open_pdf_externally", true);
options.setExperimentalOption("prefs", prefs);

WebDriver driver = new ChromeDriver(options);
```

### Headless Chrome Download (requires CDP command)

```java
// Headless Chrome ignores download.default_directory — use CDP
ChromeDriver driver = new ChromeDriver(options);
Map<String, Object> params = new HashMap<>();
params.put("behavior", "allow");
params.put("downloadPath", "/tmp/selenium-downloads");
driver.executeCdpCommand("Page.setDownloadBehavior", params);
```

### FirefoxProfile Download Settings

```java
FirefoxOptions ffOptions = new FirefoxOptions();
ffOptions.addPreference("browser.download.folderList", 2); // 2 = custom dir
ffOptions.addPreference("browser.download.dir", "/tmp/selenium-downloads");
ffOptions.addPreference("browser.download.useDownloadDir", true);
// Auto-download these MIME types without prompt
ffOptions.addPreference("browser.helperApps.neverAsk.saveToDisk",
    "application/pdf,application/octet-stream,text/csv," +
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet," +
    "application/zip");
ffOptions.addPreference("pdfjs.disabled", true); // Don't open PDF in browser

WebDriver driver = new FirefoxDriver(ffOptions);
```

### Wait for Downloaded File

```java
Path downloadDir = Path.of("/tmp/selenium-downloads");

// Click download link
driver.findElement(By.id("download-btn")).click();

// Wait for file
WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30));
wait.until(d -> {
    try (var files = Files.list(downloadDir)) {
        return files.anyMatch(f -> f.getFileName().toString().endsWith(".csv")
            && !f.getFileName().toString().endsWith(".crdownload")); // Chrome partial
    } catch (IOException e) { return false; }
});

// Get the downloaded file
Path downloaded;
try (var files = Files.list(downloadDir)) {
    downloaded = files.filter(f -> f.getFileName().toString().endsWith(".csv"))
        .max(Comparator.comparingLong(f -> f.toFile().lastModified()))
        .orElseThrow();
}
System.out.println("Downloaded: " + downloaded);
```

---

## 18. Network & Proxy

### Proxy via ChromeOptions

```java
import org.openqa.selenium.Proxy;

Proxy proxy = new Proxy();
proxy.setHttpProxy("proxy-host:8080");
proxy.setSslProxy("proxy-host:8080");
// Optional: bypass list
proxy.setNoProxy("localhost,127.0.0.1,.internal.company.com");

ChromeOptions options = new ChromeOptions();
options.setProxy(proxy);
WebDriver driver = new ChromeDriver(options);
```

### SOCKS Proxy

```java
Proxy proxy = new Proxy();
proxy.setSocksProxy("socks-host:1080");
proxy.setSocksVersion(5);
proxy.setSocksUsername("user");
proxy.setSocksPassword("pass");

ChromeOptions options = new ChromeOptions();
options.setProxy(proxy);
```

### Proxy via Chrome Arguments

```java
ChromeOptions options = new ChromeOptions();
options.addArguments("--proxy-server=http://proxy-host:8080");
// Or SOCKS:
options.addArguments("--proxy-server=socks5://socks-host:1080");
// Bypass list:
options.addArguments("--proxy-bypass-list=localhost,127.0.0.1");
```

### Firefox Proxy

```java
FirefoxOptions ffOptions = new FirefoxOptions();
ffOptions.addPreference("network.proxy.type", 1); // Manual
ffOptions.addPreference("network.proxy.http", "proxy-host");
ffOptions.addPreference("network.proxy.http_port", 8080);
ffOptions.addPreference("network.proxy.ssl", "proxy-host");
ffOptions.addPreference("network.proxy.ssl_port", 8080);
ffOptions.addPreference("network.proxy.no_proxies_on", "localhost,127.0.0.1");
```

### BrowserMob Proxy Integration

```java
// Maven: net.lightbody.bmp:browsermob-core:2.1.5
import net.lightbody.bmp.BrowserMobProxyServer;
import net.lightbody.bmp.client.ClientUtil;
import net.lightbody.bmp.proxy.CaptureType;

BrowserMobProxyServer bmpProxy = new BrowserMobProxyServer();
bmpProxy.start(0); // Random port
int port = bmpProxy.getPort();

// Enable HAR capture
bmpProxy.enableHarCaptureTypes(CaptureType.REQUEST_CONTENT, CaptureType.RESPONSE_CONTENT);
bmpProxy.newHar("test-capture");

// Connect to Selenium
Proxy seleniumProxy = ClientUtil.createSeleniumProxy(bmpProxy);
ChromeOptions options = new ChromeOptions();
options.setProxy(seleniumProxy);
options.addArguments("--ignore-certificate-errors"); // BMP uses self-signed cert
WebDriver driver = new ChromeDriver(options);

driver.get("https://example.com");

// Get captured traffic
Har har = bmpProxy.getHar();
har.getLog().getEntries().forEach(entry -> {
    System.out.println(entry.getRequest().getUrl() + " -> " + entry.getResponse().getStatus());
});

// Add request filter
bmpProxy.addRequestFilter((request, contents, messageInfo) -> {
    request.headers().add("X-Custom-Header", "test-value");
    return null; // null = don't modify response
});

// Blacklist URLs
bmpProxy.blacklistRequests(".*\\.doubleclick\\.net.*", 204);

// Cleanup
driver.quit();
bmpProxy.stop();
```

### Capture Network Traffic via Performance Logs

```java
// Alternative without external proxy — Chrome only
ChromeOptions options = new ChromeOptions();
LoggingPreferences logPrefs = new LoggingPreferences();
logPrefs.enable(LogType.PERFORMANCE, Level.ALL);
options.setCapability("goog:loggingPrefs", logPrefs);

WebDriver driver = new ChromeDriver(options);
driver.get("https://example.com");

LogEntries logs = driver.manage().logs().get(LogType.PERFORMANCE);
for (LogEntry entry : logs) {
    String message = entry.getMessage();
    if (message.contains("Network.responseReceived")) {
        // Parse JSON to extract URL, status, headers, timing
        System.out.println(message);
    }
}
```

---

## 19. WebDriver Event Listener

Selenium 4 provides `WebDriverListener` interface and `EventFiringDecorator` for intercepting WebDriver events.

### WebDriverListener Interface (Selenium 4+)

```java
import org.openqa.selenium.support.events.WebDriverListener;
import org.openqa.selenium.support.events.EventFiringDecorator;

public class MyWebDriverListener implements WebDriverListener {

    @Override
    public void beforeFindElement(WebDriver driver, By locator) {
        System.out.println("Finding: " + locator);
    }

    @Override
    public void afterFindElement(WebDriver driver, By locator, WebElement result) {
        System.out.println("Found: " + locator + " -> " + result.getTagName());
    }

    @Override
    public void beforeClick(WebElement element) {
        System.out.println("Clicking: " + element.getTagName()
            + "[" + element.getText() + "]");
    }

    @Override
    public void afterClick(WebElement element) {
        System.out.println("Clicked successfully");
    }

    @Override
    public void beforeGet(WebDriver driver, String url) {
        System.out.println("Navigating to: " + url);
    }

    @Override
    public void afterGet(WebDriver driver, String url) {
        System.out.println("Navigated to: " + driver.getCurrentUrl());
    }

    @Override
    public void beforeNavigateTo(String url, WebDriver driver) {
        System.out.println("navigate().to: " + url);
    }

    @Override
    public void afterNavigateTo(String url, WebDriver driver) {
        System.out.println("Arrived at: " + driver.getCurrentUrl());
    }

    @Override
    public void beforeSendKeys(WebElement element, CharSequence... keysToSend) {
        System.out.println("Typing into: " + element.getTagName());
    }

    @Override
    public void onError(Object target, Method method, Object[] args, InvocationTargetException e) {
        System.err.println("Error in " + method.getName() + ": " + e.getCause().getMessage());
    }
}
```

### Available Listener Methods

| Category | Before | After |
|---|---|---|
| Navigation | `beforeGet`, `beforeNavigateTo`, `beforeNavigateBack`, `beforeNavigateForward`, `beforeNavigateRefresh` | `afterGet`, `afterNavigateTo`, `afterNavigateBack`, `afterNavigateForward`, `afterNavigateRefresh` |
| Find | `beforeFindElement`, `beforeFindElements` | `afterFindElement`, `afterFindElements` |
| Element | `beforeClick`, `beforeSendKeys`, `beforeClear`, `beforeSubmit` | `afterClick`, `afterSendKeys`, `afterClear`, `afterSubmit` |
| Element Info | `beforeGetText`, `beforeGetAttribute`, `beforeIsDisplayed`, `beforeIsEnabled` | `afterGetText`, `afterGetAttribute`, `afterIsDisplayed`, `afterIsEnabled` |
| Window | `beforeMaximize`, `beforeFullscreen`, `beforeSetSize` | `afterMaximize`, `afterFullscreen`, `afterSetSize` |
| Alert | `beforeAccept`, `beforeDismiss`, `beforeSendKeys` (Alert) | `afterAccept`, `afterDismiss`, `afterSendKeys` (Alert) |
| Script | `beforeExecuteScript`, `beforeExecuteAsyncScript` | `afterExecuteScript`, `afterExecuteAsyncScript` |
| Error | — | `onError(Object target, Method method, Object[] args, InvocationTargetException e)` |

### Registering the Listener

```java
WebDriver baseDriver = new ChromeDriver();
MyWebDriverListener listener = new MyWebDriverListener();
WebDriver driver = new EventFiringDecorator<>(listener).decorate(baseDriver);

// All operations on 'driver' now trigger listener callbacks
driver.get("https://example.com");
driver.findElement(By.id("btn")).click();
driver.quit();
```

### Practical Example: Screenshot on Failure

```java
public class ScreenshotOnErrorListener implements WebDriverListener {
    private WebDriver driver;
    private final Path screenshotDir;

    public ScreenshotOnErrorListener(Path screenshotDir) {
        this.screenshotDir = screenshotDir;
    }

    @Override
    public void afterAnyWebDriverCall(WebDriver driver, Method method, Object[] args, Object result) {
        this.driver = driver; // Keep reference for screenshots
    }

    @Override
    public void onError(Object target, Method method, Object[] args, InvocationTargetException e) {
        if (driver != null) {
            try {
                File src = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
                String name = "error_" + method.getName() + "_" + System.currentTimeMillis() + ".png";
                Files.copy(src.toPath(), screenshotDir.resolve(name));
                System.out.println("Screenshot saved: " + name);
            } catch (Exception ex) {
                System.err.println("Screenshot failed: " + ex.getMessage());
            }
        }
    }
}
```

### Legacy: EventFiringWebDriver (Deprecated in Selenium 4)

```java
// Deprecated — use EventFiringDecorator + WebDriverListener instead
// Shown for reference when maintaining older code
import org.openqa.selenium.support.events.EventFiringWebDriver;
import org.openqa.selenium.support.events.AbstractWebDriverEventListener;

EventFiringWebDriver efDriver = new EventFiringWebDriver(baseDriver);
efDriver.register(new AbstractWebDriverEventListener() {
    @Override
    public void beforeClickOn(WebElement element, WebDriver driver) {
        System.out.println("Clicking: " + element);
    }
});
```

---

## 20. Selenium Manager (4.6+)

Selenium Manager automatically downloads and manages browser drivers, removing the need for manual driver setup.

### How It Works

| Step | Description |
|---|---|
| 1 | Selenium detects which browser driver is needed |
| 2 | Selenium Manager checks local cache for matching driver |
| 3 | If not cached, downloads the correct driver version |
| 4 | Driver is placed in cache and used for the session |

### Default Behavior (Zero Config)

```java
// No System.setProperty needed — Selenium Manager handles it
WebDriver chrome = new ChromeDriver();       // Auto-downloads chromedriver
WebDriver firefox = new FirefoxDriver();     // Auto-downloads geckodriver
WebDriver edge = new EdgeDriver();           // Auto-downloads msedgedriver
```

### Cache Location

| OS | Default Cache Path |
|---|---|
| Linux | `~/.cache/selenium/` |
| macOS | `~/Library/Caches/selenium/` |
| Windows | `%LOCALAPPDATA%\selenium\` |

### Configuration via Environment Variables

| Variable | Description | Example |
|---|---|---|
| `SE_CACHE_PATH` | Custom cache directory | `/opt/selenium-cache` |
| `SE_BROWSER` | Override browser name | `chrome` |
| `SE_BROWSER_VERSION` | Pin browser version | `120` |
| `SE_DRIVER_VERSION` | Pin driver version | `120.0.6099.109` |
| `SE_OFFLINE` | Disable auto-download | `true` |

### Selenium Manager CLI

```bash
# The selenium-manager executable is bundled inside the selenium JAR
# Direct invocation (for debugging):

# Check resolved driver
selenium-manager --browser chrome --output json

# Specify browser version
selenium-manager --browser chrome --browser-version 120

# Custom cache path
selenium-manager --browser chrome --cache-path /opt/selenium-cache

# Debug mode
selenium-manager --browser chrome --debug
```

### Programmatic Configuration

```java
// Pin specific browser version
ChromeOptions options = new ChromeOptions();
options.setBrowserVersion("120");
WebDriver driver = new ChromeDriver(options);

// Selenium Manager respects the version and downloads matching driver
```

### Disabling Selenium Manager

```java
// Use manual driver path (bypasses Selenium Manager)
System.setProperty("webdriver.chrome.driver", "/usr/local/bin/chromedriver");
WebDriver driver = new ChromeDriver();
```

---

## 21. Advanced Browser Options

### ChromeOptions — Extended Configuration

```java
ChromeOptions options = new ChromeOptions();

// Binary and profile
options.setBinary("/opt/google/chrome-beta/chrome");
options.addArguments("--user-data-dir=/tmp/chrome-profile");

// Extensions
options.addExtensions(new File("/path/to/extension.crx"));

// Exclude switches (e.g., remove "enable-automation" banner)
options.setExperimentalOption("excludeSwitches",
    List.of("enable-automation", "enable-logging"));

// Use automation extension = false
options.setExperimentalOption("useAutomationExtension", false);

// Mobile emulation
Map<String, Object> mobileEmulation = new HashMap<>();
mobileEmulation.put("deviceName", "Pixel 5");
options.setExperimentalOption("mobileEmulation", mobileEmulation);

// Custom mobile emulation (without predefined device)
Map<String, Object> deviceMetrics = new HashMap<>();
deviceMetrics.put("width", 393);
deviceMetrics.put("height", 851);
deviceMetrics.put("pixelRatio", 2.75);
Map<String, Object> mobileEmu = new HashMap<>();
mobileEmu.put("deviceMetrics", deviceMetrics);
mobileEmu.put("userAgent", "Mozilla/5.0 (Linux; Android 12; Pixel 5) ...");
options.setExperimentalOption("mobileEmulation", mobileEmu);
```

### Common Chrome Arguments

| Argument | Description |
|---|---|
| `--headless=new` | Headless mode (new implementation) |
| `--no-sandbox` | Disable sandbox (required in Docker) |
| `--disable-dev-shm-usage` | Use `/tmp` instead of `/dev/shm` |
| `--disable-gpu` | Disable GPU acceleration |
| `--window-size=1920,1080` | Set window size |
| `--start-maximized` | Start maximized |
| `--incognito` | Incognito mode |
| `--disable-extensions` | Disable all extensions |
| `--disable-popup-blocking` | Allow popups |
| `--disable-notifications` | Block notification prompts |
| `--disable-infobars` | Remove info bars |
| `--remote-debugging-port=9222` | Enable remote debugging |
| `--ignore-certificate-errors` | Ignore SSL errors |
| `--user-agent=<string>` | Custom user agent |
| `--lang=en-US` | Browser language |
| `--proxy-server=host:port` | Proxy server |

### FirefoxOptions — Extended Configuration

```java
FirefoxOptions ffOptions = new FirefoxOptions();

// Binary
ffOptions.setBinary("/usr/bin/firefox-developer-edition");

// Profile
FirefoxProfile profile = new FirefoxProfile();
profile.addExtension(new File("/path/to/addon.xpi"));
profile.setPreference("dom.webnotifications.enabled", false);
profile.setPreference("media.navigator.enabled", false);
profile.setPreference("geo.enabled", false);
profile.setPreference("intl.accept_languages", "en-US,en");
ffOptions.setProfile(profile);

// Preferences directly on options
ffOptions.addPreference("browser.startup.homepage", "about:blank");
ffOptions.addPreference("browser.cache.disk.enable", false);

// Arguments
ffOptions.addArguments("-headless");
ffOptions.addArguments("-width=1920", "-height=1080");

// Accept insecure certs
ffOptions.setAcceptInsecureCerts(true);
```

### EdgeOptions

```java
import org.openqa.selenium.edge.EdgeOptions;

EdgeOptions edgeOptions = new EdgeOptions();
// EdgeOptions extends ChromiumOptions — same methods as ChromeOptions
edgeOptions.addArguments("--headless=new", "--no-sandbox");
edgeOptions.addArguments("--inprivate"); // Edge private mode
edgeOptions.setExperimentalOption("excludeSwitches", List.of("enable-automation"));
edgeOptions.setBinary("/opt/microsoft/msedge/msedge");

Map<String, Object> prefs = new HashMap<>();
prefs.put("download.default_directory", "/tmp/downloads");
edgeOptions.setExperimentalOption("prefs", prefs);

WebDriver driver = new EdgeDriver(edgeOptions);
```

### DesiredCapabilities (Legacy Pattern)

```java
// DesiredCapabilities is largely replaced by browser-specific Options classes.
// Shown for reference and for Remote/Grid scenarios needing raw capabilities.

import org.openqa.selenium.remote.DesiredCapabilities;

DesiredCapabilities caps = new DesiredCapabilities();
caps.setBrowserName("chrome");
caps.setVersion("120");
caps.setPlatform(Platform.LINUX);
caps.setCapability("acceptInsecureCerts", true);

// Merge with options
ChromeOptions options = new ChromeOptions();
options.merge(caps);

// For RemoteWebDriver
WebDriver driver = new RemoteWebDriver(new URL("http://grid:4444"), options);
```

### Page Load Strategy

| Strategy | Description |
|---|---|
| `PageLoadStrategy.NORMAL` | Wait for full page load (default) |
| `PageLoadStrategy.EAGER` | Wait for DOM ready, don't wait for resources |
| `PageLoadStrategy.NONE` | Return immediately after navigation starts |

```java
ChromeOptions options = new ChromeOptions();
options.setPageLoadStrategy(PageLoadStrategy.EAGER);
```

---

## 22. Multi-Window / Multi-Tab Patterns

### Open New Tab via JavaScript

```java
// Open new tab and switch to it
((JavascriptExecutor) driver).executeScript("window.open('https://example.com', '_blank');");

// Wait for new tab
WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(5));
wait.until(ExpectedConditions.numberOfWindowsToBe(2));

// Switch to new tab
String originalHandle = driver.getWindowHandle();
for (String handle : driver.getWindowHandles()) {
    if (!handle.equals(originalHandle)) {
        driver.switchTo().window(handle);
        break;
    }
}
```

### Open New Tab via Selenium 4 API

```java
// Preferred in Selenium 4+
driver.switchTo().newWindow(WindowType.TAB);    // Opens and switches to new tab
driver.get("https://example.com");

driver.switchTo().newWindow(WindowType.WINDOW); // Opens new window
driver.get("https://other-site.com");
```

### Handle Popups and Child Windows

```java
String mainWindow = driver.getWindowHandle();

// Trigger popup
driver.findElement(By.id("open-popup")).click();

// Wait for popup
WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
wait.until(ExpectedConditions.numberOfWindowsToBe(2));

// Switch to popup
Set<String> allHandles = driver.getWindowHandles();
for (String handle : allHandles) {
    if (!handle.equals(mainWindow)) {
        driver.switchTo().window(handle);
        break;
    }
}

// Interact with popup
System.out.println("Popup title: " + driver.getTitle());
driver.findElement(By.id("confirm-btn")).click();

// Close popup and return to main window
driver.close(); // Close current (popup) window
driver.switchTo().window(mainWindow);
```

### Close Specific Tabs

```java
// Track all window handles
String mainHandle = driver.getWindowHandle();
List<String> tabHandles = new ArrayList<>();

// Open multiple tabs
for (int i = 0; i < 3; i++) {
    driver.switchTo().newWindow(WindowType.TAB);
    driver.get("https://example.com/page" + i);
    tabHandles.add(driver.getWindowHandle());
}

// Close specific tab (e.g., second tab)
driver.switchTo().window(tabHandles.get(1));
driver.close();
tabHandles.remove(1);

// Switch back to main
driver.switchTo().window(mainHandle);
```

### Window Position and Size Management

```java
// Get current position and size
Point position = driver.manage().window().getPosition();
Dimension size = driver.manage().window().getSize();

// Set position
driver.manage().window().setPosition(new Point(0, 0));

// Set size
driver.manage().window().setSize(new Dimension(1024, 768));

// Maximize / minimize / fullscreen
driver.manage().window().maximize();
driver.manage().window().minimize();
driver.manage().window().fullscreen();

// Tile windows side by side
String handle1 = driver.getWindowHandle();
driver.switchTo().newWindow(WindowType.WINDOW);
String handle2 = driver.getWindowHandle();

driver.switchTo().window(handle1);
driver.manage().window().setPosition(new Point(0, 0));
driver.manage().window().setSize(new Dimension(960, 1080));

driver.switchTo().window(handle2);
driver.manage().window().setPosition(new Point(960, 0));
driver.manage().window().setSize(new Dimension(960, 1080));
```

### Iterate All Windows

```java
// Perform action on all open windows
String current = driver.getWindowHandle();
for (String handle : driver.getWindowHandles()) {
    driver.switchTo().window(handle);
    System.out.println(handle + " -> " + driver.getTitle() + " | " + driver.getCurrentUrl());
}
driver.switchTo().window(current); // Return to original
```

---

## 23. iframe Handling

### Switch to iframe

```java
// By index (0-based)
driver.switchTo().frame(0);

// By name or id attribute
driver.switchTo().frame("iframe-name");

// By WebElement (most reliable)
WebElement iframe = driver.findElement(By.cssSelector("iframe.content-frame"));
driver.switchTo().frame(iframe);

// With explicit wait
WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
wait.until(ExpectedConditions.frameToBeAvailableAndSwitchToIt(By.id("my-frame")));
```

### Navigate Frame Hierarchy

```java
// Switch to parent frame (one level up)
driver.switchTo().parentFrame();

// Switch to top-level document (exit all frames)
driver.switchTo().defaultContent();
```

### Nested iframe Navigation

```java
// HTML structure: page > iframe#outer > iframe#inner > element

// Step 1: Switch to outer iframe
driver.switchTo().frame("outer");

// Step 2: Switch to inner iframe (relative to outer)
driver.switchTo().frame("inner");

// Step 3: Interact with element inside inner iframe
WebElement el = driver.findElement(By.id("deep-element"));
el.click();

// Step 4: Go back to outer iframe
driver.switchTo().parentFrame();

// Step 5: Go back to main page
driver.switchTo().parentFrame();
// OR jump directly to top:
driver.switchTo().defaultContent();
```

### Finding Elements Inside iframes

```java
// Helper method: find element in any iframe on the page
public WebElement findInFrames(WebDriver driver, By by) {
    // Try main content first
    driver.switchTo().defaultContent();
    List<WebElement> found = driver.findElements(by);
    if (!found.isEmpty()) return found.get(0);

    // Search each iframe
    List<WebElement> iframes = driver.findElements(By.tagName("iframe"));
    for (int i = 0; i < iframes.size(); i++) {
        driver.switchTo().defaultContent();
        driver.switchTo().frame(i);
        found = driver.findElements(by);
        if (!found.isEmpty()) return found.get(0);
    }
    driver.switchTo().defaultContent();
    throw new NoSuchElementException("Not found in any frame: " + by);
}
```

### Wait for Element Inside iframe

```java
WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));

// Combined: wait for frame, switch to it, then wait for element
wait.until(ExpectedConditions.frameToBeAvailableAndSwitchToIt(By.id("my-frame")));
WebElement el = wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("inner-btn")));
el.click();

// Return to main content
driver.switchTo().defaultContent();
```

### Common iframe Pitfalls

| Problem | Solution |
|---|---|
| `NoSuchElementException` for visible element | Check if element is inside an iframe; switch to it first |
| Element found but not interactable | Ensure you switched to the correct iframe |
| `StaleElementReferenceException` after frame switch | Re-find the element after switching frames |
| Can't find iframe by name | Use `By.cssSelector("iframe[src*='partial-url']")` |
| Dynamic iframe loaded via JS | Wait for iframe to appear: `presenceOfElementLocated(By.tagName("iframe"))` |

---

## 24. Alert Handling

### Alert Interface Methods

| Method | Returns | Description |
|---|---|---|
| `getText()` | `String` | Get the alert/confirm/prompt message text |
| `accept()` | `void` | Click OK / Accept |
| `dismiss()` | `void` | Click Cancel / Dismiss |
| `sendKeys(String)` | `void` | Type into prompt dialog |

### Basic Alert Handling

```java
// Trigger alert via JS or button click
((JavascriptExecutor) driver).executeScript("alert('Hello!');");

// Switch to alert
Alert alert = driver.switchTo().alert();
System.out.println("Alert text: " + alert.getText());
alert.accept(); // Click OK
```

### Confirm Dialog

```java
((JavascriptExecutor) driver).executeScript("confirm('Are you sure?');");

Alert confirm = driver.switchTo().alert();
System.out.println("Confirm text: " + confirm.getText());
confirm.dismiss(); // Click Cancel
// OR: confirm.accept(); // Click OK
```

### Prompt Dialog

```java
((JavascriptExecutor) driver).executeScript("prompt('Enter your name:');");

Alert prompt = driver.switchTo().alert();
prompt.sendKeys("John Doe");
prompt.accept();
```

### Wait for Alert

```java
WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));

// Wait for alert to appear, then interact
Alert alert = wait.until(ExpectedConditions.alertIsPresent());
alert.accept();
```

### Handling Unexpected Alerts

```java
// Safe alert check — no exception if absent
public boolean isAlertPresent(WebDriver driver) {
    try {
        driver.switchTo().alert();
        return true;
    } catch (NoAlertPresentException e) {
        return false;
    }
}

// Auto-dismiss any alert
public void dismissIfAlert(WebDriver driver) {
    try {
        driver.switchTo().alert().dismiss();
    } catch (NoAlertPresentException ignored) {}
}
```

### Handle Alert on Page Load

```java
// Some pages show alert on load — handle via UnexpectedAlertBehaviour
ChromeOptions options = new ChromeOptions();
options.setCapability(CapabilityType.UNHANDLED_PROMPT_BEHAVIOUR,
    UnexpectedAlertBehaviour.ACCEPT); // ACCEPT, DISMISS, IGNORE, ACCEPT_AND_NOTIFY, DISMISS_AND_NOTIFY

WebDriver driver = new ChromeDriver(options);
```

### Authentication Alert (HTTP Basic Auth)

```java
// Selenium 4+ approach via CDP
((HasAuthentication) driver).register(
    uri -> true, // all URIs
    UsernameAndPassword.of("admin", "password123")
);
driver.get("https://httpbin.org/basic-auth/admin/password123");

// Alternative: embed credentials in URL (limited browser support)
driver.get("https://admin:password123@httpbin.org/basic-auth/admin/password123");
```

---

## 25. Screenshot & Visual Testing

### Full Page Screenshot (TakesScreenshot)

```java
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;

// Viewport screenshot (visible area only)
File screenshot = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
Files.copy(screenshot.toPath(), Path.of("/tmp/screenshot.png"));

// As Base64 string
String base64 = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BASE64);

// As byte array
byte[] bytes = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
```

### Element Screenshot

```java
WebElement element = driver.findElement(By.id("chart"));
File elementShot = element.getScreenshotAs(OutputType.FILE);
Files.copy(elementShot.toPath(), Path.of("/tmp/element-screenshot.png"));
```

### Full Page Screenshot via CDP (Chrome)

```java
// Captures entire scrollable page, not just viewport
ChromeDriver chromeDriver = (ChromeDriver) driver;
Map<String, Object> params = new HashMap<>();
params.put("format", "png");
params.put("captureBeyondViewport", true);

// Get layout metrics for full page dimensions
Map<String, Object> layoutMetrics = chromeDriver.executeCdpCommand(
    "Page.getLayoutMetrics", Collections.emptyMap());
Map<String, Object> contentSize = (Map<String, Object>) layoutMetrics.get("contentSize");

params.put("clip", Map.of(
    "x", 0,
    "y", 0,
    "width", contentSize.get("width"),
    "height", contentSize.get("height"),
    "scale", 1
));

Map<String, Object> result = chromeDriver.executeCdpCommand("Page.captureScreenshot", params);
String base64Data = (String) result.get("data");
byte[] imageBytes = Base64.getDecoder().decode(base64Data);
Files.write(Path.of("/tmp/fullpage.png"), imageBytes);
```

### Augmenter for Extended Screenshots (RemoteWebDriver)

```java
import org.openqa.selenium.remote.Augmenter;

// RemoteWebDriver doesn't implement TakesScreenshot directly
WebDriver augmented = new Augmenter().augment(remoteDriver);
File screenshot = ((TakesScreenshot) augmented).getScreenshotAs(OutputType.FILE);
```

### AShot Library — Advanced Screenshots

```java
// Maven: ru.yandex.qatools.ashot:ashot:1.5.4
import ru.yandex.qatools.ashot.AShot;
import ru.yandex.qatools.ashot.Screenshot;
import ru.yandex.qatools.ashot.shooting.ShootingStrategies;
import ru.yandex.qatools.ashot.comparison.ImageDiff;
import ru.yandex.qatools.ashot.comparison.ImageDiffer;

// Full page screenshot with scrolling
Screenshot fullPage = new AShot()
    .shootingStrategy(ShootingStrategies.viewportPasting(100)) // scroll delay ms
    .takeScreenshot(driver);
ImageIO.write(fullPage.getImage(), "PNG", new File("/tmp/fullpage.png"));

// Element screenshot with padding
Screenshot elementShot = new AShot()
    .coordsProvider(new WebDriverCoordsProvider())
    .takeScreenshot(driver, driver.findElement(By.id("widget")));
ImageIO.write(elementShot.getImage(), "PNG", new File("/tmp/widget.png"));

// Visual comparison
Screenshot expected = new Screenshot(ImageIO.read(new File("/tmp/baseline.png")));
Screenshot actual = new AShot()
    .shootingStrategy(ShootingStrategies.viewportPasting(100))
    .takeScreenshot(driver);

ImageDiff diff = new ImageDiffer().makeDiff(expected, actual);
System.out.println("Images differ: " + diff.hasDiff());
System.out.println("Diff size: " + diff.getDiffSize());

// Save diff image (highlights differences in red)
if (diff.hasDiff()) {
    ImageIO.write(diff.getMarkedImage(), "PNG", new File("/tmp/diff.png"));
}
```

### Screenshot Utility Method

```java
public static void takeScreenshot(WebDriver driver, String name) {
    try {
        File src = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
        Path dest = Path.of("target", "screenshots",
            name + "_" + System.currentTimeMillis() + ".png");
        Files.createDirectories(dest.getParent());
        Files.copy(src.toPath(), dest, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("Screenshot: " + dest.toAbsolutePath());
    } catch (Exception e) {
        System.err.println("Screenshot failed: " + e.getMessage());
    }
}
```

---

## 26. TestNG / JUnit 5 Integration Patterns

### TestNG — Basic Selenium Test

```java
import org.testng.annotations.*;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;

public class LoginTest {
    private WebDriver driver;

    @BeforeMethod
    public void setUp() {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new");
        driver = new ChromeDriver(options);
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10));
    }

    @Test
    public void testSuccessfulLogin() {
        driver.get("https://app.example.com/login");
        driver.findElement(By.id("username")).sendKeys("testuser");
        driver.findElement(By.id("password")).sendKeys("pass123");
        driver.findElement(By.id("login-btn")).click();
        Assert.assertEquals(driver.getTitle(), "Dashboard");
    }

    @AfterMethod
    public void tearDown() {
        if (driver != null) driver.quit();
    }
}
```

### TestNG — @DataProvider for Data-Driven Tests

```java
public class DataDrivenTest {
    private WebDriver driver;

    @BeforeMethod
    public void setUp() {
        driver = new ChromeDriver(new ChromeOptions().addArguments("--headless=new"));
    }

    @DataProvider(name = "loginData")
    public Object[][] loginCredentials() {
        return new Object[][] {
            { "user1", "pass1", true },
            { "user2", "wrongpass", false },
            { "admin", "admin123", true },
        };
    }

    @Test(dataProvider = "loginData")
    public void testLogin(String username, String password, boolean shouldSucceed) {
        driver.get("https://app.example.com/login");
        driver.findElement(By.id("username")).sendKeys(username);
        driver.findElement(By.id("password")).sendKeys(password);
        driver.findElement(By.id("login-btn")).click();

        if (shouldSucceed) {
            Assert.assertTrue(driver.getCurrentUrl().contains("/dashboard"));
        } else {
            Assert.assertTrue(driver.findElement(By.css(".error-msg")).isDisplayed());
        }
    }

    @AfterMethod
    public void tearDown() { if (driver != null) driver.quit(); }
}
```

### TestNG — @Listeners for Screenshot on Failure

```java
// Listener class
public class ScreenshotListener implements ITestListener {
    @Override
    public void onTestFailure(ITestResult result) {
        Object instance = result.getInstance();
        WebDriver driver = ((BaseTest) instance).getDriver();
        if (driver != null) {
            File src = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
            try {
                String name = result.getMethod().getMethodName() + "_"
                    + System.currentTimeMillis() + ".png";
                Files.copy(src.toPath(), Path.of("target/screenshots", name));
            } catch (IOException e) { e.printStackTrace(); }
        }
    }
}

// Base test with driver getter
public class BaseTest {
    protected WebDriver driver;
    public WebDriver getDriver() { return driver; }
}

// Apply listener via annotation
@Listeners(ScreenshotListener.class)
public class MyTest extends BaseTest {
    @BeforeMethod
    public void setUp() { driver = new ChromeDriver(); }

    @Test
    public void testSomething() { /* ... */ }

    @AfterMethod
    public void tearDown() { if (driver != null) driver.quit(); }
}

// Or apply globally via testng.xml:
// <listeners><listener class-name="com.example.ScreenshotListener"/></listeners>
```

### TestNG — Parallel Execution

```xml
<!-- testng.xml -->
<!DOCTYPE suite SYSTEM "https://testng.org/testng-1.0.dtd">
<suite name="Parallel Suite" parallel="methods" thread-count="4">
    <test name="Chrome Tests">
        <classes>
            <class name="com.example.LoginTest"/>
            <class name="com.example.SearchTest"/>
        </classes>
    </test>
</suite>
```

```java
// Thread-safe driver with ThreadLocal
public class ParallelBaseTest {
    private static final ThreadLocal<WebDriver> driverThread = new ThreadLocal<>();

    @BeforeMethod
    public void setUp() {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new");
        driverThread.set(new ChromeDriver(options));
    }

    protected WebDriver getDriver() {
        return driverThread.get();
    }

    @AfterMethod
    public void tearDown() {
        WebDriver d = driverThread.get();
        if (d != null) { d.quit(); driverThread.remove(); }
    }
}
```

### JUnit 5 — Basic Selenium Test

```java
import org.junit.jupiter.api.*;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;

class LoginJUnit5Test {
    private WebDriver driver;

    @BeforeEach
    void setUp() {
        driver = new ChromeDriver(new ChromeOptions().addArguments("--headless=new"));
    }

    @Test
    @DisplayName("User can log in with valid credentials")
    void testSuccessfulLogin() {
        driver.get("https://app.example.com/login");
        driver.findElement(By.id("username")).sendKeys("testuser");
        driver.findElement(By.id("password")).sendKeys("pass123");
        driver.findElement(By.id("login-btn")).click();
        Assertions.assertEquals("Dashboard", driver.getTitle());
    }

    @AfterEach
    void tearDown() {
        if (driver != null) driver.quit();
    }
}
```

### JUnit 5 — @ExtendWith for WebDriver Lifecycle

```java
// Custom extension for WebDriver management
public class WebDriverExtension implements BeforeEachCallback, AfterEachCallback,
        ParameterResolver, TestWatcher {

    private static final ExtensionContext.Namespace NS =
        ExtensionContext.Namespace.create(WebDriverExtension.class);

    @Override
    public void beforeEach(ExtensionContext context) {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new", "--no-sandbox");
        WebDriver driver = new ChromeDriver(options);
        context.getStore(NS).put("driver", driver);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        WebDriver driver = context.getStore(NS).get("driver", WebDriver.class);
        if (driver != null) driver.quit();
    }

    @Override
    public boolean supportsParameter(ParameterContext paramCtx, ExtensionContext extCtx) {
        return paramCtx.getParameter().getType().equals(WebDriver.class);
    }

    @Override
    public Object resolveParameter(ParameterContext paramCtx, ExtensionContext extCtx) {
        return extCtx.getStore(NS).get("driver", WebDriver.class);
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        WebDriver driver = context.getStore(NS).get("driver", WebDriver.class);
        if (driver != null) {
            File src = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
            try {
                String name = context.getDisplayName() + "_" + System.currentTimeMillis() + ".png";
                Files.copy(src.toPath(), Path.of("target/screenshots", name));
            } catch (IOException e) { e.printStackTrace(); }
        }
    }
}

// Usage — driver injected via parameter
@ExtendWith(WebDriverExtension.class)
class SearchTest {

    @Test
    @DisplayName("Search returns results")
    void testSearch(WebDriver driver) {
        driver.get("https://app.example.com");
        driver.findElement(By.name("q")).sendKeys("Selenium" + Keys.ENTER);
        new WebDriverWait(driver, Duration.ofSeconds(10))
            .until(ExpectedConditions.presenceOfElementLocated(By.css(".results")));
        Assertions.assertTrue(
            driver.findElements(By.css(".result-item")).size() > 0
        );
    }
}
```

### JUnit 5 — @ParameterizedTest

```java
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

@ExtendWith(WebDriverExtension.class)
class ParameterizedSearchTest {

    @ParameterizedTest
    @ValueSource(strings = {"Selenium", "WebDriver", "Automation"})
    void testSearchTerms(String term, WebDriver driver) {
        driver.get("https://app.example.com");
        driver.findElement(By.name("q")).sendKeys(term + Keys.ENTER);
        new WebDriverWait(driver, Duration.ofSeconds(10))
            .until(ExpectedConditions.presenceOfElementLocated(By.css(".results")));
        Assertions.assertTrue(driver.findElements(By.css(".result-item")).size() > 0,
            "No results for: " + term);
    }

    @ParameterizedTest
    @CsvSource({
        "user1, pass1, Dashboard",
        "admin, admin123, Admin Panel"
    })
    void testLoginRedirect(String user, String pass, String expectedTitle, WebDriver driver) {
        driver.get("https://app.example.com/login");
        driver.findElement(By.id("username")).sendKeys(user);
        driver.findElement(By.id("password")).sendKeys(pass);
        driver.findElement(By.id("login-btn")).click();
        Assertions.assertEquals(expectedTitle, driver.getTitle());
    }
}
```

### JUnit 5 — Parallel Execution

```properties
# src/test/resources/junit-platform.properties
junit.jupiter.execution.parallel.enabled=true
junit.jupiter.execution.parallel.mode.default=concurrent
junit.jupiter.execution.parallel.config.strategy=fixed
junit.jupiter.execution.parallel.config.fixed.parallelism=4
```

```java
// Ensure thread safety with ThreadLocal or per-method driver instances
@ExtendWith(WebDriverExtension.class) // Extension creates driver per test
@Execution(ExecutionMode.CONCURRENT)
class ParallelJUnit5Test {

    @Test
    void testPageA(WebDriver driver) {
        driver.get("https://app.example.com/pageA");
        Assertions.assertEquals("Page A", driver.getTitle());
    }

    @Test
    void testPageB(WebDriver driver) {
        driver.get("https://app.example.com/pageB");
        Assertions.assertEquals("Page B", driver.getTitle());
    }
}
```

### Maven Surefire — Parallel Test Configuration

```xml
<!-- pom.xml — run Selenium tests in parallel -->
<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-surefire-plugin</artifactId>
      <version>3.2.5</version>
      <configuration>
        <!-- For TestNG -->
        <suiteXmlFiles>
          <suiteXmlFile>testng.xml</suiteXmlFile>
        </suiteXmlFiles>
        <!-- For JUnit 5 -->
        <properties>
          <configurationParameters>
            junit.jupiter.execution.parallel.enabled=true
            junit.jupiter.execution.parallel.config.fixed.parallelism=4
          </configurationParameters>
        </properties>
        <!-- Fork JVM per test class (isolation) -->
        <forkCount>4</forkCount>
        <reuseForks>true</reuseForks>
      </configuration>
    </plugin>
  </plugins>
</build>
```

---

## Extended Import Cheat Sheet

```java
// Core (covered in Section above)
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.firefox.FirefoxProfile;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeOptions;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.*;
import org.openqa.selenium.support.FindBy;
import org.openqa.selenium.support.PageFactory;
import static org.openqa.selenium.support.locators.RelativeLocator.with;

// DevTools / CDP
import org.openqa.selenium.devtools.DevTools;
import org.openqa.selenium.devtools.v129.network.Network;
import org.openqa.selenium.devtools.v129.log.Log;
import org.openqa.selenium.devtools.v129.performance.Performance;
import org.openqa.selenium.devtools.v129.emulation.Emulation;
import org.openqa.selenium.devtools.v129.fetch.Fetch;
import org.openqa.selenium.devtools.v129.runtime.Runtime;

// BiDi
import org.openqa.selenium.bidi.browsingcontext.BrowsingContext;
import org.openqa.selenium.bidi.network.*;
import org.openqa.selenium.bidi.log.*;
import org.openqa.selenium.bidi.script.*;

// Logging
import org.openqa.selenium.logging.*;
import java.util.logging.Level;

// Remote / Grid
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.remote.Augmenter;
import org.openqa.selenium.remote.SessionId;
import org.openqa.selenium.remote.DesiredCapabilities;

// Events
import org.openqa.selenium.support.events.WebDriverListener;
import org.openqa.selenium.support.events.EventFiringDecorator;

// Auth
import org.openqa.selenium.HasAuthentication;
import org.openqa.selenium.UsernameAndPassword;

// Proxy
import org.openqa.selenium.Proxy;

// Java standard
import java.time.Duration;
import java.nio.file.*;
import java.io.File;
import java.net.URL;
import java.util.*;
```
