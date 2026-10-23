package com.mqclient.topology

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * GraphicsLayer 截图 + PNG 编码的真实 Compose 运行时冒烟：
 * 只验证「录到的内容能编码成 PNG 字节」，不覆盖实际 Kuiver 图。
 */
@OptIn(ExperimentalTestApi::class)
class TopologyExportUiTest {

    @Test
    fun `GraphicsLayer 截图可编码为 PNG`() = runComposeUiTest {
        var captured: ByteArray? = null
        setContent {
            val layer = rememberGraphicsLayer()
            val scope = rememberCoroutineScope()
            var clickCount by mutableStateOf(0)
            Box(
                Modifier.size(120.dp, 80.dp).drawWithContent {
                    layer.record(
                        size = IntSize(size.width.toInt(), size.height.toInt()),
                    ) {
                        drawRect(Color.Red)
                        this@drawWithContent.drawContent()
                    }
                    drawLayer(layer)
                },
            )
            Button(onClick = {
                clickCount += 1
                scope.launch {
                    captured = layer.toImageBitmap().toPngByteArray()
                }
            }) {
                Text("export-$clickCount")
            }
        }
        waitForIdle()
        onNodeWithText("export-0").performClick()
        waitForIdle()

        val bytes = captured
        assertNotNull(bytes, "导出应产生 PNG 字节")
        assertTrue(bytes.size > 8, "PNG 输出不应为空")
        assertTrue(bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte(), "PNG 签名不匹配")
    }
}
