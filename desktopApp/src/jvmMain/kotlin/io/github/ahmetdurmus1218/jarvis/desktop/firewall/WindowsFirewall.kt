package io.github.ahmetdurmus1218.jarvis.desktop.firewall

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Jarvis's outbound firewall on Windows (section 4): block rules for Jarvis.exe, jarvis-kapi.exe,
 * llama-server.exe and kiwix-serve.exe, made once with the user's UAC approval. For a download the
 * kapi rule alone is switched off — again only with a UAC approval — and switched back on when the
 * download session ends or after at most [GATE_MINUTES] minutes, whichever comes first. The
 * elevated helper that holds the gate open also blocks newly unpacked programs before it closes.
 */
class WindowsFirewall(
    val prefix: String = "Jarvis",
    private val shell: (String) -> ShellResult = ::powershell,
) {
    data class Rule(val name: String, val program: File)
    data class ShellResult(val exit: Int, val out: String)

    fun displayName(r: Rule) = "$prefix - ${r.name}"

    private fun q(s: String) = "'" + s.replace("'", "''") + "'"

    /**
     * Rule name → enabled, for the rules that exist (reading rules needs no elevation). No double
     * quotes in the command: Java's Windows command line would split the -Command argument on them.
     */
    fun state(): Map<String, Boolean> {
        val r = shell("Get-NetFirewallRule -DisplayName ${q("$prefix - *")} -ErrorAction SilentlyContinue | ForEach-Object { \$_.DisplayName + [char]9 + \$_.Enabled }")
        return r.out.lines().mapNotNull { l -> l.split('\t').takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim().equals("True", true) } }.toMap()
    }

    fun isInstalled(rules: List<Rule>): Boolean {
        val s = state()
        return rules.all { s[displayName(it)] == true }
    }

    /** Full long path (no 8.3 short names such as RUNNER~1, which the firewall API rejects). */
    private fun path(f: File): String = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)

    /** Text safe inside a PowerShell double-quoted string. */
    private fun dq(s: String) = s.replace("`", "``").replace("$", "`$").replace("\"", "")

    /** PowerShell lines that (re)create one block rule with netsh, failing loudly. */
    private fun blockLines(name: String, programExpr: String): String = """
        netsh advfirewall firewall delete rule "name=$name" | Out-Null
        netsh advfirewall firewall add rule "name=$name" dir=out action=block "program=$programExpr" enable=yes profile=any | Out-Null
        if (${'$'}LASTEXITCODE -ne 0) { throw "netsh add rule failed for $name" }
    """.trimIndent()

    fun installScript(rules: List<Rule>): String = buildString {
        appendLine("\$ErrorActionPreference = 'Stop'")
        for (r in rules) appendLine(blockLines(dq(displayName(r)), dq(path(r.program))))
    }

    /** One UAC prompt; true when every rule exists and is on afterwards. */
    suspend fun install(rules: List<Rule>): Boolean = withContext(Dispatchers.IO) {
        val r = elevated(installScript(rules), wait = true)
        r.exit == 0 && isInstalled(rules)
    }

    suspend fun uninstall(): Boolean = withContext(Dispatchers.IO) {
        elevated("Get-NetFirewallRule -DisplayName ${q("$prefix - *")} -ErrorAction SilentlyContinue | Remove-NetFirewallRule", wait = true)
        state().isEmpty()
    }

    fun gateScript(rule: Rule, dir: File, seconds: Int): String {
        val open = File(dir, "gate.open").absolutePath
        val done = File(dir, "gate.done").absolutePath
        val closed = File(dir, "gate.closed").absolutePath
        val name = displayName(rule)
        return """
            ${'$'}ErrorActionPreference = 'Stop'
            Remove-Item ${q(closed)} -ErrorAction SilentlyContinue
            netsh advfirewall firewall set rule "name=${dq(name)}" new enable=no | Out-Null
            if (${'$'}LASTEXITCODE -ne 0) { throw "could not open the gate" }
            Set-Content -Path ${q(open)} -Value 'open'
            ${'$'}end = (Get-Date).AddSeconds($seconds)
            try {
              while ((Get-Date) -lt ${'$'}end -and -not (Test-Path ${q(done)})) { Start-Sleep -Milliseconds 500 }
              if (Test-Path ${q(done)}) {
                foreach (${'$'}line in Get-Content ${q(done)}) {
                  ${'$'}p = ${'$'}line -split "`t"
                  if (${'$'}p.Length -eq 3 -and ${'$'}p[0] -eq 'BLOCK' -and ${'$'}p[1].StartsWith(${q("$prefix - ")})) {
                    ${'$'}n = ${'$'}p[1]; ${'$'}g = ${'$'}p[2]
                    netsh advfirewall firewall delete rule "name=${'$'}n" | Out-Null
                    netsh advfirewall firewall add rule "name=${'$'}n" dir=out action=block "program=${'$'}g" enable=yes profile=any | Out-Null
                  }
                }
              }
            } finally {
              netsh advfirewall firewall set rule "name=${dq(name)}" new enable=yes | Out-Null
              Remove-Item ${q(open)}, ${q(done)} -ErrorAction SilentlyContinue
              Set-Content -Path ${q(closed)} -Value 'closed'
            }
        """.trimIndent()
    }

    /**
     * Opens the gate for [rule] (UAC). Returns false if the user declined or it did not open within
     * [waitMs]. The helper keeps running elevated until [closeGate] or the time limit.
     */
    suspend fun openGate(rule: Rule, dir: File, seconds: Int = GATE_MINUTES * 60, waitMs: Long = 120_000): Boolean = withContext(Dispatchers.IO) {
        dir.mkdirs()
        listOf("gate.open", "gate.done", "gate.closed").forEach { File(dir, it).delete() }
        val start = elevated(gateScript(rule, dir, seconds), wait = false)
        if (start.exit != 0) return@withContext false
        val deadline = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < deadline) {
            if (File(dir, "gate.open").isFile) return@withContext true
            delay(250)
        }
        false
    }

    /** Ends the session: the helper blocks [newPrograms], switches the kapi rule back on and exits. */
    suspend fun closeGate(dir: File, newPrograms: List<Rule> = emptyList(), waitMs: Long = 30_000): Boolean = withContext(Dispatchers.IO) {
        if (!File(dir, "gate.open").isFile) return@withContext true
        File(dir, "gate.done").writeText(newPrograms.joinToString("\n") { "BLOCK\t${displayName(it)}\t${path(it.program)}" })
        val deadline = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < deadline) {
            if (File(dir, "gate.closed").isFile) return@withContext true
            delay(200)
        }
        false
    }

    /** Output of the last elevated run (for Settings and test diagnostics). */
    @Volatile var lastOutput: String = ""
        private set

    /**
     * Runs [script] elevated through UAC; [wait] = until it ends (its exit code is returned). When
     * Jarvis already runs as administrator there is nothing to elevate and no prompt is shown.
     */
    private fun elevated(script: String, wait: Boolean): ShellResult {
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        val args = "'-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-EncodedCommand','$encoded'"
        val isAdmin = "([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)"
        val outer = if (wait) {
            "if ($isAdmin) { & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand $encoded; exit \$LASTEXITCODE } " +
                "else { \$p = Start-Process powershell.exe -Verb RunAs -WindowStyle Hidden -PassThru -Wait -ArgumentList $args; exit \$p.ExitCode }"
        } else {
            "if ($isAdmin) { Start-Process powershell.exe -WindowStyle Hidden -ArgumentList $args } " +
                "else { Start-Process powershell.exe -Verb RunAs -WindowStyle Hidden -ArgumentList $args }"
        }
        return shell(outer).also { lastOutput = "exit ${it.exit}: ${it.out.take(2_000)}" }
    }

    companion object {
        const val GATE_MINUTES = 10

        fun powershell(command: String): ShellResult {
            val p = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", command)
                .redirectErrorStream(true).start()
            p.outputStream.close()
            val out = p.inputStream.readBytes().decodeToString()
            if (!p.waitFor(15, TimeUnit.MINUTES)) { p.destroyForcibly(); return ShellResult(-1, out) }
            return ShellResult(p.exitValue(), out)
        }

        val isWindows: Boolean get() = System.getProperty("os.name").orEmpty().startsWith("Windows")
    }
}
