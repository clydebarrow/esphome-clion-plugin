package io.esphome.clion.run

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** The command line built for each backend/command, the part users care about. */
class EsphomeCommandLinesTest {

    private val config = File("/home/me/devices/living_room.yaml")

    private fun cmd(
        backend: EsphomeBackend,
        command: EsphomeCommand,
        executable: String? = "/usr/bin/esphome",
        device: String? = null,
        stateReporting: StateReporting = StateReporting.DEFAULT,
        resetBeforeLogs: Boolean = false,
        extraArgs: String? = null,
        cacheDir: File? = null,
    ) = EsphomeCommandLines.build(
        backend, command, config, executable, EsphomeRunOptions.DEFAULT_DOCKER_IMAGE, device,
        stateReporting = stateReporting, resetBeforeLogs = resetBeforeLogs, extraArgs = extraArgs, cacheDir = cacheDir,
    ).commandLineString

    @Test
    fun `local compile runs esphome on the file`() {
        assertEquals("/usr/bin/esphome compile /home/me/devices/living_room.yaml", cmd(EsphomeBackend.LOCAL, EsphomeCommand.COMPILE))
    }

    @Test
    fun `local upload with device adds --device`() {
        assertEquals(
            "/usr/bin/esphome upload /home/me/devices/living_room.yaml --device 192.168.1.5",
            cmd(EsphomeBackend.LOCAL, EsphomeCommand.UPLOAD, device = "192.168.1.5"),
        )
    }

    @Test
    fun `device is ignored for commands that don't flash`() {
        assertEquals(
            "/usr/bin/esphome compile /home/me/devices/living_room.yaml",
            cmd(EsphomeBackend.LOCAL, EsphomeCommand.COMPILE, device = "192.168.1.5"),
        )
    }

    @Test
    fun `venv backend runs the venv esphome like local`() {
        assertEquals(
            "/venv/bin/esphome compile /home/me/devices/living_room.yaml",
            EsphomeCommandLines.build(
                EsphomeBackend.VENV, EsphomeCommand.COMPILE, config, "/venv/bin/esphome",
                EsphomeRunOptions.DEFAULT_DOCKER_IMAGE, null,
            ).commandLineString,
        )
    }

    @Test
    fun `docker mounts the config dir and runs on the basename`() {
        assertEquals(
            "docker run --rm -v /home/me/devices:/config -w /config " +
                "ghcr.io/esphome/esphome:latest compile living_room.yaml",
            cmd(EsphomeBackend.DOCKER, EsphomeCommand.COMPILE),
        )
    }

    @Test
    fun `docker adds a cache mount when given`() {
        assertEquals(
            "docker run --rm -v /home/me/devices:/config -v /home/me/.cache/esphome:/cache " +
                "-w /config ghcr.io/esphome/esphome:latest compile living_room.yaml",
            cmd(EsphomeBackend.DOCKER, EsphomeCommand.COMPILE, cacheDir = File("/home/me/.cache/esphome")),
        )
    }

    @Test
    fun `state reporting flag applies only to logs and run`() {
        assertEquals(
            "/usr/bin/esphome logs /home/me/devices/living_room.yaml --no-states",
            cmd(EsphomeBackend.LOCAL, EsphomeCommand.LOGS, stateReporting = StateReporting.OFF),
        )
        // compile doesn't stream logs, so the flag is dropped.
        assertEquals(
            "/usr/bin/esphome compile /home/me/devices/living_room.yaml",
            cmd(EsphomeBackend.LOCAL, EsphomeCommand.COMPILE, stateReporting = StateReporting.OFF),
        )
    }

    @Test
    fun `reset flag applies only to logs and run`() {
        assertEquals(
            "/usr/bin/esphome logs /home/me/devices/living_room.yaml --reset",
            cmd(EsphomeBackend.LOCAL, EsphomeCommand.LOGS, resetBeforeLogs = true),
        )
        assertEquals(
            "/usr/bin/esphome upload /home/me/devices/living_room.yaml",
            cmd(EsphomeBackend.LOCAL, EsphomeCommand.UPLOAD, resetBeforeLogs = true),
        )
    }

    @Test
    fun `extra args are appended after the config file`() {
        assertEquals(
            "/usr/bin/esphome compile /home/me/devices/living_room.yaml -s name value --only-generate",
            cmd(EsphomeBackend.LOCAL, EsphomeCommand.COMPILE, extraArgs = "-s name value --only-generate"),
        )
    }
}
