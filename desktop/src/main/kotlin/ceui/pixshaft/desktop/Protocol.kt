package ceui.pixshaft.desktop

import java.util.concurrent.TimeUnit

object ProtocolRegistrar {
    fun registerCurrentProcess() {
        if (!System.getProperty("os.name").orEmpty().contains("Windows", ignoreCase = true)) return
        val exe = currentLaunchCommand() ?: return
        registerScheme("pixiv", "URL:Pixiv OAuth", exe)
        registerScheme("shaft", "URL:PixShaft", exe)
    }

    private fun currentLaunchCommand(): String? {
        val cmd = ProcessHandle.current().info().command().orElse(null) ?: return null
        // Dev `gradle :desktop:run` launches via java.exe and cannot own pixiv://.
        if (cmd.contains("java", ignoreCase = true)) return null
        return cmd
    }

    private fun registerScheme(scheme: String, description: String, exe: String) {
        val root = "HKCU\\Software\\Classes\\$scheme"
        val command = "\"$exe\" \"%1\""
        runCatching {
            regAdd(root, valueName = null, data = description)
            regAdd(root, valueName = "URL Protocol", data = "")
            // FTA_Show — treat as a URL protocol so Chrome/Edge can ShellExecute it.
            regAdd(root, valueName = "EditFlags", data = "2", dword = true)
            regAdd("$root\\shell\\open\\command", valueName = null, data = command)
            regAdd(
                "HKCU\\Software\\Microsoft\\Internet Explorer\\ProtocolExecute\\$scheme",
                valueName = "WarnOnOpen",
                data = "0",
                dword = true,
            )
        }.onFailure { CrashLog.write(it) }
    }

    private fun regAdd(key: String, valueName: String?, data: String, dword: Boolean = false) {
        val cmd = mutableListOf("reg", "add", key)
        when {
            dword && valueName != null ->
                cmd += listOf("/v", valueName, "/t", "REG_DWORD", "/d", data, "/f")
            valueName == null ->
                cmd += listOf("/ve", "/d", data, "/f")
            else ->
                cmd += listOf("/v", valueName, "/d", data, "/f")
        }
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(3, TimeUnit.SECONDS)) {
            process.destroyForcibly()
        }
    }
}
