package io.esphome.clion.api

import io.esphome.clion.api.toolwindow.EntityIcons
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Every icon the type/device-class maps reference is actually bundled. */
class EntityIconsTest {

    @Test
    fun `all mapped icon resources exist`() {
        for (name in EntityIcons.iconResourceNames()) {
            assertNotNull(
                "missing bundled icon: $name.svg",
                EntityIcons::class.java.getResource("/icons/esphome/$name.svg"),
            )
        }
    }
}
