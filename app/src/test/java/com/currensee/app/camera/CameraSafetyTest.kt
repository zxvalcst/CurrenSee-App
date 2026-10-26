package com.currensee.app.camera

import androidx.camera.core.ImageProxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.lang.reflect.Proxy

class CameraSafetyTest {
    @Test
    fun imageIsClosedAfterSuccessAndConsumerFailure() {
        var closed = 0
        val image = Proxy.newProxyInstance(
            ImageProxy::class.java.classLoader, arrayOf(ImageProxy::class.java)
        ) { _, method, _ ->
            if (method.name == "close") closed++
            null
        } as ImageProxy
        consumeCapturedImage(image) { assertSame(image, it) }
        assertEquals(1, closed)
        assertThrows(IllegalStateException::class.java) {
            consumeCapturedImage(image) { error("Consumer gagal") }
        }
        assertEquals(2, closed)
    }

    @Test
    fun permissionDecisionsRespectGrantRationaleAndBlockedState() {
        assertEquals(CameraStatus.PermissionRequired, permissionStatus(false, false, false))
        assertEquals(CameraStatus.PermissionDenied, permissionStatus(false, true, false))
        assertEquals(CameraStatus.PermissionBlocked, permissionStatus(false, false, true))
        assertEquals(CameraStatus.Starting, permissionStatus(true, false, true))
        // Izin dicabut setelah grant: boleh diminta lagi, tidak langsung dianggap diblokir.
        assertEquals(CameraStatus.PermissionRequired, permissionStatus(false, false, false))
    }
}
