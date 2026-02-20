package com.citigroup.copilotchat.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.EditorTextField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.*

/**
 * Panel for recording browser interactions via Playwright codegen.
 * Three states: IDLE → RECORDING → COMPLETED, managed by CardLayout.
 */
class RecorderPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val log = Logger.getInstance(RecorderPanel::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private enum class State { IDLE, RECORDING, COMPLETED }

    private val cardLayout = CardLayout()
    private val cardPanel = JPanel(cardLayout)

    // IDLE card controls
    private val urlField = JBTextField("https://example.com")
    private val targetCombo = ComboBox(arrayOf(
        "javascript", "python", "python-async", "python-pytest", "csharp", "java"
    ))
    private val deviceCombo = ComboBox(arrayOf(
        "(none)", "Desktop Chrome", "Desktop Firefox", "Desktop Safari",
        "Pixel 5", "iPhone 12", "iPad Pro 11"
    ))
    private val browserCombo = ComboBox(arrayOf("chromium", "firefox", "webkit"))
    private val startButton = JButton("Start Recording")

    // RECORDING card controls
    private val recordingUrlLabel = JLabel()
    private val recordingTargetLabel = JLabel()
    private val stopButton = JButton("Stop Recording")

    // COMPLETED card controls
    private val codeHeaderLabel = JLabel()
    private lateinit var codeEditor: EditorTextField
    private val copyButton = JButton("Copy")
    private val saveButton = JButton("Save")
    private val replayButton = JButton("Replay")
    private val newRecordingButton = JButton("New Recording")

    // Process state
    private var playwrightProcess: Process? = null
    private var outputFile: File? = null
    private var generatedCode: String = ""

    init {
        border = JBUI.Borders.empty(8)

        cardPanel.add(buildIdleCard(), State.IDLE.name)
        cardPanel.add(buildRecordingCard(), State.RECORDING.name)
        cardPanel.add(buildCompletedCard(), State.COMPLETED.name)
        add(cardPanel, BorderLayout.CENTER)

        showState(State.IDLE)

        // Wire actions
        startButton.addActionListener { startRecording() }
        stopButton.addActionListener { stopRecording() }
        copyButton.addActionListener { copyCode() }
        saveButton.addActionListener { saveCode() }
        replayButton.addActionListener { replayCode() }
        newRecordingButton.addActionListener { showState(State.IDLE) }
    }

    private fun buildIdleCard(): JPanel {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(4)
        }

        var row = 0

        // URL
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        panel.add(JLabel("URL:"), gbc)
        gbc.gridx = 1; gbc.gridy = row; gbc.weightx = 1.0; gbc.gridwidth = 3
        panel.add(urlField, gbc)
        gbc.gridwidth = 1
        row++

        // Target + Device + Browser
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        panel.add(JLabel("Target:"), gbc)
        gbc.gridx = 1; gbc.weightx = 0.3
        panel.add(targetCombo, gbc)

        gbc.gridx = 2; gbc.weightx = 0.0
        panel.add(JLabel("  Device:"), gbc)
        gbc.gridx = 3; gbc.weightx = 0.3
        panel.add(deviceCombo, gbc)
        row++

        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        panel.add(JLabel("Browser:"), gbc)
        gbc.gridx = 1; gbc.weightx = 0.3
        panel.add(browserCombo, gbc)
        row++

        // Start button
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 4; gbc.weightx = 0.0
        gbc.fill = GridBagConstraints.NONE; gbc.anchor = GridBagConstraints.CENTER
        gbc.insets = JBUI.insets(12, 4, 4, 4)
        panel.add(startButton, gbc)

        // Wrap so it's vertically centered
        val wrapper = JPanel(GridBagLayout())
        wrapper.add(panel)
        return wrapper
    }

    private fun buildRecordingCard(): JPanel {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            gridx = 0; fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(4)
            weightx = 1.0
        }

        gbc.gridy = 0
        val spinnerLabel = JLabel("Recording in progress...", AllIcons.Process.Step_1, SwingConstants.LEFT).apply {
            font = font.deriveFont(Font.BOLD, 14f)
        }
        panel.add(spinnerLabel, gbc)

        gbc.gridy = 1
        val infoPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            add(JLabel("URL:"))
            add(recordingUrlLabel)
            add(JLabel("  |  Target:"))
            add(recordingTargetLabel)
        }
        panel.add(infoPanel, gbc)

        gbc.gridy = 2
        panel.add(JLabel("Close the browser or click Stop to finish."), gbc)

        gbc.gridy = 3; gbc.fill = GridBagConstraints.NONE; gbc.anchor = GridBagConstraints.CENTER
        gbc.insets = JBUI.insets(12, 4, 4, 4)
        panel.add(stopButton, gbc)

        val wrapper = JPanel(GridBagLayout())
        wrapper.add(panel)
        return wrapper
    }

    private fun buildCompletedCard(): JPanel {
        val panel = JPanel(BorderLayout(0, 4))

        // Header with label and action buttons
        val headerPanel = JPanel(BorderLayout()).apply {
            codeHeaderLabel.font = codeHeaderLabel.font.deriveFont(Font.BOLD)
            add(codeHeaderLabel, BorderLayout.WEST)
            val buttonBar = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                add(copyButton)
                add(saveButton)
                add(replayButton)
            }
            add(buttonBar, BorderLayout.EAST)
        }
        panel.add(headerPanel, BorderLayout.NORTH)

        // Code editor
        val document = EditorFactory.getInstance().createDocument("")
        codeEditor = EditorTextField(document, project, PlainTextFileType.INSTANCE, true, false)
        codeEditor.setOneLineMode(false)
        codeEditor.preferredSize = Dimension(0, 400)
        panel.add(codeEditor, BorderLayout.CENTER)

        // Bottom button
        val bottomBar = JPanel(FlowLayout(FlowLayout.CENTER)).apply {
            add(newRecordingButton)
        }
        panel.add(bottomBar, BorderLayout.SOUTH)

        return panel
    }

    private fun showState(state: State) {
        cardLayout.show(cardPanel, state.name)
    }

    private fun startRecording() {
        val url = urlField.text.trim()
        if (url.isEmpty()) {
            Messages.showWarningDialog("Please enter a URL.", "No URL")
            return
        }

        val target = targetCombo.selectedItem as String
        val device = deviceCombo.selectedItem as String
        val browser = browserCombo.selectedItem as String

        // Update recording card labels
        recordingUrlLabel.text = url
        recordingTargetLabel.text = target

        // Create temp file for output
        outputFile = File.createTempFile("playwright_recording_", fileExtensionForTarget(target))
        outputFile!!.deleteOnExit()

        // Build command
        val npx = if (SystemInfo.isWindows) "npx.cmd" else "npx"
        val cmd = mutableListOf(npx, "playwright", "codegen", "--target=$target", "--output=${outputFile!!.absolutePath}")

        if (device != "(none)") {
            cmd.add("--device=$device")
        }
        if (browser != "chromium") {
            cmd.add("--browser=$browser")
        }
        cmd.add(url)

        showState(State.RECORDING)
        startButton.isEnabled = false

        scope.launch(Dispatchers.IO) {
            try {
                val pb = ProcessBuilder(cmd)
                pb.redirectErrorStream(false)
                pb.environment()["PLAYWRIGHT_BROWSERS_PATH"] = "0"

                val proc = pb.start()
                playwrightProcess = proc

                // Drain stdout
                launch(Dispatchers.IO) {
                    try {
                        proc.inputStream.bufferedReader().use { reader ->
                            while (true) {
                                val line = reader.readLine() ?: break
                                log.debug("playwright stdout: $line")
                            }
                        }
                    } catch (_: Exception) {}
                }

                // Drain stderr
                launch(Dispatchers.IO) {
                    try {
                        proc.errorStream.bufferedReader().use { reader ->
                            while (true) {
                                val line = reader.readLine() ?: break
                                log.debug("playwright stderr: $line")
                            }
                        }
                    } catch (_: Exception) {}
                }

                // Wait for process to exit
                proc.waitFor()

                // Read the output file
                val code = if (outputFile!!.exists() && outputFile!!.length() > 0) {
                    outputFile!!.readText()
                } else {
                    "// No code was generated. Did you interact with the page?"
                }
                generatedCode = code

                withContext(Dispatchers.Main) {
                    onRecordingComplete(target)
                }
            } catch (e: Exception) {
                log.warn("Failed to run playwright codegen", e)
                withContext(Dispatchers.Main) {
                    Messages.showErrorDialog(
                        "Failed to start Playwright:\n${e.message}\n\nMake sure npx and playwright are installed.",
                        "Playwright Error"
                    )
                    showState(State.IDLE)
                    startButton.isEnabled = true
                }
            } finally {
                playwrightProcess = null
            }
        }
    }

    private fun onRecordingComplete(target: String) {
        startButton.isEnabled = true
        codeHeaderLabel.text = "Generated Code ($target)"
        ApplicationManager.getApplication().runWriteAction {
            codeEditor.document.setText(generatedCode)
        }
        showState(State.COMPLETED)
    }

    private fun stopRecording() {
        scope.launch(Dispatchers.IO) {
            playwrightProcess?.let { proc ->
                proc.destroy()
                try {
                    withTimeout(5000) {
                        while (proc.isAlive) {
                            delay(100)
                        }
                    }
                } catch (_: TimeoutCancellationException) {
                    proc.destroyForcibly()
                }
            }
        }
    }

    private fun copyCode() {
        val text = generatedCode
        if (text.isNotEmpty()) {
            CopyPasteManager.getInstance().setContents(StringSelection(text))
        }
    }

    private fun saveCode() {
        val target = targetCombo.selectedItem as String
        val ext = fileExtensionForTarget(target)
        val descriptor = FileSaverDescriptor("Save Generated Code", "Choose where to save the Playwright test", ext.removePrefix("."))
        val wrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val result = wrapper.save(null as com.intellij.openapi.vfs.VirtualFile?, "test${ext}") ?: return

        scope.launch(Dispatchers.IO) {
            try {
                result.file.writeText(generatedCode)
                LocalFileSystem.getInstance().refreshAndFindFileByIoFile(result.file)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Messages.showErrorDialog("Failed to save file:\n${e.message}", "Save Error")
                }
            }
        }
    }

    private fun replayCode() {
        val target = targetCombo.selectedItem as String
        val ext = fileExtensionForTarget(target)
        val tempFile = File.createTempFile("playwright_replay_", ext)
        tempFile.deleteOnExit()
        tempFile.writeText(generatedCode)

        val npx = if (SystemInfo.isWindows) "npx.cmd" else "npx"
        val cmd = when {
            target.startsWith("python") -> listOf("python", "-m", "pytest", tempFile.absolutePath, "-v")
            target == "java" -> listOf(npx, "playwright", "test", tempFile.absolutePath)
            else -> listOf(npx, "playwright", "test", tempFile.absolutePath)
        }

        scope.launch(Dispatchers.IO) {
            try {
                val pb = ProcessBuilder(cmd)
                pb.redirectErrorStream(true)
                val proc = pb.start()
                val output = proc.inputStream.bufferedReader().readText()
                proc.waitFor()

                withContext(Dispatchers.Main) {
                    val exitCode = proc.exitValue()
                    val title = if (exitCode == 0) "Replay Passed" else "Replay Failed (exit $exitCode)"
                    val icon = if (exitCode == 0) Messages.getInformationIcon() else Messages.getErrorIcon()
                    Messages.showDialog(
                        output.take(3000),
                        title,
                        arrayOf("OK"),
                        0,
                        icon
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Messages.showErrorDialog("Failed to replay:\n${e.message}", "Replay Error")
                }
            }
        }
    }

    private fun fileExtensionForTarget(target: String): String = when (target) {
        "javascript" -> ".js"
        "python", "python-async", "python-pytest" -> ".py"
        "csharp" -> ".cs"
        "java" -> ".java"
        else -> ".js"
    }

    override fun dispose() {
        playwrightProcess?.let { proc ->
            proc.destroy()
            if (proc.isAlive) proc.destroyForcibly()
        }
        scope.cancel()
    }
}
