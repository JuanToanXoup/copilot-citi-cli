# Cucumber Java API Reference

## Step Definitions

A step definition is a Java method annotated with a Gherkin keyword that maps to steps in `.feature` files.

### Annotation-Based (Standard)

```java
package com.example.steps;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import io.cucumber.java.en.Then;

public class OrderSteps {

    @Given("the customer has {int} items in the cart")
    public void the_customer_has_items(int count) {
        // set up state
    }

    @When("the customer checks out")
    public void the_customer_checks_out() {
        // perform action
    }

    @Then("the order total should be {double}")
    public void the_order_total_should_be(double total) {
        // assert outcome
    }
}
```

### Lambda-Based (Java 8+)

Implement `io.cucumber.java8.En` and define steps in the constructor:

```java
public class OrderSteps implements En {
    public OrderSteps() {
        Given("the customer has {int} items", (Integer count) -> { /* ... */ });
        When("the customer checks out", () -> { /* ... */ });
    }
}
```

### Expression Types

**Cucumber Expressions** (recommended): `{int}`, `{float}`, `{double}`, `{string}`, `{word}`, `{long}`, `{bigdecimal}`, `{biginteger}`, `{}` (anonymous).

```java
@Given("I have {int} cukes in my {string} basket")
public void cukes_in_basket(int count, String basket) {}
```

**Regular Expressions** also supported:

```java
@Given("^I have (\\d+) cukes in my \"([^\"]*)\" basket$")
public void cukes_in_basket(int count, String basket) {}
```

### State Between Steps

Share state via instance variables. Cucumber creates a new instance per scenario.

```java
public class CartSteps {
    private Cart cart;
    @Given("an empty cart")
    public void an_empty_cart() { cart = new Cart(); }
    @When("I add {string} to the cart")
    public void add_to_cart(String item) { cart.add(item); }
}
```

## Custom Parameter Types

Define reusable parameter types with `@ParameterType`:

```java
import io.cucumber.java.ParameterType;

public class ParameterTypes {

    @ParameterType("red|green|blue")
    public Color color(String colorName) {
        return Color.valueOf(colorName.toUpperCase());
    }

    @ParameterType("[A-Z]{3}-\\d{4}")
    public OrderId orderId(String id) {
        return new OrderId(id);
    }
}
```

Usage in steps:
```java
@Given("the {color} widget with order {orderId}")
public void widget_with_order(Color color, OrderId id) {}
```

## Data Tables

Data tables from Gherkin are passed to step definitions and can be consumed in several ways.

```java
// Raw DataTable
@Given("the following users:")
public void users(DataTable table) { List<Map<String, String>> rows = table.asMaps(); }

// Auto-converted types: List<Map<String,String>>, List<List<String>>, List<String>
@Given("the following users:")
public void users(List<Map<String, String>> users) {}
```

### Custom DataTable Types

```java
@DataTableType
public User userEntry(Map<String, String> row) {
    return new User(row.get("name"), row.get("email"));
}
// Now steps can accept List<User> directly
```

## Doc Strings

```java
@Given("the following JSON payload:")
public void the_following_json(String docString) { /* raw multi-line text */ }

// Custom DocString type converter
@DocStringType(contentType = "json")
public JsonObject json(String docString) {
    return JsonParser.parseString(docString).getAsJsonObject();
}
```

## Hooks

Hooks execute code at specific lifecycle points. They are defined in any step definition class.

### Scenario Hooks

```java
@Before                              // runs before every scenario
public void setUp() {}

@Before("@ui and not @headless")     // conditional on tags
public void setUpBrowser() {}

@After                               // runs after every scenario (even on failure)
public void tearDown(Scenario scenario) {
    if (scenario.isFailed()) { /* capture screenshot */ }
}

@Before(order = 10)                  // lower order = runs first (@Before), last (@After)
public void earlySetup() {}
```

### Step Hooks

```java
@BeforeStep
public void beforeEachStep() {}
@AfterStep
public void afterEachStep() {}
```

### Global Hooks (BeforeAll / AfterAll)

Run once across the entire suite. Must be **static** methods.

```java
@BeforeAll
public static void beforeAll() { /* start shared resource */ }
@AfterAll
public static void afterAll() { /* stop shared resource */ }
```

## Dependency Injection

Cucumber creates new step definition instances per scenario. To share state across multiple step definition classes, use a DI framework.

### Supported DI Modules

| Module                    | Dependency                            |
|---------------------------|---------------------------------------|
| PicoContainer (simplest)  | `cucumber-picocontainer`              |
| Spring                    | `cucumber-spring`                     |
| Guice                     | `cucumber-guice`                      |
| CDI (Weld)                | `cucumber-cdi2`                       |
| OpenEJB                   | `cucumber-openejb`                    |

### PicoContainer Example

Add `cucumber-picocontainer` dependency, then inject shared state via constructor:

```java
public class SharedState {
    private String currentUser;
    public void setCurrentUser(String u) { currentUser = u; }
    public String getCurrentUser() { return currentUser; }
}

public class LoginSteps {
    private final SharedState state;
    public LoginSteps(SharedState state) { this.state = state; }

    @Given("the user {string} is logged in")
    public void user_logged_in(String user) { state.setCurrentUser(user); }
}
```

### Spring Example

```java
@CucumberContextConfiguration
@SpringBootTest
public class CucumberSpringConfig {}
```

## Test Runners

### JUnit 5 (Platform Suite) -- Recommended

```java
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;

@Suite
@IncludeEngines("cucumber")
@SelectPackages("com.example")
public class RunCucumberTest {}
```

### JUnit 4

```java
import io.cucumber.junit.Cucumber;
import io.cucumber.junit.CucumberOptions;
import org.junit.runner.RunWith;

@RunWith(Cucumber.class)
@CucumberOptions(
    features = "src/test/resources/features",
    glue = "com.example.steps",
    plugin = {"pretty", "html:target/cucumber-reports.html"},
    tags = "@smoke and not @wip"
)
public class RunCucumberTest {}
```

## Configuration

### cucumber.properties

Place in `src/test/resources/cucumber.properties`:

```properties
cucumber.glue=com.example.steps
cucumber.features=src/test/resources/features
cucumber.filter.tags=@smoke and not @wip
cucumber.plugin=pretty, html:target/cucumber-reports.html, json:target/cucumber.json
cucumber.snippet-type=camelcase
cucumber.publish.quiet=true
cucumber.execution.dry-run=false
```

### junit-platform.properties

Place in `src/test/resources/junit-platform.properties` (for JUnit 5):

```properties
cucumber.glue=com.example.steps
cucumber.features=src/test/resources/features
cucumber.filter.tags=@smoke and not @wip
cucumber.plugin=pretty, html:target/cucumber-reports.html
cucumber.snippet-type=camelcase
cucumber.execution.parallel.enabled=true
cucumber.execution.parallel.config.fixed.parallelism=4
```

### @CucumberOptions Attributes

| Attribute    | Description                                       |
|--------------|---------------------------------------------------|
| features     | Path(s) to `.feature` files                       |
| glue         | Package(s) containing step definitions and hooks  |
| plugin       | Formatters/reporters                              |
| tags         | Tag expression filter                             |
| dryRun       | Validate steps without executing (true/false)     |
| snippets     | Snippet style: `UNDERSCORE` or `CAMELCASE`        |
| monochrome   | Readable console output (true/false)              |
| name         | Regex filter on scenario names                    |

### Configuration Precedence (highest to lowest)

1. CLI arguments / System properties
2. `@CucumberOptions` annotation
3. Environment variables
4. `cucumber.properties` / `junit-platform.properties`

## Common Plugins

| Plugin                                   | Output                       |
|------------------------------------------|------------------------------|
| `pretty`                                 | Colored console output       |
| `html:path/report.html`                  | HTML report                  |
| `json:path/report.json`                  | JSON report                  |
| `junit:path/report.xml`                  | JUnit XML report             |
| `io.qameta.allure.cucumber7jvm.AllureCucumber7Jvm` | Allure integration |
| `rerun:path/rerun.txt`                   | Failed scenario paths        |

## Maven Dependencies (BOM)

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.cucumber</groupId>
            <artifactId>cucumber-bom</artifactId>
            <version>7.18.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>io.cucumber</groupId>
        <artifactId>cucumber-java</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>io.cucumber</groupId>
        <artifactId>cucumber-junit-platform-engine</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

## Parallel Execution

Cucumber supports parallel execution at both the scenario and feature level via JUnit 5 Platform.

### JUnit 5 Parallel Configuration

Enable parallel execution in `src/test/resources/junit-platform.properties`:

```properties
cucumber.execution.parallel.enabled=true

# Fixed parallelism: always use exactly N threads
cucumber.execution.parallel.config.strategy=fixed
cucumber.execution.parallel.config.fixed.parallelism=4
cucumber.execution.parallel.config.fixed.max-pool-size=4

# Dynamic parallelism: factor * available processors
# cucumber.execution.parallel.config.strategy=dynamic
# cucumber.execution.parallel.config.dynamic.factor=0.5
```

### Per-Scenario vs Per-Feature Parallelism

By default Cucumber runs scenarios in parallel. Control the execution mode:

```properties
# SAME_THREAD — feature files run sequentially, scenarios within them sequentially
# CONCURRENT  — scenarios run concurrently (default when parallel is enabled)
cucumber.execution.execution-mode.feature=CONCURRENT
```

When `feature=CONCURRENT`, individual scenarios from different features may run on different threads simultaneously.

### Thread-Safe Step Definitions

Cucumber creates a **new step definition instance per scenario**, so instance variables are safe. Avoid:

- Static mutable fields shared across step classes
- Singleton caches that are not thread-safe
- Shared database connections without isolation

```java
public class ThreadSafeSteps {
    // SAFE: instance variable, unique per scenario
    private Response response;

    // UNSAFE: static mutable state shared across threads
    // private static List<String> sharedList = new ArrayList<>();

    @When("the user calls the API")
    public void callApi() {
        response = apiClient.get("/endpoint");
    }
}
```

### WebDriver Thread Isolation

Use `ThreadLocal` to ensure each thread gets its own WebDriver instance:

```java
public class WebDriverManager {
    private static final ThreadLocal<WebDriver> DRIVER = new ThreadLocal<>();

    public static WebDriver getDriver() {
        return DRIVER.get();
    }

    public static void setDriver(WebDriver driver) {
        DRIVER.set(driver);
    }

    public static void quitDriver() {
        WebDriver driver = DRIVER.get();
        if (driver != null) {
            driver.quit();
            DRIVER.remove();
        }
    }
}
```

```java
@Before("@ui")
public void setUp() {
    WebDriver driver = new ChromeDriver();
    WebDriverManager.setDriver(driver);
}

@After("@ui")
public void tearDown() {
    WebDriverManager.quitDriver();
}
```

## Scenario Object (io.cucumber.java.Scenario)

The `Scenario` object is injected into hooks and provides metadata about the currently running scenario.

### Key Methods

| Method | Return Type | Description |
|--------|-------------|-------------|
| `getName()` | `String` | Scenario name from the feature file |
| `getId()` | `String` | Unique URI-based identifier |
| `getStatus()` | `Status` | Current status: `PASSED`, `FAILED`, `SKIPPED`, `PENDING`, `UNDEFINED`, `AMBIGUOUS` |
| `isFailed()` | `boolean` | `true` if any step has failed |
| `getSourceTagNames()` | `Collection<String>` | All tags applied to the scenario (including inherited) |
| `getUri()` | `URI` | URI of the feature file |
| `getLine()` | `Integer` | Line number in the feature file |
| `write(String)` | `void` | **Deprecated.** Use `log(String)` instead |
| `log(String)` | `void` | Attach a text log message to the scenario report |
| `attach(byte[], String, String)` | `void` | Embed binary content (e.g., screenshot) with media type and name |

### Screenshot on Failure in @After Hook

```java
import io.cucumber.java.After;
import io.cucumber.java.Scenario;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;

public class ScreenshotHook {

    @After("@ui")
    public void captureScreenshot(Scenario scenario) {
        if (scenario.isFailed()) {
            WebDriver driver = WebDriverManager.getDriver();
            if (driver instanceof TakesScreenshot) {
                byte[] screenshot = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
                scenario.attach(screenshot, "image/png",
                    "screenshot-" + scenario.getName().replaceAll("\\s+", "_"));
            }
        }
    }
}
```

### Logging Scenario Context

```java
@Before
public void logScenarioStart(Scenario scenario) {
    System.out.println("Starting: " + scenario.getName());
    System.out.println("Tags: " + scenario.getSourceTagNames());
    System.out.println("URI: " + scenario.getUri() + ":" + scenario.getLine());
}

@After
public void logScenarioEnd(Scenario scenario) {
    scenario.log("Final status: " + scenario.getStatus());
}
```

## Tag Expressions

Tags filter which scenarios or features run. Apply tags in feature files and filter them in configuration or runners.

### Applying Tags

```gherkin
@smoke
Feature: Login

  @regression @quick
  Scenario: Valid credentials
    Given ...

  @slow @nightly
  Scenario: Bulk import
    Given ...
```

### Tag Expression Syntax

| Expression | Meaning |
|------------|---------|
| `@smoke and @regression` | Scenarios that have **both** tags |
| `@smoke or @quick` | Scenarios that have **either** tag |
| `not @wip` | Scenarios that do **not** have the tag |
| `(@smoke or @regression) and not @slow` | Combined expression |

### Using Tag Expressions

```java
// In @CucumberOptions
@CucumberOptions(tags = "@smoke and not @wip")

// In cucumber.properties
cucumber.filter.tags=@smoke and not @wip

// In hooks
@Before("@ui and not @headless")
public void setUpBrowser() {}

// Command line
// mvn test -Dcucumber.filter.tags="@smoke"
```

### Tag Inheritance

Tags placed on a `Feature` or `Rule` are inherited by all their child `Scenario` and `Scenario Outline` elements.

```gherkin
@team-checkout
Feature: Checkout

  @smoke
  Scenario: Quick checkout
    # Effective tags: @team-checkout, @smoke
    Given ...

  Rule: Discount rules
    @discounts
    Scenario: Apply coupon
      # Effective tags: @team-checkout, @discounts
      Given ...
```

## Cucumber Expressions (Deep Dive)

Cucumber Expressions are the recommended way to match step text. They provide readability and type safety over raw regex.

### Built-In Parameter Types

| Expression | Java Type | Matches | Example |
|------------|-----------|---------|---------|
| `{int}` | `Integer` | `-?\d+` | `42`, `-1` |
| `{float}` | `Float` | `-?\d*\.\d+` | `3.14` |
| `{double}` | `Double` | `-?\d+(\.\d+)?` | `99.9` |
| `{long}` | `Long` | `-?\d+` | `9999999999` |
| `{bigdecimal}` | `BigDecimal` | `-?\d+(\.\d+)?` | `123.456` |
| `{biginteger}` | `BigInteger` | `-?\d+` | `999999999999999` |
| `{string}` | `String` | `"[^"]*"` or `'[^']*'` | `"hello"` |
| `{word}` | `String` | `[^\s]+` | `banana` |
| `{}` | `String` | `.*` (anonymous) | anything |

### Optional Text

Wrap text in parentheses to make it optional:

```java
@Given("I have {int} cucumber(s) in my basket")
public void cucumbers(int count) {}
// Matches: "I have 1 cucumber in my basket"
// Matches: "I have 5 cucumbers in my basket"
```

### Alternative Text

Use `/` between words to match either:

```java
@When("I add/remove {int} item(s)")
public void modifyCart(int count) {}
// Matches: "I add 3 items"
// Matches: "I remove 1 item"
```

### Escaping Special Characters

Use backslash to escape `(`, `)`, `{`, `}`, `/`:

```java
@Given("the price is \\${double}")
public void price(double amount) {}
// Matches: "the price is $9.99"

@Given("the ratio is \\({int}\\)")
public void ratio(int value) {}
// Matches: "the ratio is (5)"
```

### Custom Parameter Types with @ParameterType

```java
import io.cucumber.java.ParameterType;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public class CustomTypes {

    // Name derived from method name by default
    @ParameterType("\\d{4}-\\d{2}-\\d{2}")
    public LocalDate isoDate(String dateStr) {
        return LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE);
    }

    // Explicit name override
    @ParameterType(name = "status", value = "active|inactive|pending")
    public AccountStatus accountStatus(String status) {
        return AccountStatus.valueOf(status.toUpperCase());
    }

    // Use in steps
    // @Given("the account created on {isoDate} is {status}")
    // public void account(LocalDate date, AccountStatus status) {}
}
```

### Anonymous Parameter

The `{}` parameter captures anything and passes it as a `String`:

```java
@Then("the error message is {}")
public void errorMessage(String message) {}
// Matches: "the error message is Something went wrong"
```

## Reporting & CI Integration

Cucumber supports multiple report formats via the `plugin` option.

### Built-In Report Plugins

```java
@CucumberOptions(plugin = {
    "pretty",                                // Console output
    "html:target/cucumber-reports.html",     // HTML report
    "json:target/cucumber.json",             // JSON report
    "junit:target/cucumber.xml",             // JUnit XML (for CI)
    "rerun:target/rerun.txt"                 // Failed scenario paths
})
```

Equivalent in `cucumber.properties`:

```properties
cucumber.plugin=pretty, html:target/cucumber-reports.html, json:target/cucumber.json, junit:target/cucumber.xml, rerun:target/rerun.txt
```

### Re-Running Failed Scenarios

The `rerun` plugin writes failed scenario locations to a file. Re-run only those:

```properties
# cucumber.properties for rerun
cucumber.features=@target/rerun.txt
```

Or via Maven:

```bash
mvn test -Dcucumber.features="@target/rerun.txt"
```

### Allure Integration

Add the dependency:

```xml
<dependency>
    <groupId>io.qameta.allure</groupId>
    <artifactId>allure-cucumber7-jvm</artifactId>
    <version>2.25.0</version>
    <scope>test</scope>
</dependency>
```

Configure the plugin:

```java
@CucumberOptions(plugin = {
    "io.qameta.allure.cucumber7jvm.AllureCucumber7Jvm"
})
```

Use Allure annotations alongside Cucumber:

```java
import io.qameta.allure.Description;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;

@Severity(SeverityLevel.CRITICAL)
@Description("Verify checkout completes successfully")
@When("the user completes checkout")
public void checkout() { /* ... */ }
```

### Maven Surefire/Failsafe Configuration

Use `maven-surefire-plugin` for unit-level tests or `maven-failsafe-plugin` for integration tests:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <version>3.2.5</version>
    <configuration>
        <properties>
            <configurationParameters>
                cucumber.junit-platform.naming-strategy=long
            </configurationParameters>
        </properties>
    </configuration>
</plugin>

<!-- For integration tests -->
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-failsafe-plugin</artifactId>
    <version>3.2.5</version>
    <executions>
        <execution>
            <goals>
                <goal>integration-test</goal>
                <goal>verify</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

### Gradle Test Task Configuration

```groovy
// build.gradle
dependencies {
    testImplementation 'io.cucumber:cucumber-java:7.18.0'
    testImplementation 'io.cucumber:cucumber-junit-platform-engine:7.18.0'
    testImplementation 'org.junit.platform:junit-platform-suite:1.10.2'
    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
}

test {
    useJUnitPlatform()
    systemProperty "cucumber.execution.parallel.enabled", "true"
    systemProperty "cucumber.execution.parallel.config.fixed.parallelism", "4"
    systemProperty "cucumber.plugin", "pretty, html:build/reports/cucumber.html"
}

configurations {
    cucumberRuntime { extendsFrom testImplementation }
}

task cucumberTest(type: Test) {
    useJUnitPlatform()
    filter { includeTestsMatching "com.example.RunCucumberTest" }
}
```

## Assertions with Cucumber

Cucumber does not include its own assertion library. Use any Java assertion framework in `@Then` steps.

### JUnit 5 Assertions

```java
import static org.junit.jupiter.api.Assertions.*;

@Then("the response status should be {int}")
public void verifyStatus(int expected) {
    assertEquals(expected, response.getStatusCode());
}

@Then("the user should exist")
public void userExists() {
    assertTrue(userRepository.exists(userId), "User should be present in DB");
}

@Then("creating a duplicate should throw an error")
public void duplicateThrows() {
    assertThrows(DuplicateException.class, () -> service.create(duplicate));
}

@Then("all fields should match")
public void allFieldsMatch() {
    assertAll(
        () -> assertEquals("Alice", user.getName()),
        () -> assertEquals("alice@test.com", user.getEmail()),
        () -> assertEquals(30, user.getAge())
    );
}
```

### AssertJ (Fluent Assertions)

```java
import static org.assertj.core.api.Assertions.*;

@Then("the result list has {int} entries")
public void resultListSize(int size) {
    assertThat(results).hasSize(size);
}

@Then("the names contain {string} and {string}")
public void namesContain(String name1, String name2) {
    assertThat(names).containsExactlyInAnyOrder(name1, name2);
}

@Then("the response body contains the user details")
public void responseContainsUser() {
    assertThat(response.getBody())
        .extracting("name", "email")
        .containsExactly("Alice", "alice@test.com");
}

@Then("the error message should start with {string}")
public void errorStarts(String prefix) {
    assertThat(errorMessage)
        .startsWith(prefix)
        .doesNotContain("stacktrace");
}
```

### Hamcrest Matchers

```java
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@Then("the price should be {double}")
public void verifyPrice(double expected) {
    assertThat(actualPrice, is(closeTo(expected, 0.01)));
}

@Then("the cart should contain {string}")
public void cartContains(String item) {
    assertThat(cart.getItems(), hasItem(item));
}

@Then("the message should contain {string}")
public void messageContains(String text) {
    assertThat(message, containsString(text));
}
```

### Soft Assertions Pattern

Soft assertions collect all failures before reporting, useful in `@Then` steps with multiple checks:

```java
import org.assertj.core.api.SoftAssertions;

@Then("the order details should be correct")
public void verifyOrder() {
    SoftAssertions softly = new SoftAssertions();
    softly.assertThat(order.getTotal()).isEqualTo(99.99);
    softly.assertThat(order.getItems()).hasSize(3);
    softly.assertThat(order.getStatus()).isEqualTo("CONFIRMED");
    softly.assertThat(order.getShipping()).isNotNull();
    softly.assertAll(); // reports ALL failures at once
}
```

## Screenplay Pattern

The Screenplay Pattern is an alternative to traditional step definitions that models tests as actors performing tasks.

### Core Concepts

| Concept | Purpose | Example |
|---------|---------|---------|
| **Actor** | A user or system interacting with the application | `Actor.named("Alice")` |
| **Ability** | Something the actor can do | `BrowseTheWeb.with(driver)` |
| **Task** | A high-level action composed of interactions | `Login.withCredentials("user", "pass")` |
| **Question** | Queries the system for observable state | `TheBalance.ofAccount("savings")` |
| **Interaction** | A low-level atomic action | `Click.on(SUBMIT_BUTTON)` |

### How It Differs from Traditional Steps

Traditional step definitions often become large classes with many methods and shared mutable state. Screenplay decomposes behavior into reusable, composable objects:

```java
// Traditional approach — logic in step defs
@When("Alice logs in with valid credentials")
public void login() {
    driver.findElement(By.id("username")).sendKeys("alice");
    driver.findElement(By.id("password")).sendKeys("secret");
    driver.findElement(By.id("submit")).click();
}

// Screenplay approach — thin step defs delegating to tasks
@When("Alice logs in with valid credentials")
public void login() {
    actor.attemptsTo(
        Login.withCredentials("alice", "secret")
    );
}
```

### When to Use It

- Large test suites (100+ scenarios) where step reuse and composition matter
- Multiple actors interacting in the same scenario
- Teams that want strong separation between "what" (Gherkin) and "how" (automation)
- Projects using Serenity BDD, which provides built-in Screenplay support

## Anti-Patterns

### Too Many Scenarios in One Feature

Features with 30+ scenarios are hard to maintain. Split by business capability:

```
# Bad: features/orders.feature (50 scenarios)
# Good:
features/orders/
  create_order.feature
  cancel_order.feature
  order_discounts.feature
```

### Testing Implementation Instead of Behavior

```gherkin
# Bad — coupled to UI implementation
Scenario: Login
  When I enter "alice" into input#username
  And I click button.submit

# Good — describes behavior
Scenario: Successful login
  When the user logs in with valid credentials
  Then they should see the dashboard
```

### Reusing Step Definition Classes Across Domains

Avoid a single `SharedSteps` class handling login, orders, and payments. Keep step classes focused on a single domain. Use dependency injection to share state.

### Overly Technical Gherkin

```gherkin
# Bad
Given a POST request to /api/v2/users with body {"name":"Alice","role":"admin"}
And the response JSON path $.id is stored in variable userId

# Good
Given an admin user "Alice" exists
```

### Ignoring Tags

Without tags, you cannot selectively run smoke tests, exclude WIP, or parallelize effectively. Tag every scenario meaningfully.

### Not Using Hooks Properly

- Do not use `@Before` for business setup (use `Given` steps instead)
- Use hooks only for technical concerns: driver setup, screenshot capture, cleanup
- Keep hooks in dedicated classes, not scattered across step definitions

## Common Patterns

### Page Object Integration

```java
public class LoginPage {
    private final WebDriver driver;

    public LoginPage(WebDriver driver) {
        this.driver = driver;
    }

    public void enterUsername(String username) {
        driver.findElement(By.id("username")).sendKeys(username);
    }

    public void enterPassword(String password) {
        driver.findElement(By.id("password")).sendKeys(password);
    }

    public DashboardPage submit() {
        driver.findElement(By.id("login-btn")).click();
        return new DashboardPage(driver);
    }
}

public class LoginSteps {
    private final WebDriver driver;
    private LoginPage loginPage;

    public LoginSteps(SharedWebDriver shared) {
        this.driver = shared.getDriver();
    }

    @Given("the user is on the login page")
    public void onLoginPage() {
        driver.get("https://app.example.com/login");
        loginPage = new LoginPage(driver);
    }

    @When("the user logs in as {string} with password {string}")
    public void login(String user, String pass) {
        loginPage.enterUsername(user);
        loginPage.enterPassword(pass);
        loginPage.submit();
    }
}
```

### WebDriver Lifecycle Management in Hooks

```java
public class WebDriverHooks {

    private final SharedWebDriver shared;

    public WebDriverHooks(SharedWebDriver shared) {
        this.shared = shared;
    }

    @Before("@ui")
    public void createDriver() {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless", "--no-sandbox");
        WebDriver driver = new ChromeDriver(options);
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10));
        shared.setDriver(driver);
    }

    @After("@ui")
    public void quitDriver(Scenario scenario) {
        WebDriver driver = shared.getDriver();
        if (driver != null) {
            if (scenario.isFailed()) {
                byte[] screenshot = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
                scenario.attach(screenshot, "image/png", "failure-screenshot");
            }
            driver.quit();
        }
    }
}
```

### Test Context / World Object Pattern

A shared POJO holds state across step classes within a single scenario:

```java
public class TestContext {
    private String authToken;
    private Response lastResponse;
    private Map<String, Object> storedValues = new HashMap<>();

    public void store(String key, Object value) { storedValues.put(key, value); }
    public <T> T retrieve(String key, Class<T> type) {
        return type.cast(storedValues.get(key));
    }

    // Getters and setters
    public String getAuthToken() { return authToken; }
    public void setAuthToken(String token) { this.authToken = token; }
    public Response getLastResponse() { return lastResponse; }
    public void setLastResponse(Response r) { this.lastResponse = r; }
}

// Inject via PicoContainer or Spring
public class ApiSteps {
    private final TestContext ctx;
    public ApiSteps(TestContext ctx) { this.ctx = ctx; }

    @When("the user calls GET {string}")
    public void callGet(String path) {
        Response r = given().auth().oauth2(ctx.getAuthToken()).get(path);
        ctx.setLastResponse(r);
    }

    @Then("the response status is {int}")
    public void checkStatus(int expected) {
        assertThat(ctx.getLastResponse().getStatusCode()).isEqualTo(expected);
    }
}
```

### Retry Failed Scenarios

Use the `rerun` plugin combined with a second Maven execution:

```xml
<!-- First execution: run all, capture failures -->
<execution>
    <id>run-tests</id>
    <goals><goal>integration-test</goal></goals>
    <configuration>
        <systemPropertyVariables>
            <cucumber.plugin>rerun:target/rerun.txt</cucumber.plugin>
        </systemPropertyVariables>
    </configuration>
</execution>

<!-- Second execution: rerun only failures -->
<execution>
    <id>rerun-failures</id>
    <goals><goal>integration-test</goal></goals>
    <configuration>
        <systemPropertyVariables>
            <cucumber.features>@target/rerun.txt</cucumber.features>
        </systemPropertyVariables>
    </configuration>
</execution>
```

### Environment-Specific Configuration

Use system properties or profiles to switch environments:

```java
public class EnvConfig {
    private static final String ENV = System.getProperty("test.env", "dev");

    public static String getBaseUrl() {
        return switch (ENV) {
            case "staging" -> "https://staging.example.com";
            case "prod"    -> "https://www.example.com";
            default        -> "http://localhost:8080";
        };
    }
}
```

Run with:

```bash
mvn test -Dtest.env=staging
```

### Database Seeding and Cleanup in Hooks

```java
public class DatabaseHooks {
    private final DataSource dataSource;

    public DatabaseHooks(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Before("@db")
    public void seedTestData() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO users (id, name) VALUES (1, 'Test User')");
        }
    }

    @After("@db")
    public void cleanupTestData() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM users WHERE name = 'Test User'");
        }
    }
}
```

### API Setup Before UI Tests

```java
@Before(value = "@ui and @needs-user", order = 1)
public void createUserViaApi() {
    String token = given()
        .contentType("application/json")
        .body("{\"name\":\"TestUser\",\"role\":\"admin\"}")
        .post("/api/users")
        .then().statusCode(201)
        .extract().path("token");
    testContext.setAuthToken(token);
}

@Before(value = "@ui", order = 2)
public void launchBrowser() {
    WebDriver driver = new ChromeDriver();
    shared.setDriver(driver);
}
```

## Cucumber with Spring Boot

The `cucumber-spring` module integrates Cucumber with the Spring application context.

### Setup

Add the dependency:

```xml
<dependency>
    <groupId>io.cucumber</groupId>
    <artifactId>cucumber-spring</artifactId>
    <scope>test</scope>
</dependency>
```

### @CucumberContextConfiguration

Exactly one class must be annotated with `@CucumberContextConfiguration` to bootstrap the Spring context:

```java
import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

@CucumberContextConfiguration
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class CucumberSpringConfiguration {
    // No methods needed — this class bootstraps the context
}
```

### Sharing ApplicationContext Across Scenarios

Spring's test context is created once and shared across all scenarios in a run (unless `@DirtiesContext` is used). Step definition classes are Spring beans and can use `@Autowired`:

```java
public class UserApiSteps {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestRestTemplate restTemplate;

    @Given("a user {string} exists in the database")
    public void userExists(String name) {
        userRepository.save(new User(name, name.toLowerCase() + "@test.com"));
    }

    @When("I request the user list")
    public void requestUsers() {
        response = restTemplate.getForEntity("/api/users", String.class);
    }
}
```

### MockMvc Integration

For testing controllers without starting a full server:

```java
@CucumberContextConfiguration
@SpringBootTest
@AutoConfigureMockMvc
public class CucumberSpringConfiguration {}

public class MockMvcSteps {

    @Autowired
    private MockMvc mockMvc;

    private ResultActions result;

    @When("I POST to {string} with body:")
    public void postWithBody(String path, String body) throws Exception {
        result = mockMvc.perform(post(path)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
    }

    @Then("the response status is {int}")
    public void checkStatus(int status) throws Exception {
        result.andExpect(status().is(status));
    }

    @Then("the response JSON path {string} is {string}")
    public void checkJsonPath(String path, String expected) throws Exception {
        result.andExpect(jsonPath(path).value(expected));
    }
}
```

### TestRestTemplate for API Testing

```java
@CucumberContextConfiguration
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class CucumberSpringConfiguration {}

public class ApiSteps {

    @Autowired
    private TestRestTemplate restTemplate;

    private ResponseEntity<String> response;

    @When("I call GET {string}")
    public void callGet(String path) {
        response = restTemplate.getForEntity(path, String.class);
    }

    @Then("the status code is {int}")
    public void statusCode(int expected) {
        assertThat(response.getStatusCode().value()).isEqualTo(expected);
    }
}
```

### @DirtiesContext Usage

Use `@DirtiesContext` on the configuration class when tests modify shared application state that must be reset:

```java
@CucumberContextConfiguration
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
public class CucumberSpringConfiguration {}
```

Note: `@DirtiesContext` slows down execution because it recreates the application context. Use it sparingly and prefer explicit database cleanup in `@After` hooks.

## Cucumber with TestNG

Cucumber integrates with TestNG via `cucumber-testng`.

### Dependency

```xml
<dependency>
    <groupId>io.cucumber</groupId>
    <artifactId>cucumber-testng</artifactId>
    <scope>test</scope>
</dependency>
```

### Runner Class

Extend `AbstractTestNGCucumberTests`:

```java
import io.cucumber.testng.AbstractTestNGCucumberTests;
import io.cucumber.testng.CucumberOptions;

@CucumberOptions(
    features = "src/test/resources/features",
    glue = "com.example.steps",
    plugin = {"pretty", "html:target/cucumber-reports.html"}
)
public class RunCucumberTest extends AbstractTestNGCucumberTests {
}
```

### Parallel Execution with TestNG

Override the `scenarios()` method to enable parallel data provider execution:

```java
@CucumberOptions(
    features = "src/test/resources/features",
    glue = "com.example.steps",
    plugin = {"pretty"}
)
public class RunCucumberTest extends AbstractTestNGCucumberTests {

    @Override
    @DataProvider(parallel = true)
    public Object[][] scenarios() {
        return super.scenarios();
    }
}
```

Configure the thread count in `testng.xml`:

```xml
<!DOCTYPE suite SYSTEM "https://testng.org/testng-1.0.dtd">
<suite name="Cucumber Suite" parallel="methods" thread-count="4">
    <test name="Cucumber Tests">
        <classes>
            <class name="com.example.RunCucumberTest"/>
        </classes>
    </test>
</suite>
```

### DataProvider Integration

TestNG's `@DataProvider` is used internally by `AbstractTestNGCucumberTests` to supply scenarios. Each scenario becomes a test method invocation, enabling TestNG features like retry listeners:

```java
import org.testng.IRetryAnalyzer;
import org.testng.ITestResult;

public class RetryAnalyzer implements IRetryAnalyzer {
    private int count = 0;
    private static final int MAX_RETRY = 2;

    @Override
    public boolean retry(ITestResult result) {
        if (count < MAX_RETRY) {
            count++;
            return true;
        }
        return false;
    }
}
```

Apply via `testng.xml` or a listener:

```xml
<suite name="Cucumber Suite">
    <listeners>
        <listener class-name="com.example.RetryListener"/>
    </listeners>
    <test name="Cucumber Tests">
        <classes>
            <class name="com.example.RunCucumberTest"/>
        </classes>
    </test>
</suite>
```
