// Shared signing config for :androidApp and :gate. Both APKs MUST use the same key, otherwise the
// signature-level GATE permission is not granted and Kale cannot talk to Kapı.
//
// CI passes the real key through environment variables (see README). When they are missing the
// repository's public ci-test.keystore is used and the release notes say so.
val env = System.getenv()
extra["jarvisStoreFile"] = env["JARVIS_KEYSTORE_FILE"]?.takeIf { it.isNotBlank() } ?: rootProject.file("ci-test.keystore").absolutePath
extra["jarvisStorePassword"] = env["JARVIS_KEYSTORE_PASSWORD"]?.takeIf { it.isNotBlank() } ?: "jarvis-ci-test"
extra["jarvisKeyAlias"] = env["JARVIS_KEY_ALIAS"]?.takeIf { it.isNotBlank() } ?: "jarvis-ci-test"
extra["jarvisKeyPassword"] = env["JARVIS_KEY_PASSWORD"]?.takeIf { it.isNotBlank() } ?: "jarvis-ci-test"

/** "0.1.0" -> 100, "1.2.3" -> 10203 */
fun versionCodeOf(v: String): Int {
    val p = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)
    return p[0] * 10000 + p[1] * 100 + p[2]
}
extra["jarvisVersionCode"] = versionCodeOf(project.version.toString())
