// Root build: share the Kotlin plugin versions across modules.
//
// :catalog — pure Kotlin model + loader (kotlinx.serialization), no IntelliJ SDK.
// :plugin  — the CLion plugin (IntelliJ Platform); it declares the IntelliJ
//            Platform Gradle Plugin itself so the SDK tooling stays isolated to
//            that module.
plugins {
    kotlin("jvm") version "2.2.21" apply false
    kotlin("plugin.serialization") version "2.2.21" apply false
}

/** Run [command], optionally feeding [stdin]; throws on a non-zero exit. */
fun runProcess(command: List<String>, env: Map<String, String> = emptyMap(), stdin: String? = null) {
    val process = ProcessBuilder(command).redirectErrorStream(true)
        .also { it.environment().putAll(env) }
        .start()
    if (stdin != null) process.outputStream.use { it.write(stdin.toByteArray()) }
    val output = process.inputStream.bufferedReader().readText()
    if (process.waitFor() != 0) {
        throw GradleException("`${command.first()}` failed (exit ${process.exitValue()}): ${output.trim()}")
    }
}

// One-shot setup: generate the JetBrains plugin-signing key + self-signed
// certificate and store them (plus the password) as GitHub repo secrets via the
// `gh` CLI, so the release workflow can sign and publish to the Marketplace.
//
//   ./gradlew storeSigningSecrets -PsigningPassword=<password>
//
// Requires `openssl` and an authenticated `gh`. PUBLISH_TOKEN is set manually.
tasks.register("storeSigningSecrets") {
    group = "publishing"
    description = "Generate a signing key/cert and store PRIVATE_KEY, CERTIFICATE_CHAIN, PRIVATE_KEY_PASSWORD as gh secrets."
    doLast {
        val password = providers.gradleProperty("signingPassword").orNull
            ?: throw GradleException("Provide the signing key password: -PsigningPassword=<password>")
        val dir = layout.buildDirectory.dir("signing").get().asFile.apply { mkdirs() }
        val key = dir.resolve("private.pem")
        val cert = dir.resolve("chain.crt")
        val env = mapOf("KEYPASS" to password)
        try {
            // Encrypted PKCS#8 private key + a self-signed certificate from it —
            // the password goes via env so it never appears in process args.
            runProcess(
                listOf(
                    "openssl", "genpkey", "-aes-256-cbc", "-algorithm", "RSA",
                    "-pkeyopt", "rsa_keygen_bits:4096", "-pass", "env:KEYPASS", "-out", key.path,
                ),
                env,
            )
            runProcess(
                listOf(
                    "openssl", "req", "-new", "-x509", "-key", key.path, "-passin", "env:KEYPASS",
                    "-days", "3650", "-subj", "/CN=ESPHome Plugin Signing", "-out", cert.path,
                ),
                env,
            )
            // `gh secret set NAME` reads the value from stdin.
            runProcess(listOf("gh", "secret", "set", "PRIVATE_KEY"), stdin = key.readText())
            runProcess(listOf("gh", "secret", "set", "CERTIFICATE_CHAIN"), stdin = cert.readText())
            runProcess(listOf("gh", "secret", "set", "PRIVATE_KEY_PASSWORD"), stdin = password)
            logger.lifecycle(
                "Stored PRIVATE_KEY, CERTIFICATE_CHAIN and PRIVATE_KEY_PASSWORD as GitHub secrets. " +
                    "Set PUBLISH_TOKEN manually (from a Marketplace token).",
            )
        } finally {
            key.delete()
            cert.delete()
        }
    }
}
