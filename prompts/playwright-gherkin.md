Read the feature file at `{feature_file}`. Execute every scenario using Playwright MCP tools. Do NOT run Maven, Cucumber, or any test framework.

## Project structure

This is a Citi BDD project. Key conventions:
- **Feature files:** `bdd/src/test/resources/FeatureFiles/`
- **Step definitions:** `bdd/src/test/java/stepDef/<platform>/` (e.g. `stepDef/cbol/`, `stepDef/mbol/`)
- **Page actions:** `bdd/src/test/java/pageActions/<platform>/`
- **Page locators:** `bdd/src/test/java/pageLocators/<platform>/`
- **Test data:** `bdd/src/test/java/dataProvider/` (Excel `.xlsx` files)
- **Data annotation:** Feature files use `##@data@<path>@<sheet>` comments to reference Excel data
- **Environment URLs:** Resolved in step def code via switch statements (e.g. `UAT1` → `https://uat01.citi.com/`)

## Process

### 1. Parse the feature file
Extract all Gherkin steps, the Examples table, and any `##@data@` annotation.

### 2. Resolve step definitions
For each step text, find its Java step definition:
1. Use `ide_find_symbol` to search for the StepDef class matching the feature name (e.g. feature `CBOL_Net_Worth` → class `AUTO_RR_CBOL_Net_Worth_StepDef`)
2. Read the StepDef class. Match each Gherkin step to a `@Given`/`@When`/`@Then`/`@And` annotation by comparing the step text to the annotation's pattern.
3. If a step is not in that class, search other StepDef classes in the same `stepDef/<platform>/` directory (shared steps like login are often in a separate class like `CBOL_Login_StepDef`).
4. If still not found, use `ide_find_symbol` with keywords from the step text.

### 3. Read the step definition code
For each matched step definition method:
1. Read the method body to understand the Selenium actions it performs.
2. The code uses Page Object pattern: step defs call **PageActions** methods which use **PageLocators**. Use `ide_find_definition` on method calls to trace into PageActions and read the actual Selenium logic (xpaths, clicks, waits, assertions).
3. Extract all XPath selectors, CSS selectors, URLs, and expected text values.

### 4. Resolve all variables
Substitute `<placeholders>` from the Scenario Outline's Examples table. For environment mappings, look for `switch` or `if` statements in the step def (e.g. `"UAT1"` → `"https://uat01.citi.com/"`).

### 5. Translate to Playwright and execute
Map each Selenium action to the equivalent Playwright MCP call:

| Selenium action | Playwright MCP tool |
|---|---|
| `driver.get(url)` | `browser_navigate` |
| `click` / `clickElementByXpathID` | `browser_click` with the xpath |
| `sendKeys` / enter text | `browser_type` |
| `Select` dropdown | `browser_select_option` |
| `waitForElement` / `waitForElementTextToLoad` | `browser_snapshot`, check text |
| `verifyElementPresent` / assertions | `browser_snapshot`, verify element exists |

Execute actions sequentially. Take a `browser_snapshot` after each key interaction to verify page state. If a step fails, report which Gherkin step failed and the error.

## Execution order
1. Resolve ALL step definitions and variables BEFORE executing anything.
2. Then execute the Playwright actions in scenario order.
3. Report pass/fail per Gherkin step.
