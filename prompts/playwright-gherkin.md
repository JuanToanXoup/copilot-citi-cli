Read the feature file at {feature_file} and execute its scenarios using Playwright MCP directly (do NOT run Maven, Cucumber, or any test framework).

Follow this process for each scenario:

1. **Parse the feature file** — extract all Gherkin steps (Given/When/Then/And).

2. **Resolve step definitions** — for each step, use ide_search_text or ide_find_references to find the matching Java step definition method (look for @Given, @When, @Then, @And annotations with matching regex patterns). Read the step definition code to understand what each step does.

3. **Resolve all variables** — trace any parameterized values (environment URLs, UserIDs, passwords, file paths, test data) by searching:
   - Feature file Examples/Scenario Outline tables
   - Properties files (*.properties, *.yml, *.yaml, *.xml)
   - Excel/CSV test data files referenced in the step definitions
   - Config classes or constants referenced in the step def code
   - Use ide_find_definition and ide_find_references to follow the chain until you reach the actual hardcoded values.

4. **Translate to Playwright actions** — convert each resolved step into Playwright MCP tool calls:
   - Navigation → browser_navigate
   - Click → browser_click
   - Type text → browser_type
   - Select dropdown → browser_select_option
   - Assertions → browser_snapshot + verify element text/presence
   - Waits → browser_wait_for_selector

5. **Execute sequentially** — run each Playwright action in order, taking a browser_snapshot after key steps to verify the page state. If a step fails, report which Gherkin step failed and why.

Start by reading the feature file and finding all step definitions before executing anything.
