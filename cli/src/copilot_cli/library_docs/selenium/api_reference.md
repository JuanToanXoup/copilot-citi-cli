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
