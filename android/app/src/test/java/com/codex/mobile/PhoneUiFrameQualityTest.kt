package com.codex.mobile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneUiFrameQualityTest {
    @Test
    fun rejectsBlackProducerFrameWithOnlyOnePointerPixel() {
        val pixels = IntArray(20_000) { 0xff000000.toInt() }
        pixels[10_000] = 0xffff0000.toInt()
        assertFalse(PhoneUiFrameQuality.inspectPixels(pixels).usable)
    }

    @Test
    fun acceptsDarkPageWithSparseVisibleControls() {
        val pixels = IntArray(20_000) { 0xff000000.toInt() }
        repeat(100) { pixels[it * 100] = 0xffdddddd.toInt() }
        assertTrue(PhoneUiFrameQuality.inspectPixels(pixels).usable)
    }

    @Test
    fun acceptsBrightLoadingPage() {
        val pixels = IntArray(10_000) { 0xffffffff.toInt() }
        assertTrue(PhoneUiFrameQuality.inspectPixels(pixels).usable)
    }

    @Test
    fun recognizesExistingTargetTaskOnVirtualDisplay() {
        val source = JSONObject().put("tasks", JSONObject().put("roots", JSONArray()
            .put(JSONObject().put("displayId", 4).put("top", "com.taobao.taobao/com.taobao.Main")
                .put("base", "com.taobao.taobao/com.taobao.Welcome"))
            .put(JSONObject().put("displayId", 0).put("top", "com.other/.Main").put("base", "com.other/.Main"))))
        assertTrue(PhoneUiAgentRuntime.taskExistsOnDisplay(source, "com.taobao.taobao", 4))
        assertFalse(PhoneUiAgentRuntime.taskExistsOnDisplay(source, "com.taobao.taobao", 0))
    }
}
