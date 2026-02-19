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
