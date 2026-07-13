package com.theycallmeboxy.one35config.data

import com.theycallmeboxy.one35config.data.model.Action
import com.theycallmeboxy.one35config.data.model.Bind
import com.theycallmeboxy.one35config.data.model.BindGroup
import com.theycallmeboxy.one35config.data.model.Config
import com.theycallmeboxy.one35config.data.model.Layout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigMappingTest {

    private fun load(name: String): Config {
        val text = javaClass.getResource("/$name")!!.readText()
        return AppJson.decodeFromString(Config.serializer(), text)
    }

    private fun allBinds(cfg: Config): List<Bind> =
        cfg.layers.flatMap { it.landscape.allBinds() + it.portrait.allBinds() }

    @Test
    fun roster_covers_every_bind_in_shipping_configs() {
        for (name in listOf("default.json", "example_minimal.json", "example_two_layer.json")) {
            for (bind in allBinds(load(name))) {
                assertNotNull(
                    "src ${bind.src} in $name is not in the physical roster",
                    PhysicalButton.forCode(bind.src),
                )
            }
        }
    }

    @Test
    fun tate_shoulders_recognized_and_no_r3() {
        // TATE_L/TATE_R are real physical buttons; R3 (318) is not on the device.
        assertEquals(PhysicalButton.TATE_L, PhysicalButton.forCode(309))
        assertEquals(PhysicalButton.TATE_R, PhysicalButton.forCode(306))
        assertNull(PhysicalButton.forCode(318))
        // default.json's portrait uses the tate shoulders as L1/R1 — confirm they're bound there.
        val portrait = load("default.json").layers[0].portrait
        assertNotNull(portrait.bindFor(309))
        assertNotNull(portrait.bindFor(306))
    }

    @Test
    fun serialize_parse_is_idempotent() {
        for (name in listOf("default.json", "example_minimal.json", "example_two_layer.json")) {
            val once = load(name)
            val round = AppJson.decodeFromString(
                Config.serializer(),
                AppJson.encodeToString(Config.serializer(), once),
            )
            assertEquals("re-encode changed $name", once, round)
        }
    }

    @Test
    fun withBind_preserves_unknown_src() {
        // A layout with an unrecognized src (e.g. a future/undocumented code) must survive edits.
        val unknown = Bind(src = 9999, tap = Action("gamepad_key", code = 304))
        val layout = Layout(controllerButtons = BindGroup(listOf(unknown)))

        val edited = layout.withBind(PhysicalButton.A, Bind(304, Action("gamepad_key", 305)))

        assertNotNull("unknown src dropped", edited.bindFor(9999))
        assertEquals(305, edited.bindFor(304)!!.tap.code)
    }

    @Test
    fun withBind_null_clears_and_system_split() {
        val layout = Layout()
            .withBind(PhysicalButton.A, Bind(304, Action("gamepad_key", 304)))
            .withBind(PhysicalButton.BACK, Bind(158, Action("android_key", 158)))

        assertNotNull(layout.bindFor(304))
        // System buttons live in system_buttons; the rest in controller_buttons.
        assertTrue(layout.systemButtons!!.binds.any { it.src == 158 })
        assertTrue(layout.controllerButtons!!.binds.any { it.src == 304 })

        val cleared = layout.withBind(PhysicalButton.A, null)
        assertNull("A should be unbound after clearing", cleared.bindFor(304))
    }
}
