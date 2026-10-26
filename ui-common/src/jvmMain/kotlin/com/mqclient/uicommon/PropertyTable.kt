package com.mqclient.uicommon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mqclient.uicommon.i18n.stringResource
import kotlinx.coroutines.launch

/**
 * 「Key-Value」属性表（各 MQ 模块属性面板/对话框的统一展示组件）：
 *
 *  - **斑马纹**：奇数行（index 1/3/...）`surfaceVariant` 浅底 + 4dp 微圆角，偶数行透明；
 *  - **宽度**：整表最大 [PropertyTableMaxWidth] 并**水平居中**（超宽屏下不再把两列拉散）；
 *  - **分栏**：Key `weight(0.35f)` / Value `weight(0.65f)`，都是左对齐（次要色 / 主色），
 *    各自最多两行后省略；
 *  - **可选中**：Value 包在 `SelectionContainer` 里，鼠标可拖选；
 *  - **复制**：行尾 IconButton（24dp / 图标 14dp）点击写剪贴板，Snackbar 提示
 *    「已复制」（复用 i18n `msg_detail_copied`）—— Snackbar host 内置在组件里，
 *    对话框 / 面板中开箱即用，调用方无需接 host；无复制按钮的行留同宽占位，
 *    保证斑马纹里的分栏线逐行对齐；
 *  - **空值**：null / 空串显示 `-`，且不渲染复制按钮（`copyable` 同时为 true 才显示）。
 *
 * 分栏为什么用权重而不是定宽：旧写法 Key 定宽 120dp 既装不下
 * `x-dead-letter-exchange`（约 158dp，折行），又与 Value 没有比例关系 —— 窗口越宽，
 * Key 与 Value 的距离越离谱。权重 + 居中限宽让「聚拢」与「长 Key 有地方站」同时成立。
 *
 * 分组场景：小标题 + [PropertyTable]（见 [PropertyTableSection]），不再套卡片 ——
 * 斑马纹本身就是视觉容器，双层背景会互相叠深。
 */
data class PropertyItem(
    val key: String,
    val value: String?,
    val copyable: Boolean = true,
    /** 可选说明行：渲染在 Key 下方的小字（labelSmall 次要色，两行截断）；null 不渲染。 */
    val description: String? = null,
)

/**
 * 属性表的最大宽度（含 [PropertyTableSection] 的标题与混合页面的其它块）。
 *
 * 900dp 的依据：按 0.35 / 0.65 分栏，Key 列约 300dp，比最长的 MQ 参数名
 * （`x-dead-letter-routing-key` 一类，~200dp）还多出一半余量；再宽只会把短 Value
 * 推得更远。桌面端多数窗口宽度下这个值不会触发（窗口本身没那么宽），它只在全屏 /
 * 超宽屏时生效 —— 那里正是需要「聚拢」的场景。
 */
val PropertyTableMaxWidth: Dp = 900.dp

/** Key 列权重（Value 拿剩下的 0.65）。 */
private const val KeyWeight = 0.35f
private const val ValueWeight = 0.65f

/** 行尾复制位的固定宽度（无按钮时用同宽 Spacer 占位）。 */
private val CopySlotWidth = 24.dp

/**
 * 「限宽 + 居中」的列容器：[PropertyTable] 与 [PropertyTableSection] 共用，
 * 混合页面（属性表旁边还有别的全宽块）也拿它夹同一个宽度。
 *
 * 修饰符顺序与 `ObjectCardGrid` 一致：先 `widthIn(max)` 夹约束，再 `fillMaxWidth()`
 * 在夹好的上限内填充 —— 反过来写 `fillMaxWidth` 会先领到父级无限宽。
 */
@Composable
fun WidthCappedColumn(
    modifier: Modifier = Modifier,
    maxWidth: Dp = PropertyTableMaxWidth,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.widthIn(max = maxWidth).fillMaxWidth(),
            verticalArrangement = verticalArrangement,
            horizontalAlignment = horizontalAlignment,
            content = content,
        )
    }
}

/** 属性表：若干 [PropertyItem] 的斑马纹两列展示，行尾复制按钮 + 内置 Snackbar。 */
@Composable
fun PropertyTable(
    items: List<PropertyItem>,
    modifier: Modifier = Modifier,
    maxWidth: Dp = PropertyTableMaxWidth,
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    // Snackbar 留在**外层全宽** Box：跟到限宽列里会让「已复制」提示偏到中间去。
    Box(modifier = modifier.fillMaxWidth()) {
        WidthCappedColumn(maxWidth = maxWidth, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEachIndexed { index, item ->
                val zebra = index % 2 == 1
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (zebra) {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            } else {
                                Color.Transparent
                            },
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(KeyWeight).padding(end = 12.dp)) {
                        Text(
                            text = item.key,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            // 长参数名（x-dead-letter-routing-key 一类）最多两行，
                            // 再长省略 —— 行高有界，表格不会因为某个 Key 被顶歪。
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!item.description.isNullOrBlank()) {
                            Text(
                                text = item.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    androidx.compose.foundation.text.selection.SelectionContainer(
                        modifier = Modifier.weight(ValueWeight),
                    ) {
                        Text(
                            text = item.value ?: "-",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (item.copyable && !item.value.isNullOrBlank()) {
                        IconButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(item.value.orEmpty()))
                                scope.launch {
                                    snackbarHostState.showSnackbar(stringResource("msg_detail_copied"))
                                }
                            },
                            modifier = Modifier.size(CopySlotWidth),
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = item.key,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    } else {
                        // 与复制按钮同宽的占位：没有按钮的行不能让 24dp 退还给两列 ——
                        // 否则分栏线逐行错开 0.35x24 ≈ 8dp，斑马纹里一眼就看得出不齐。
                        Spacer(Modifier.width(CopySlotWidth))
                    }
                }
            }
        }
        ThemedSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * 分组便捷封装：小标题 + [PropertyTable]（属性对话框「基本信息 / 统计指标」这类
 * 分节场景用）。
 *
 * 标题与表格**放在同一个限宽列里**：只夹表格会让标题贴最左、表格居中缩进几百 dp，
 * 比不限宽更难看。内层 [PropertyTable] 因此传 `Dp.Infinity`（已经夹过，不再二次居中）。
 */
@Composable
fun PropertyTableSection(
    title: String,
    items: List<PropertyItem>,
    modifier: Modifier = Modifier,
    maxWidth: Dp = PropertyTableMaxWidth,
) {
    WidthCappedColumn(modifier = modifier, maxWidth = maxWidth) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        PropertyTable(items = items, maxWidth = Dp.Infinity)
    }
}
