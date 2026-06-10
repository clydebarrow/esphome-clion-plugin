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
    ) = EsphomeCommandLines.build(
        backend, command, config, executable, EsphomeRunOptions.DEFAULT_DOCKER_IMAGE, device,
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
    fun `docker mounts the config dir and runs on the basename`() {
        assertEquals(
            "docker run --rm -v /home/me/devices:/config -w /config " +
                "ghcr.io/esphome/esphome:latest compile living_room.yaml",
            cmd(EsphomeBackend.DOCKER, EsphomeCommand.COMPILE),
        )
    }
}
