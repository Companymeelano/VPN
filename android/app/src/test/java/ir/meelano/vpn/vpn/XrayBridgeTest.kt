package ir.meelano.vpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class XrayBridgeTest {

    private class VoidGomobileShape {
        companion object {
            var calls: Int = 0

            @JvmStatic
            fun registerDialerController(controller: Any) {
                check(controller === TOKEN)
                calls++
                // Intentionally returns Unit -> Java static void -> Method.invoke returns null.
            }

            val TOKEN = Any()
        }
    }

    @Test
    fun successfulStaticVoidInvocationIsNotMistakenForAMissingMethod() {
        VoidGomobileShape.calls = 0
        val result = XrayBridge.invokeRequiredStatic(
            VoidGomobileShape::class.java,
            "registerDialerController",
            VoidGomobileShape.TOKEN,
        )
        assertNull(result)
        assertEquals(1, VoidGomobileShape.calls)
    }

    @Test
    fun genuinelyMissingRequiredMethodStillHasANamedFailure() {
        val error = assertThrows(XrayBridge.Missing::class.java) {
            XrayBridge.invokeRequiredStatic(VoidGomobileShape::class.java, "notPresent")
        }
        org.junit.Assert.assertTrue(error.message.orEmpty().contains("notPresent"))
    }
}
