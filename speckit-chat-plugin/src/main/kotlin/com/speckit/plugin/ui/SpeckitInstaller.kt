package com.speckit.plugin.ui

import com.intellij.execution.RunContentExecutor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.openapi.project.Project

/**
 * Downloads and extracts the latest Speckit release into the project root.
 */
object SpeckitInstaller {

    fun install(project: Project, afterCompletion: Runnable? = null) {
        val basePath = project.basePath ?: return
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val shellType = if (isWindows) "ps" else "sh"

        val cmd = if (isWindows) {
            val psScript = """
                ${"$"}ErrorActionPreference = 'Stop'
                Write-Host 'Fetching latest spec-kit release...'
                ${"$"}release = Invoke-RestMethod -Uri 'https://api.github.com/repos/github/spec-kit/releases/latest'
                ${"$"}asset = ${"$"}release.assets | Where-Object { ${"$"}_.name -like '*copilot-${shellType}*' } | Select-Object -First 1
                if (-not ${"$"}asset) { Write-Error 'No copilot-${shellType} asset found'; exit 1 }
                ${"$"}tmpZip = Join-Path ${"$"}env:TEMP ('speckit-' + [guid]::NewGuid().ToString('N') + '.zip')
                Write-Host "Downloading ${"$"}(${"$"}asset.browser_download_url)"
                Invoke-WebRequest -Uri ${"$"}asset.browser_download_url -OutFile ${"$"}tmpZip
                Write-Host 'Extracting to ${basePath.replace("\\", "\\\\")}...'
                Expand-Archive -Path ${"$"}tmpZip -DestinationPath '${basePath.replace("'", "''")}' -Force
                Remove-Item ${"$"}tmpZip -Force
                Write-Host 'Done.'
            """.trimIndent()
            GeneralCommandLine("powershell", "-NoProfile", "-Command", psScript)
        } else {
            val shScript = """
                set -e
                TMPZIP=${"$"}(mktemp /tmp/speckit-XXXXXX.zip)
                echo "Fetching latest spec-kit release..."
                URL=${"$"}(curl -sL https://api.github.com/repos/github/spec-kit/releases/latest \
                  | grep -o '"browser_download_url":[^,]*copilot-${shellType}[^"]*' \
                  | cut -d'"' -f4)
                if [ -z "${"$"}URL" ]; then echo "ERROR: No copilot-${shellType} asset found"; exit 1; fi
                echo "Downloading ${"$"}URL"
                curl -Lo "${"$"}TMPZIP" "${"$"}URL"
                echo "Extracting to ${basePath}..."
                unzip -o "${"$"}TMPZIP" -d "${basePath}"
                rm -f "${"$"}TMPZIP"
                echo "Done."
            """.trimIndent()
            GeneralCommandLine("bash", "-c", shScript)
        }

        cmd.withWorkDirectory(basePath)
        val handler = OSProcessHandler(cmd)

        val executor = RunContentExecutor(project, handler)
            .withTitle("Speckit Install")
        if (afterCompletion != null) {
            executor.withAfterCompletion(afterCompletion)
        }
        executor.run()
    }
}
