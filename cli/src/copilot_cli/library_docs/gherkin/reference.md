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

| Operator | Meaning  | Example                                |
|----------|----------|----------------------------------------|
| `and`    | Both     | `@smoke and @fast`                     |
| `or`     | Either   | `@api or @ui`                          |
| `not`    | Exclude  | `not @wip`                             |
| `()`     | Grouping | `(@smoke or @regression) and not @wip` |

Common expressions: `@smoke and not @slow`, `@api or @ui`, `not @manual`, `@payments and @critical and not @flaky`.

Tags can be placed on: `Feature`, `Rule`, `Scenario`, `Scenario Outline`, and `Examples`. Tags propagate downward -- a `@smoke` tag on `Feature` is inherited by every scenario inside it. The effective tags for a scenario are the union of all ancestor tags plus its own.

```bash
cucumber --tags "@smoke and not @wip"                         # CLI
mvn test -Dcucumber.filter.tags="@smoke and not @wip"         # JVM
pytest -m "smoke and not wip"                                 # pytest-bdd
```

## Data Table Patterns

**Single-column list** (no header, each row is one item):

```gherkin
Given the following fruits:
  | apple  |
  | banana |
```

**Key-value pairs** (2 columns, no header -- converted to `Map<String, String>`):

```gherkin
Given the user profile:
  | first_name | Alice          |
  | email      | alice@test.com |
```

**Table with header** (first row is the header -- `List<Map<String, String>>`):

```gherkin
Given these users exist:
  | name  | email          | role  |
  | Alice | alice@test.com | admin |
  | Bob   | bob@test.com   | user  |
```

**Nested tables**: not supported. Use JSON/YAML in a Doc String, flatten into columns, or split into multiple steps.

**Empty cells**: `| Alice | |` yields `""` (empty string), not `null`.

**Escaping pipe**: `| value with \| pipe |`. Use `\\` for a literal backslash.

**Whitespace**: leading/trailing spaces in cells are trimmed. `| Alice |` and `|  Alice  |` both yield `"Alice"`.

## Scenario Outline Advanced

### Multiple Examples tables with tags

```gherkin
Scenario Outline: Login attempt
  Given the user enters "<username>" and "<password>"
  When they submit the form
  Then the result should be "<outcome>"

  @positive @smoke
  Examples: Valid credentials
    | username | password  | outcome |
    | admin    | secret123 | success |

  @negative
  Examples: Invalid credentials
    | username | password | outcome       |
    | admin    | wrong    | access denied |

  @edge
  Examples: Boundary cases
    | username | password | outcome           |
    |          | secret   | username required |
```

Tags on `Examples` apply only to scenarios generated from that table.

### Placeholders in doc strings and data tables

Placeholders `<name>` are substituted inside doc strings and data table cells, not only in step text:

```gherkin
Scenario Outline: API request
  Given the request body:
    """json
    {"name": "<name>", "role": "<role>"}
    """
  When the API is called
  Then the status code is <status>

  Examples:
    | name  | role  | status |
    | Alice | admin | 200    |
```

### Combining Scenario Outline with Data Tables

```gherkin
Scenario Outline: User with permissions
  Given a user with role "<role>"
  And the user has permissions:
    | permission |
    | <perm1>    |
    | <perm2>    |
  When the user is saved
  Then it should "<result>"

  Examples:
    | role  | perm1 | perm2 | result  |
    | admin | read  | write | succeed |
    | guest | read  |       | succeed |
```

## Doc Strings Advanced

**Triple-quote vs triple-backtick**: both `"""` and ` ``` ` are interchangeable. Use backticks when content itself contains `"""`.

**Content type annotation**: an optional hint follows the opening delimiter (`"""json`, `"""xml`, `"""yaml`, `"""sql`). Cucumber passes it to the step definition but does not validate it.

**Indentation handling**: the column of the opening `"""` sets the baseline; that common indent is stripped from every line.

```gherkin
Given the text:
  """
  Line one
    Indented line
  Line three
  """
```

Step definition receives `Line one\n  Indented line\nLine three`.

**Common uses**: JSON, XML, YAML, SQL, HTML, multi-line plain text (email bodies, error messages, etc.).

## Best Practices

- **One scenario, one behavior.** If a scenario name contains "and", split it.
- **Declarative over imperative.** Write `Given the user is logged in` not `Given I type "admin" into "#username"`.
- **Keep scenarios independent.** No ordering dependencies between scenarios.
- **Background: Given steps only.** No `When` or `Then` in Background.
- **Scenario Outline for data variation, not behavior variation.** All Examples rows should exercise the same logical path.
- **Feature files per domain/capability** (`payments.feature`), not per page/screen.
- **Avoid conditional logic.** Gherkin has no `if/else`. Write separate scenarios for each path.
- **Tags for categorization, not test data.** `@smoke`, `@regression`, `@api` -- do not encode data in tags.
- **3-7 steps per scenario.** Fewer than 3 may lack context; more than 7 likely combines multiple behaviors.

## Anti-Patterns

**Incidental details** -- UI selectors or API internals in business scenarios:

```gherkin
# Bad:  Given I click on div#login-form > input.username
# Good: Given the user is on the login page
```

**Scenario soup** -- many unrelated scenarios in one file. Use `Rule` or separate feature files.

**Cucumber as a test runner** -- procedural `step 1: open browser, step 2: navigate...` instead of behavior specs.

**Brittle scenarios** -- depending on specific IDs or timestamps (`Then the user ID should be 42`). Use flexible assertions (`Then a new user should be created`).

**Too many steps** -- 10+ steps usually means multiple behaviors. Split them.

**Given-When-Then-When-Then chains** -- multiple When-Then pairs test multiple behaviors. Split into separate scenarios.

## i18n (Internationalization)

Place `# language: xx` as the very first line of the `.feature` file.

```gherkin
# language: fr
Fonctionnalité: Connexion utilisateur
  Scénario: Connexion réussie
    Soit un utilisateur avec le nom "Alice"
    Quand l'utilisateur se connecte avec un mot de passe valide
    Alors l'utilisateur voit le tableau de bord
```

| Language   | Code  | Feature            | Scenario | Given      | When   | Then     |
|------------|-------|--------------------|----------|------------|--------|----------|
| English    | en    | Feature            | Scenario | Given      | When   | Then     |
| French     | fr    | Fonctionnalité     | Scénario | Soit       | Quand  | Alors    |
| German     | de    | Funktionalität     | Szenario | Angenommen | Wenn   | Dann     |
| Spanish    | es    | Característica     | Escenario| Dado       | Cuando | Entonces |
| Portuguese | pt    | Funcionalidade     | Cenário  | Dado       | Quando | Então    |
| Chinese    | zh-CN | 功能               | 场景      | 假如       | 当     | 那么      |
| Japanese   | ja    | フィーチャ          | シナリオ  | 前提       | もし    | ならば    |
| Korean     | ko    | 기능               | 시나리오  | 조건       | 먼저    | 그러면   |
| Russian    | ru    | Функция            | Сценарий | Допустим   | Когда  | Тогда    |
| Arabic     | ar    | خاصية              | سيناريو  | بفرض       | متى    | اذاً     |
| Hindi      | hi    | रूप लेख            | परिदृश्य  | अगर       | जब     | तब       |

- Only one language per `.feature` file.
- Step definition code stays in the programming language -- only Gherkin keywords change.
- Tag names remain ASCII (`@smoke`, not localized).
- Run `cucumber --i18n <code>` to see all keywords for a language.

## Gherkin with IDE Support

**IntelliJ IDEA** (Cucumber for Java/Kotlin plugin):
- Ctrl+Click on a step to jump to its definition. Auto-complete for step text.
- Green gutter icons to run/debug individual scenarios. Yellow markers for unimplemented steps.
- Rename refactoring updates both step definitions and Gherkin files.

**VS Code** (Cucumber/Gherkin Full Support extension):
- Syntax highlighting, auto-complete, go-to-definition.
- Outline view for feature/scenario navigation. Snippets (`sce` + Tab).
- Configure `cucumber.glue` and `cucumber.features` in `settings.json`.

**Eclipse** (Cucumber Eclipse plugin):
- Syntax highlighting, Ctrl+Click step linking, integrated test runner.

## Feature File Organization

**By feature/domain (recommended):**

```
features/
├── authentication/
│   ├── login.feature
│   └── password-reset.feature
├── payments/
│   ├── checkout.feature
│   └── refunds.feature
└── support/
    └── step_definitions/
```

**By layer:** `features/api/`, `features/ui/`, `features/integration/`.

**Naming**: use kebab-case (`user-login.feature`) or snake_case (`user_login.feature`). Be consistent. Avoid generic names (`test.feature`).

**One Feature per file.** Keeps files focused, simplifies navigation, and makes tag filtering predictable.

**Description as documentation** -- the free-text after `Feature:` is ignored by Cucumber but serves as living documentation for the team. Include business rules, user stories, or acceptance criteria.

## Edge Cases & Gotchas

**Step matching across keywords**: `Given`, `When`, `Then`, `And`, `But`, `*` are stripped before matching. `Given "X"` and `When "X"` invoke the **same** step definition. Use distinct wording if different behavior is needed.

**Ambiguous step definitions**: if >1 regex matches a step, Cucumber raises `AmbiguousStepDefinitionsException` and fails. Fix by making patterns more specific or merging duplicates.

**Undefined steps**: Cucumber marks them as undefined, skips them, and prints suggested code snippets for implementation.

**Pending steps**: throw `PendingException` (Java) or call `pending()` (Ruby) to mark work-in-progress. The scenario is marked pending, not failed.

**Empty scenarios**: valid syntax but Cucumber warns. Prefer `@wip` or `@skip` tags.

**Unicode**: fully supported in step text, tables, doc strings, tags, and descriptions. Step definition patterns must also handle Unicode.

**Size guidelines**: no hard limits, but keep features under ~20 scenarios, scenarios at 3-7 steps, and Examples tables under ~20 rows. Large `.feature` files slow IDE parsing and are harder to review.
