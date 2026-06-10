package io.esphome.clion.run

import io.esphome.clion.settings.EsphomeSettings

/**
 * Resolves which `esphome` to invoke for a given [EsphomeBackend], shared by run
 * configurations and background config validation so the two never diverge:
 *
 *  - [EsphomeBackend.VENV] — the managed venv's `esphome`, if provisioned.
 *  - [EsphomeBackend.LOCAL] — the configured (Settings | Tools | ESPHome) or
 *    `PATH` executable.
 *  - [EsphomeBackend.DOCKER] — no host executable is needed (the image provides
 *    it); this falls back to the local/PATH executable only as a convenience for
 *    callers that can run locally, and is otherwise unused.
 */
object EsphomeExecutables {
    fun forBackend(backend: EsphomeBackend): String? = when (backend) {
        EsphomeBackend.VENV -> EsphomeVenv.esphome().takeIf { it.canExecute() }?.path
        else -> EsphomeSettings.getInstance().resolveExecutable()
    }
}
