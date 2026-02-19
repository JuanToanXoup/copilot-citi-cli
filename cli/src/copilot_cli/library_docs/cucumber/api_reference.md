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
