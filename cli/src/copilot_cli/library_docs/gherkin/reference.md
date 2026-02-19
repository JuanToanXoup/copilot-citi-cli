# Gherkin Syntax Reference

Gherkin is the plain-text language Cucumber uses to define test cases. Files use the `.feature` extension.

## File Structure

```gherkin
# language: en          (optional, sets spoken language)
@tag1 @tag2
Feature: Feature title
  Optional description text (free-form, ignored by Cucumber).

  Background:
    Given some shared precondition

  Rule: Business rule description

    Scenario: Scenario title
      Given a precondition
      When an action occurs
      Then an expected outcome

    Scenario Outline: Parameterized scenario
      Given I have <count> items
      When I remove <removed>
      Then I should have <remaining> items

      Examples:
        | count | removed | remaining |
        | 12    | 5       | 7         |
        | 20    | 3       | 17        |
```

## Keywords

### Feature

Must be the first keyword in a `.feature` file. Groups related scenarios.

```gherkin
Feature: User login
  Users should be able to log in with valid credentials.
```

### Rule (Gherkin 6+)

Groups scenarios that illustrate a single business rule within a Feature.

```gherkin
Rule: Customers with 10+ purchases get a discount
  Scenario: Eligible customer
    Given a customer with 15 purchases
    Then they should receive a 10% discount
```

### Scenario (alias: Example)

A concrete test case composed of steps. `Scenario` and `Example` are interchangeable.

```gherkin
Scenario: Successful withdrawal
  Given the account balance is $100
  When the user withdraws $40
  Then the account balance should be $60
```

### Steps: Given / When / Then / And / But

| Keyword | Purpose                          |
|---------|----------------------------------|
| Given   | Establish initial context/state  |
| When    | Describe an action or event      |
| Then    | Assert expected outcome          |
| And     | Continue previous step type      |
| But     | Continue previous step (negation)|
| *       | Bullet-style alternative         |

```gherkin
Scenario: Multiple steps
  Given the user is logged in
  And the shopping cart is empty
  When the user adds "Widget" to the cart
  And the user adds "Gadget" to the cart
  Then the cart should contain 2 items
  But the cart total should not exceed $100
```

Step keywords are **not** considered during matching -- only the text after the keyword matters. `Given "X"` and `When "X"` match the same step definition.

### Background

Steps shared across every Scenario in a Feature (or Rule). Runs before each Scenario.

```gherkin
Background:
  Given the application is running
  And the database is seeded

Scenario: First test
  When ...

Scenario: Second test
  When ...
```

- Place after `Feature:` (or `Rule:`) and before the first `Scenario:`.
- Do not use `Background` to set up complicated state -- keep it short.
- Only use `Given` steps in a Background.

### Scenario Outline (alias: Scenario Template)

Runs the same scenario multiple times with different data from an `Examples` table. Placeholders use angle brackets `<name>`.

```gherkin
Scenario Outline: Eating cucumbers
  Given there are <start> cucumbers
  When I eat <eat> cucumbers
  Then I should have <left> cucumbers

  Examples:
    | start | eat | left |
    |    12 |   5 |    7 |
    |    20 |   5 |   15 |

  Examples: Edge cases
    | start | eat | left |
    |     0 |   0 |    0 |
```

Multiple `Examples` tables are allowed; each can have its own name and tags.

## Tags

Tags are `@`-prefixed labels placed above `Feature`, `Rule`, `Scenario`, `Scenario Outline`, or `Examples`. They are inherited by child elements.

```gherkin
@smoke
Feature: Payments

  @critical @payments
  Scenario: Process valid payment
    ...
```

Use tag expressions to filter execution: `@smoke and not @slow`, `@payments or @billing`.

## Data Tables

Provide structured tabular data as a step argument. The first row is typically a header.

```gherkin
Given the following users exist:
  | name  | email            | role  |
  | Alice | alice@test.com   | admin |
  | Bob   | bob@test.com     | user  |
```

Data tables are passed to step definitions as `DataTable` (or auto-converted to `List<Map<String, String>>`, `List<List<String>>`, etc.).

## Doc Strings

Multi-line string arguments delimited by triple quotes `"""` or triple backticks.

```gherkin
Given the following JSON payload:
  """json
  {
    "name": "Alice",
    "role": "admin"
  }
  """
```

An optional content type (e.g., `json`, `xml`) can follow the opening delimiter for documentation purposes.

## Comments

Lines starting with `#` (with optional leading whitespace). Only allowed on their own line, not inline.

```gherkin
# This is a comment
Feature: Example
```

## Localization

Gherkin supports 70+ spoken languages. Declare with a `# language:` header:

```gherkin
# language: fr
Fonctionnalite: Connexion utilisateur
```

## Quick Keyword Summary

| Keyword            | Followed by colon? | Purpose                        |
|--------------------|-------------------|--------------------------------|
| Feature            | Yes               | Top-level grouping             |
| Rule               | Yes               | Business rule grouping         |
| Scenario / Example | Yes               | Single test case               |
| Scenario Outline   | Yes               | Parameterized test case        |
| Background         | Yes               | Shared preconditions           |
| Examples           | Yes               | Data for Scenario Outline      |
| Given/When/Then    | No                | Step keywords                  |
| And/But            | No                | Continuation step keywords     |
| @tag               | No                | Metadata / filtering           |
| #                  | No                | Comment                        |
| \|                 | No                | Data table cell separator      |
| """                 | No                | Doc string delimiter           |

## Tag Expressions (Filtering)

Tag expressions use boolean operators to select which scenarios to run.

### Operators

| Operator | Meaning    | Example                        |
|----------|------------|--------------------------------|
| `and`    | Both match | `@smoke and @fast`             |
| `or`     | Either     | `@api or @ui`                  |
| `not`    | Exclude    | `not @wip`                     |
| `()`     | Grouping   | `(@smoke or @regression) and not @wip` |

### Common expressions

```
@smoke and not @slow
@api or @ui
(@smoke or @regression) and not @wip
@payments and @critical and not @flaky
not @manual
```

### Where tags can be placed

```gherkin
@feature-tag
Feature: Shopping Cart

  @rule-tag
  Rule: Discounts apply above $50

    @scenario-tag
    Scenario: Apply discount
      ...

    @outline-tag
    Scenario Outline: Variable discount
      ...

      @positive
      Examples: Valid amounts
        | amount |
        | 60     |

      @negative
      Examples: Invalid amounts
        | amount |
        | 10     |
```

### Tag inheritance

Tags propagate downward. A `@smoke` tag on `Feature` is inherited by every `Rule`, `Scenario`, `Scenario Outline`, and `Examples` inside it. The effective tags for a scenario are the union of all ancestor tags plus its own.

### Running with tag filter

```bash
# Cucumber JVM
mvn test -Dcucumber.filter.tags="@smoke and not @wip"

# Cucumber CLI
cucumber --tags "@smoke and not @wip"

# Behave (Python)
behave --tags="@smoke" --tags="~@wip"

# pytest-bdd
pytest -m "smoke and not wip"
```

## Data Table Patterns

### Single-column list

```gherkin
Given the following fruits:
  | apple  |
  | banana |
  | cherry |
```

No header row; each row is one item. Passed as `List<String>` or `List<List<String>>`.

### Key-value pairs (2 columns, no header)

```gherkin
Given the user profile:
  | first_name | Alice          |
  | last_name  | Smith          |
  | email      | alice@test.com |
```

Converted to `Map<String, String>` by most Cucumber implementations.

### Table with header row

```gherkin
Given the following users exist:
  | name  | email            | role  |
  | Alice | alice@test.com   | admin |
  | Bob   | bob@test.com     | user  |
```

First row is the header. Rows become `List<Map<String, String>>` keyed by header values.

### Nested data tables

Gherkin does not support nested tables. Alternatives:
- Use JSON/YAML inside a Doc String for complex nested structures.
- Flatten nested data into additional columns.
- Split into multiple steps with separate tables.

### Empty cells

```gherkin
| name  | nickname |
| Alice |          |
| Bob   | Bobby    |
```

An empty cell produces an empty string `""`, not `null`.

### Escaping the pipe character

```gherkin
| expression       |
| value with \| pipe |
```

Use `\|` inside a cell to represent a literal pipe. Use `\\` for a literal backslash.

### Whitespace handling

Leading and trailing whitespace within cells is **trimmed**. `| Alice |` and `|  Alice  |` both yield `"Alice"`. To preserve whitespace, wrap the value in quotes in step definitions.

## Scenario Outline Advanced

### Multiple Examples tables with different tags

```gherkin
Scenario Outline: Login attempt
  Given the user enters "<username>" and "<password>"
  When they submit the form
  Then the result should be "<outcome>"

  @positive @smoke
  Examples: Valid credentials
    | username | password  | outcome |
    | admin    | secret123 | success |
    | user1    | pass1     | success |

  @negative
  Examples: Invalid credentials
    | username | password | outcome      |
    | admin    | wrong    | access denied |
    | unknown  | any      | access denied |

  @edge
  Examples: Boundary cases
    | username | password | outcome       |
    |          | secret   | username required |
    | admin    |          | password required |
```

Each `Examples` table generates its own set of scenarios. Tags on `Examples` apply only to the scenarios generated from that table.

### Placeholders in doc strings

```gherkin
Scenario Outline: API request
  Given the request body:
    """json
    {
      "name": "<name>",
      "role": "<role>"
    }
    """
  When the API is called
  Then the status code is <status>

  Examples:
    | name  | role  | status |
    | Alice | admin | 200    |
    | Bob   | guest | 403    |
```

Placeholders `<name>` are substituted inside doc strings and data tables, not only in step text.

### Scenario Outline with Data Tables

```gherkin
Scenario Outline: Create user with permissions
  Given a user with role "<role>"
  And the user has permissions:
    | permission   |
    | <perm1>      |
    | <perm2>      |
  When the user is saved
  Then the operation should "<result>"

  Examples:
    | role  | perm1 | perm2  | result  |
    | admin | read  | write  | succeed |
    | guest | read  |        | succeed |
```

Placeholders inside data table cells are replaced before the step definition receives the table.

### Complex parameterization example

```gherkin
Scenario Outline: Shipping cost calculation
  Given the package weighs <weight> kg
  And the destination is "<zone>"
  And the service level is "<service>"
  When shipping cost is calculated
  Then the cost should be $<cost>
  And delivery estimate should be <days> days

  @domestic
  Examples: Domestic standard
    | weight | zone     | service  | cost  | days |
    | 1      | local    | standard | 5.00  | 5    |
    | 5      | national | standard | 12.50 | 7    |

  @international @slow
  Examples: International economy
    | weight | zone   | service | cost  | days |
    | 2      | europe | economy | 25.00 | 14   |
    | 10     | asia   | economy | 60.00 | 21   |
```

## Doc Strings Advanced

### Triple-quote vs triple-backtick

Both delimiters are interchangeable:

```gherkin
# Triple-quote style
Given the payload:
  """
  Hello World
  """

# Triple-backtick style
Given the payload:
  ```
  Hello World
  ```
```

Triple backticks are useful when the content itself contains `"""`.

### Content type annotation

An optional media type hint can follow the opening delimiter:

```gherkin
Given the JSON response:
  """json
  {"status": "ok"}
  """

Given the XML config:
  """xml
  <config><debug>true</debug></config>
  """

Given the YAML manifest:
  """yaml
  name: app
  version: 1.0
  """
```

The content type is passed to the step definition but Cucumber does not parse or validate it.

### Indentation handling

The common leading indent (from the opening `"""`) is stripped from every line. The opening delimiter's column determines the baseline.

```gherkin
Scenario: Indentation example
  Given the text:
    """
    Line one
      Indented line
    Line three
    """
```

The step definition receives:
```
Line one
  Indented line
Line three
```

### Common doc string use cases

```gherkin
# SQL
Given the query:
  """sql
  SELECT u.name, u.email
  FROM users u
  WHERE u.active = true
  ORDER BY u.name ASC
  """

# HTML
Then the page contains:
  """html
  <div class="alert">
    <p>Payment successful</p>
  </div>
  """

# Multi-line plain text
Then the email body should be:
  """
  Dear Customer,

  Your order #12345 has been shipped.

  Regards,
  Support Team
  """
```

## Best Practices

**One scenario, one behavior.** Each scenario should test exactly one business rule or outcome. If a scenario name contains "and", split it into two scenarios.

```gherkin
# Bad
Scenario: User logs in and views dashboard and updates profile

# Good
Scenario: User logs in with valid credentials
Scenario: User views dashboard after login
Scenario: User updates their profile
```

**Declarative over imperative.** Describe *what* happens, not *how* the user interacts with the UI.

```gherkin
# Imperative (avoid)
Given I navigate to "/login"
And I type "admin" into the "#username" field
And I type "secret" into the "#password" field
And I click the "#login-btn" button
Then I should see the "#dashboard" element

# Declarative (prefer)
Given the user is on the login page
When the user logs in with valid credentials
Then the user should see the dashboard
```

**Keep scenarios independent.** Scenarios must not depend on execution order. Each scenario starts from a clean state via `Background` or its own `Given` steps.

**Background should only contain Given steps.** Background sets up context. It must not contain `When` or `Then` steps.

**Scenario Outline for data variation, not behavior variation.** All rows in an `Examples` table should exercise the same logical path with different inputs. Different behaviors need separate scenarios.

**Feature files per domain or capability.** Organize by business domain (`login.feature`, `payments.feature`), not by UI page or technical layer.

**Avoid conditional logic.** Gherkin has no `if/else`. If you need branching, write separate scenarios for each path.

**Use tags for categorization, not for test data.** Tags classify scenarios (`@smoke`, `@regression`, `@api`). Do not encode test data or parameters in tags.

**Limit steps per scenario.** Aim for 3-7 steps. Fewer than 3 may lack context; more than 7 likely combines multiple behaviors.

## Anti-Patterns

### Incidental details

Including UI-specific or implementation details in business-level scenarios.

```gherkin
# Anti-pattern
Given I click on div#login-form > input.username
When I send a POST request to /api/v2/auth with header X-Token

# Better
Given the user is on the login page
When the user authenticates with valid credentials
```

### Scenario soup

Dumping many unrelated scenarios into a single feature file with no logical grouping. Use `Rule` blocks or separate feature files to organize.

### Cucumber as a test runner

Writing procedural, script-like steps instead of behavior specifications.

```gherkin
# Anti-pattern (procedural)
Scenario: Test user flow
  Given step 1: open browser
  And step 2: navigate to URL
  And step 3: enter data
  And step 4: click submit
  And step 5: verify database record
  And step 6: check email sent
  And step 7: close browser
```

### Brittle scenarios

Depending on specific IDs, timestamps, or database state that may change.

```gherkin
# Brittle
Then the user ID should be 42
And the timestamp should be "2024-01-15T10:30:00Z"

# Robust
Then a new user should be created
And the creation timestamp should be recent
```

### Too many steps

Scenarios with 10+ steps are hard to read and usually test multiple behaviors. Split them.

### Given-When-Then-When-Then chains

Multiple When-Then pairs in one scenario indicate multiple behaviors being tested.

```gherkin
# Anti-pattern
Scenario: Full user journey
  Given the user is logged in
  When the user adds an item to the cart
  Then the cart count is 1
  When the user checks out
  Then the order is confirmed
  When the user views order history
  Then the order appears in the list

# Better: split into three scenarios
Scenario: Add item to cart
Scenario: Checkout with items in cart
Scenario: View order in history
```

## i18n (Internationalization)

### Language declaration

Place `# language: xx` as the very first line of the `.feature` file.

```gherkin
# language: fr
Fonctionnalité: Connexion utilisateur
  En tant qu'utilisateur enregistré
  Je veux me connecter à l'application

  Scénario: Connexion réussie
    Soit un utilisateur avec le nom "Alice"
    Quand l'utilisateur se connecte avec un mot de passe valide
    Alors l'utilisateur voit le tableau de bord
```

### Common languages and their keywords

| Language | Code  | Feature             | Scenario  | Given  | When   | Then   |
|----------|-------|---------------------|-----------|--------|--------|--------|
| English  | en    | Feature             | Scenario  | Given  | When   | Then   |
| French   | fr    | Fonctionnalité      | Scénario  | Soit   | Quand  | Alors  |
| German   | de    | Funktionalität      | Szenario  | Angenommen | Wenn | Dann   |
| Spanish  | es    | Característica      | Escenario | Dado   | Cuando | Entonces |
| Portuguese | pt  | Funcionalidade      | Cenário   | Dado   | Quando | Então  |
| Chinese  | zh-CN | 功能                | 场景       | 假如    | 当     | 那么   |
| Japanese | ja    | フィーチャ           | シナリオ   | 前提    | もし   | ならば  |
| Korean   | ko    | 기능                | 시나리오   | 조건    | 먼저   | 그러면  |
| Russian  | ru    | Функция             | Сценарий  | Допустим | Когда | Тогда  |
| Arabic   | ar    | خاصية               | سيناريو   | بفرض   | متى   | اذاً   |
| Hindi    | hi    | रूप लेख             | परिदृश्य   | अगर    | जब    | तब     |

### Mixed language considerations

- Only one language per `.feature` file.
- Step definition code (Java, Python, etc.) is always in the programming language -- only the Gherkin keywords change.
- Tag names remain ASCII (`@smoke`, not localized).
- `And`, `But`, and `*` also have localized equivalents. Run `cucumber --i18n <code>` to see all keywords for a language.

## Gherkin with IDE Support

### IntelliJ IDEA (Cucumber for Java / Kotlin plugin)

- **Step navigation**: Ctrl+Click (Cmd+Click on macOS) on a step to jump to its definition.
- **Auto-complete**: Type a step keyword and the IDE suggests matching step definitions.
- **Run scenarios**: Green gutter icons next to `Scenario` or `Feature` to run/debug individually.
- **Gutter markers**: Green check for implemented steps, yellow warning for unimplemented.
- **Rename refactoring**: Renaming a step definition updates Gherkin files.
- **Syntax highlighting**: Keywords, tags, placeholders, tables, and doc strings are color-coded.

### VS Code (Cucumber / Gherkin extension)

- **Cucumber (Gherkin) Full Support** extension: syntax highlighting, auto-complete, go-to-definition.
- **Outline view**: Navigate features and scenarios via the document outline panel.
- **Snippets**: Type `sce` + Tab to scaffold a scenario block.
- **Format on save**: Auto-formats data tables and indentation.
- Configure `cucumber.glue` and `cucumber.features` in `settings.json` for step matching.

### Eclipse (Cucumber Eclipse plugin)

- Syntax highlighting and content assist for `.feature` files.
- Step definition linking via Ctrl+Click.
- Integrated test runner with scenario-level results.
- Template support for new feature files.

## Feature File Organization

### Directory structure patterns

**By feature / domain (recommended):**

```
features/
├── authentication/
│   ├── login.feature
│   ├── logout.feature
│   └── password-reset.feature
├── payments/
│   ├── checkout.feature
│   ├── refunds.feature
│   └── payment-methods.feature
├── inventory/
│   ├── stock-management.feature
│   └── product-catalog.feature
└── support/
    └── step_definitions/
```

**By layer:**

```
features/
├── api/
│   ├── auth-api.feature
│   └── payments-api.feature
├── ui/
│   ├── auth-ui.feature
│   └── payments-ui.feature
└── integration/
    └── end-to-end.feature
```

### Naming conventions

- Use **kebab-case** (`user-login.feature`) or **snake_case** (`user_login.feature`). Pick one and stay consistent.
- Name files after the feature or capability they describe.
- Avoid generic names like `test.feature` or `misc.feature`.

### One Feature per file

Each `.feature` file should contain exactly one `Feature` block. This keeps files focused, simplifies navigation, and makes tag filtering predictable.

### Feature description as living documentation

```gherkin
Feature: Password Reset
  As a registered user who forgot their password,
  I want to reset my password via email,
  so that I can regain access to my account.

  Business rules:
  - Reset link expires after 24 hours
  - User must verify their email address
  - Password must meet complexity requirements
```

The free-text description is ignored by Cucumber but serves as documentation for the team.

## Edge Cases & Gotchas

### Step text matching across keywords

`Given`, `When`, `Then`, `And`, `But`, and `*` are stripped before matching. The following two steps invoke the **same** step definition:

```gherkin
Given the account balance is $100
When the account balance is $100    # same definition!
```

Use distinct wording if different behavior is needed.

### Ambiguous step definitions

If more than one step definition regex matches a step, Cucumber raises an `AmbiguousStepDefinitionsException` and fails immediately. Fix by making patterns more specific or merging duplicates.

### Undefined steps

When a step has no matching definition, Cucumber marks it as undefined, skips it, and prints a suggested code snippet:

```
You can implement step definitions for undefined steps with these snippets:

@Given("the account balance is {int}")
public void theAccountBalanceIs(int balance) {
    // Write code here
}
```

### Pending steps

Throw `PendingException` (Java) or call `pending()` (Ruby) inside a step definition to mark it as work-in-progress. The step turns yellow in reports and the scenario is marked as pending, not failed.

### Empty scenarios

```gherkin
Scenario: Placeholder
```

Valid syntax. Cucumber logs a warning but does not fail. Use `@wip` or `@skip` tags instead of leaving scenarios empty.

### Unicode in step text

Gherkin fully supports Unicode. Step text, data tables, doc strings, tags, and descriptions can all contain non-ASCII characters. Step definition patterns must also handle Unicode matching.

```gherkin
Scenario: Vérifier le résumé du panier
  Given l'utilisateur a ajouté "Écouteurs Bluetooth" au panier
  Then le résumé affiche "1 article — 49,99 €"
```

### Maximum scenario and feature size

There is no hard limit on the number of scenarios per feature or steps per scenario. However, practical guidelines:
- Keep features under **20 scenarios** (split larger ones by `Rule` or separate files).
- Keep scenarios at **3-7 steps**.
- Keep `Examples` tables under **20 rows** (beyond that, consider property-based testing).
- Very large `.feature` files slow down IDE parsing and are harder to review in pull requests.
