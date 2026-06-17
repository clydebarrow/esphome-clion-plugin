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
        uploadSpeed: String? = null,
        extraArgs: String? = null,
        cacheDir: File? = null,
    ) = EsphomeCommandLines.build(
        backend, command, config, executable, EsphomeRunOptions.DEFAULT_DOCKER_IMAGE, device,
        stateReporting = stateReporting, resetBeforeLogs = resetBeforeLogs, uploadSpeed = uploadSpeed,
        extraArgs = extraArgs, cacheDir = cacheDir,
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
    fun `upload speed adds --upload_speed for run and upload`() {
        assertEquals(
            "/usr/bin/esphome upload /home/me/devices/living_room.yaml --device /dev/ttyUSB0 --upload_speed 921600",
            cmd(EsphomeBackend.LOCAL, EsphomeCommand.UPLOAD, device = "/dev/ttyUSB0", uploadSpeed = "921600"),
        )
        assertEquals(
            "/usr/bin/esphome run /home/me/devices/living_room.yaml --upload_speed 460800",
            cmd(EsphomeBackend.LOCAL, EsphomeCommand.RUN, uploadSpeed = "460800"),
        )
    }

    @Test
    fun `upload speed is dropped for logs, blank, and OTA targets`() {
        // logs doesn't flash
        assertEquals(
            "/usr/bin/esphome logs /home/me/devices/living_room.yaml",
            cmd(EsphomeBackend.LOCAL, EsphomeCommand.LOGS, uploadSpeed = "921600"),
        )
        // blank speed → no flag
        assertEquals(
            "/usr/bin/esphome upload /home/me/devices/living_room.yaml",
            cmd(EsphomeBackend.LOCAL, EsphomeCommand.UPLOAD, uploadSpeed = ""),
        )
        // OTA host → speed is meaningless, so it's omitted
        assertEquals(
            "/usr/bin/esphome upload /home/me/devices/living_room.yaml --device device.local",
            cmd(EsphomeBackend.LOCAL, EsphomeCommand.UPLOAD, device = "device.local", uploadSpeed = "921600"),
        )
    }

    @Test
    fun `extra args are appended after the config file`() {
        assertEquals(
            "/usr/bin/esphome compile /home/me/devices/living_room.yaml -s name value --only-generate",
            cmd(EsphomeBackend.LOCAL, EsphomeCommand.COMPILE, extraArgs = "-s name value --only-generate"),
        )
    }

    @Test
    fun `parses lib dirs from sdl2-config --libs output`() {
        assertEquals(
            listOf("/opt/homebrew/lib"),
            EsphomeCommandLines.parseLibDirs("-L/opt/homebrew/lib -lSDL2"),
        )
        assertEquals(
            listOf("/opt/homebrew/lib", "/usr/local/lib"),
            EsphomeCommandLines.parseLibDirs("-L/opt/homebrew/lib -L/usr/local/lib -Wl,-rpath,/x -lSDL2"),
        )
        assertEquals(emptyList<String>(), EsphomeCommandLines.parseLibDirs("-lSDL2"))
    }

    @Test
    fun `network vs serial device detection`() {
        assertEquals(true, EsphomeCommandLines.isNetworkDevice("living-room.local"))
        assertEquals(true, EsphomeCommandLines.isNetworkDevice("10.0.0.5"))
        assertEquals(false, EsphomeCommandLines.isNetworkDevice("/dev/ttyUSB0"))
        assertEquals(false, EsphomeCommandLines.isNetworkDevice("/dev/cu.usbserial-1420"))
        assertEquals(false, EsphomeCommandLines.isNetworkDevice("COM3"))
        assertEquals(false, EsphomeCommandLines.isNetworkDevice("")) // blank: ESPHome may pick serial
        assertEquals(false, EsphomeCommandLines.isNetworkDevice(null))
    }

    // --- buildConfig: validation runs `esphome config` on the same backend as a run ---

    @Test
    fun `config validation on local runs esphome config on the file`() {
        assertEquals(
            "/usr/bin/esphome config /home/me/devices/living_room.yaml",
            EsphomeCommandLines.buildConfig(
                EsphomeBackend.LOCAL, config, "/usr/bin/esphome", EsphomeRunOptions.DEFAULT_DOCKER_IMAGE,
            ).commandLineString,
        )
    }

    @Test
    fun `config validation on venv uses the venv esphome`() {
        assertEquals(
            "/venv/bin/esphome config /home/me/devices/living_room.yaml",
            EsphomeCommandLines.buildConfig(
                EsphomeBackend.VENV, config, "/venv/bin/esphome", EsphomeRunOptions.DEFAULT_DOCKER_IMAGE,
            ).commandLineString,
        )
    }

    @Test
    fun `config validation on docker mounts the dir and runs config on the basename`() {
        assertEquals(
            "docker run --rm -v /home/me/devices:/config -w /config " +
                "ghcr.io/esphome/esphome:latest config living_room.yaml",
            EsphomeCommandLines.buildConfig(
                EsphomeBackend.DOCKER, config, executable = null,
                dockerImage = EsphomeRunOptions.DEFAULT_DOCKER_IMAGE,
            ).commandLineString,
        )
    }

    @Test
    fun `config validation on docker adds the cache mount when given`() {
        assertEquals(
            "docker run --rm -v /home/me/devices:/config -v /home/me/.cache/esphome:/cache " +
                "-w /config ghcr.io/esphome/esphome:latest config living_room.yaml",
            EsphomeCommandLines.buildConfig(
                EsphomeBackend.DOCKER, config, executable = null,
                dockerImage = EsphomeRunOptions.DEFAULT_DOCKER_IMAGE,
                cacheDir = File("/home/me/.cache/esphome"),
            ).commandLineString,
        )
    }
}
