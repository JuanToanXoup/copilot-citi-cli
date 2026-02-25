rootProject.name = "copilot-citi-cli"

include("cli")
include("agent-builder")
include("speckit-mcp")
include("copilot-electron")
includeBuild("ide-index")
includeBuild("copilot-chat")
includeBuild("speckit-companion")
includeBuild("speckit-plugin")
includeBuild("playwright-companion")
