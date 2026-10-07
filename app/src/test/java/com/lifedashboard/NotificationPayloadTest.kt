package com.lifedashboard

import android.app.Notification
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class NotificationPayloadTest {
    @Test fun invalidNumberDoesNotDiscardTitleTextOrOtherFields() {
        val extras = Bundle().apply {
            putCharSequence(Notification.EXTRA_TITLE, "테스트")
            putCharSequence(Notification.EXTRA_TEXT, "보존할 원문")
            putDouble("bad", Double.NaN)
            putString("good", "보존할 필드")
        }
        val result = NotificationPayload.captureExtras(extras)
        assertEquals("테스트", result.getString("title"))
        assertEquals("보존할 원문", result.getString("text"))
        assertEquals("보존할 필드", result.getJSONObject("extras").getString("good"))
        assertTrue(result.getJSONObject("extras").getJSONObject("bad").getBoolean("unavailable"))
        assertTrue(result.getJSONArray("captureErrors").length() > 0)
    }
    @Test fun messagesAndPrimitiveArraysRemainStructured() {
        val extras = Bundle().apply {
            putParcelableArray("messages", arrayOf(Bundle().apply { putString("text", "익명 메시지") }))
            putIntArray("numbers", intArrayOf(1, 2))
        }
        val result = NotificationPayload.captureExtras(extras).getJSONObject("extras")
        assertEquals("익명 메시지", result.getJSONArray("messages").getJSONObject(0).getString("text"))
        assertEquals(2, result.getJSONArray("numbers").getInt(1))
    }
    @Test fun recursiveBundleIsIsolatedInsteadOfOverflowingStack() {
        val extras = Bundle().apply { putString("good", "원본"); putBundle("loop", this) }
        val result = NotificationPayload.captureExtras(extras)
        assertEquals("원본", result.getJSONObject("extras").getString("good"))
        assertTrue(result.getJSONArray("captureErrors").length() > 0)
    }
}
