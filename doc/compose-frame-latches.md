# Compose 帧派发与 Tao 闩锁

这篇笔记记录一次真实故障的完整机制：**一次 1 毫秒的渲染等了 51 秒才执行**。
根因不在我们的代码里，而在「Compose 等一帧」与「nucleus/Tao 派帧」之间的一层闩锁。

写下它是因为这个坑有三个特点：**症状像性能问题**（40 秒、60 秒、越来越慢）、
**日志里看不出异常**（后台线程全程健康）、**修法不像修法**（把两个 `LaunchedEffect`
合成一个）。如果只留提交信息，下一个人还得重新推一遍。

文中每条机制都标了来源：**【字节码】**＝ 用 `javap -c` 从 jar 里读出来的；
**【推断】**＝ 没读到实现，只是最可能的解释。请勿把后者当结论引用。

---

## 一、先看结论

在 `mq-client` 里，**「等下一帧」的期望耗时是几十秒，不是 16 毫秒。**

因为它不是定时器打出来的：一帧只有在 native 事件循环派发 `RedrawRequested` 时才会发生。
用户不动鼠标、不按键，就没有帧。任何写成「等一帧之后再做」的代码，都在等一次用户交互。

所以有一条硬规则（第六节详述）：

> **不要写「以状态为 key 的第二个 `LaunchedEffect`」。** 要串在同一个协程里。

---

## 二、一帧是干什么用的

Compose 的「帧」不是刷新率概念，是**事务边界**。一个帧内依次完成：

```
读 MutableState → 重组 Recompose → 测量 Measure → 布局 Layout → 绘制 Draw → 提交
```

它是原子的：这一帧里所有读取看到同一份快照，不会出现「上半屏新值、下半屏旧值」。
`withFrameNanos` 就是这条边界的时钟。**【字节码】**确认的帧体是三步：

```java
// androidx.compose.ui.scene.SingleComposeSceneRenderingScope.render(...)
frameRecomposer.performFrame(ns);      // 重组事务 + BroadcastFrameClock.sendFrame(ns)
scene.measureAndLayout();              // 测量 + 布局
scene.draw(canvas);                    // 绘制
if (hasInvalidations(scene)) scheduleFrame.invoke();   // 还有失效 → 再要一帧
```

`frameRecomposer.performFrame(ns)` 内部先 `isInFrame = true`，再
`performFrameDispatch()`，然后 `BroadcastFrameClock.sendFrame(ns)` —— **所有挂起在
`withFrameNanos` 上的协程在这一行被恢复**。这就是「等一帧」的物理位置。

推论：凡语义挂在帧上的东西都会一起卡住 —— 动画、`delay`、以及
「`LaunchedEffect` 的 key 变了要重启」。

---

## 三、这一帧由谁来派：三层闩锁

帧请求要走三层，每层都是「合并请求」的闩锁。任一层没被打开，整条链静默停住。

### 第 1 层：`FrameRecomposer.onNewAwaiters`

**【字节码】** `androidx.compose.ui.platform.FrameRecomposer`（`ui-desktop-1.12.0`）：

```java
// FrameRecomposer$frameClock$1.invoke() → FrameRecomposer.access$onNewAwaiters
private final void onNewAwaiters() {
    if (isInFrame) return;
    invalidate.invoke();          // ← 有新的帧等待者，立刻请求一帧
}
```

`invalidate` 是构造 `FrameRecomposer` 时传进来的回调。**【字节码】**
`TaoSceneBundleKt.canvasLayersSceneBundle / platformLayersSceneBundle` 里：

```java
new FrameRecomposer(coroutineContext, () -> requestFrame.invoke())
```

而 `requestFrame` 是 host 传进去的 lambda —— 最终是 `TaoWindow.requestRedraw()`。
同一个 `requestFrame` 还喂给 `SingleComposeSceneRenderingScope`（就是上面
`render()` 末尾那个 `scheduleFrame`）。

**这一层没问题**：只要有等待者，请求确实发出来了。坑在下一层。

### 第 2 层：`TaoWindow.requestRedraw` 的 `redrawPending` 闩锁

**【字节码】** `dev.nucleusframework.window.tao.TaoWindow`：

```java
public final void requestRedraw() {
    if (!redrawPending.compareAndSet(false, true)) return;   // ← 闩锁：只放行一次
    NativeTaoBridge.nativeRequestRedraw(handle);
}
```

`redrawPending` 是 `AtomicBoolean`。**只有 `false → true` 那一次真的会通知 native**，
之后所有请求被**静默丢弃**（`return`，不排队、不报错、不留日志）。

只有一个方法能把它放回来：

```java
public final void resetRedrawLatch$Nucleus_decorated_window_tao() {
    redrawPending.set(false);
    requestRedraw();            // 立即补一次
}
```

它的**唯一调用点**是出站拖拽的收尾：**【字节码】** 只在
`TaoComposeSceneHostWindows.runWindowsOutboundDrag$lambda$0` 里（`nativeStartDrag` 之后）。
也就是说：这是个窄口子的应急阀，不是常规复位路径。

### 第 3 层：`TaoMainDispatcher` 的队列 + `wakePending`

**【字节码】** `dev.nucleusframework.window.tao.dispatch.TaoMainDispatcher`：

```java
public void dispatch(CoroutineContext ctx, Runnable block) {
    pending.offer(block);                                  // 先进队列
    if (!loopRunning) { scheduleFallbackDrain(); return; } // native 环没跑 → 兜底线程
    if (!NativeTaoBridge.isLoaded()) return;
    if (wakePending.compareAndSet(false, true))
        NativeTaoBridge.nativeWake();                      // ← 同样是「只唤醒一次」
}
```

任务不会丢（留在 `pending` 里），但要等 native 主循环调 `pump()` 才被消费。
`pump()` 的预算是 `MAX_PUMP_PASSES = 64` 趟 / `PUMP_TIME_BUDGET_NS = 8ms`，用完就
重新挂 `pumpScheduler` 再等下一轮。**【字节码】**：`pump()` 里
`ldc2_w 8000000l`（8ms）与 `bipush 64`。

---

## 四、复位路径：`nativeRequestRedraw` 之后到底发生什么

这是整条链上**唯一**能把闩锁解开的路径。**【字节码】** 逐跳确认：

```
① requestRedraw()                       TaoWindow
   └─ redrawPending CAS false→true
   └─ NativeTaoBridge.nativeRequestRedraw(handle)
        ↓  （native：Tao / Rust，本仓库无源码）
② native 事件循环派发 RedrawRequested
        ↓
③ TaoWindow 事件处理
   └─ redrawPending.set(false)          ← 闩锁在这里复位，且只在这里
   └─ redrawListener.invoke()
        ↓
④ TaoComposeSceneHostWindows.onRedrawRequested()
        ↓
⑤ TaoSceneBundle.render(canvas, ns)
        ↓
⑥ SingleComposeSceneRenderingScope.render(scene, frameRecomposer, canvas, ns)
   └─ frameRecomposer.performFrame(ns)          ← BroadcastFrameClock.sendFrame(ns)
   └─ scene.measureAndLayout()                  ← 等帧的协程在这一段恢复
   └─ scene.draw(canvas)
   └─ if (hasInvalidations) scheduleFrame()     ← 还有失效就再要一帧
        ↓
⑦ 协程从 withFrameNanos 返回，effect 体继续跑
```

`redrawListener` 的注册也是**【字节码】**确认的：
`DecoratedWindowKt.openDecoratedWindowWindows$lambda$20` →
`TaoComposeSceneHostWindows.onRedrawRequested()`，通过
`TaoWindow.onRedrawRequested(Function0)` 塞进 `redrawListener` 字段。

**关键点：闩锁的复位完全依赖 ②，而 ② 由 native 事件循环决定。**
如果 native 没派这个事件，闩锁就永远停在 `true`：
- 后续帧请求全部被丢弃（第 2 层）；
- 已经排队的 effect 重启也等不到帧（第 1 层的 `invalidate` 走不通）；
- `dispose`、后续动画、`delay` 全部一起卡住。

界面既不更新，也不报错。它只会等**下一次真实事件**（用户动鼠标 / 按键 / 缩放窗口）
把整条链重新走通 —— 这也解释了为什么「点一下就好了」。

---

## 五、真实代价：等一帧 = 等一次用户交互

故障现场是 RabbitMQ 消息详情弹窗。`[ui.detail]` 埋点（见第七节）给出的原始数据：

| 阶段 | 耗时 |
| --- | --- |
| `clicked → composed` | 2 ~ 12 ms |
| `composed → preview ready`（解码） | 3 ms |
| `preview ready → rendered`（**空档**） | **51,020 / 4,104 / 1,937 / 23,737 / 5,622 ms** |
| `rendered` 里真正的渲染 | 1 / 0 / 2 / 0 / 1 ms |

如果帧是定时来的，这些空档应该接近 16 / 33 / 50 ms 的倍数。它们不是 ——
每次都在等不同长度的「用户没碰鼠标的时间」。

### 当时的代码

```kotlin
// ✗ 第一个 effect 产出状态，第二个 effect 拿它当 key
LaunchedEffect(effectiveBody, binary, codedCharSetId) {
    preview = decode(effectiveBody)          // 3ms 跑完
}
LaunchedEffect(effectiveBody, interpreter, params, preview) {   // ← preview 是 key
    rendered = interpreter.interpret(...)    // 1ms，但要等 51 秒才启动
}
```

`preview` 一写，第二个 effect 的 key 就变了。Compose 要让 effect 重启，必须先有一次
重组 —— 而重组要求一帧。于是 1ms 的活欠了一帧的债，而这一帧的期望时长是几十秒。

### 修法

```kotlin
// ✓ 串在同一个协程里：一次调用跑完解码与渲染
LaunchedEffect(effectiveBody, binary, codedCharSetId, interpreter, params) {
    val content = buildDetailContent(...)    // 解码 + 渲染
    preview = content.preview
    content.rendered?.let { rendered = it }
}
```

真机实测（12 次连续打开）：`composed → loaded` 为
`166 / 140 / 143 / 124 / 123 / 127 / 144 / 150 / 142 / 129 / 125 / 148 ms`，
无递增。剩下的 ~130ms 是窗口创建 + 布局 + 绘制的真实成本，是固定下限，不是债务。

---

## 六、编码规则

### 6.1 不要写「以状态为 key 的第二个 effect」

```kotlin
// ✗ 每次都要多等一帧
LaunchedEffect(input) { stage1 = computeA(input) }
LaunchedEffect(stage1) { stage2 = computeB(stage1) }

// ✓ 串起来
LaunchedEffect(input) {
    val a = computeA(input)
    stage1 = a
    stage2 = computeB(a)
}
```

代价对比很不对称：合并只让每次多算一次（十几毫秒），拆分却欠一帧（这个应用里是几十秒）。

### 6.2 同一个模式的其他写法

以下都是「欠一帧」的等价形态，审查时一起看：

```kotlin
// ✗ derivedStateOf 之后再起 effect
val derived by remember { derivedStateOf { f(x) } }
LaunchedEffect(derived) { doWork(derived) }

// ✗ snapshotFlow 的输出写进状态，再由另一个 effect 接走
LaunchedEffect(Unit) { snapshotFlow { x }.collect { stage = it } }
LaunchedEffect(stage) { heavy(stage) }

// ✗ 用 SideEffect / DisposableEffect 写状态，再用那个状态起 effect
SideEffect { stage = compute() }
LaunchedEffect(stage) { ... }

// ✓ snapshotFlow 直接在同一个协程里消费 —— 它自己等状态变化，不依赖帧重启
LaunchedEffect(Unit) { snapshotFlow { x }.collect { heavy(it) } }
```

区分点很细：`snapshotFlow` **本身**不欠帧（在协程里等状态变化）。它只在
「输出又被写回状态、由另一个 effect 接走」时才变成欠帧链的一环。最后那个 ✓ 与
倒数第三个 ✗ 只差一次状态中转，代价差一帧。

### 6.3 「等一帧」的合法场景

帧是事务边界，所以**一次** `awaitFrame` / `withFrameNanos` 是合理的 —— 例如
「先让布局完成，再测量尺寸」。不合理的是一次状态传递就要多等**额外**的一帧。
判断方法：数一下从用户操作到结果，中间串了几次「状态写入 → 另一个 effect 读到」。

### 6.4 也要注意自动推进的 UI

`6.1` 的反面同样成立：**任何不由用户点击驱动的 UI 更新**（后台扫描完成刷新列表、
AI 流式输出逐字追加、定时器驱动的进度）都可能撞上同一个闩锁。它们目前看起来正常，
多半是因为驱动方本身就在 native 事件回调里跑。新增这类功能时优先用「一个协程内
串行」，不要靠 effect 之间的状态接力。

---

## 七、怎么诊断

### 7.1 埋点（已常驻）

`ui-common` 的 `MessageDetailDialog` 与 `rabbitmq-ui` 的两处埋点，tag 为 `ui.detail`：

```
detail clicked:   deliveryTag=… bodyBytes=… windows=…
detail composed:  bodyBytes=… sinceCompose=…ms windows=…
detail loaded:    interpreter=… bytes=… binary=… rendered=… total=…ms
detail disposed:  lifetime=…ms windows=…
```

判读方法：**相邻两行的时间戳差**才是真耗时；行内自报的 `elapsed` / `total` 只统计
自己那段。两者差得越远，说明在等的不是算力，是调度。

### 7.2 区分「算得慢」还是「没被调度」

| 现象 | 含义 |
| --- | --- |
| 行内 `elapsed` 与时间戳差接近 | 真的在算 |
| 行内 `elapsed` 很小、时间戳差巨大 | 在等帧 / 等锁 / 等调度 |
| 后台线程日志照常刷（如 `status scanned` 每 10s） | 进程健康，问题只在 Composition 线程 |
| 数字不均匀、像随机 | 在等用户交互（帧），不是固定轮询 |

### 7.3 无头测试能测什么、不能测什么

`ObjectDetailDialog` 用 nucleus 的 `DecoratedDialog` 建真实窗口，无头测试里构造不出来。
所以详情这条链路把可测的部分抽成了 `internal suspend fun buildDetailContent(...)`
（`ui-common`），由 `MessageDetailPipelineTest` 直接打它 —— 能证明「一次调用同时给出
基线与渲染」，**测不出**帧派发行为。涉及窗口/帧的问题只能真机验证。

---

## 八、已知未确认

诚实记录边界，避免下一个人把推断当结论：

- **native 为什么有时不派 `RedrawRequested` —— 未确认。** 第 ② 步在 Tao（Rust）里，
  本仓库无源码。可能的解释（窗口被遮挡时 Windows 不合成 `WM_PAINT`、事件循环的
  `Wait` 策略、重绘被 inner-loop 合并）都是**推断**。能确认的只有：闩锁确实存在，
  复位只依赖这个事件。
- **只在 Windows + Tao 后端实测。** macOS / Linux 的帧派发行为未验证。若那边不依赖
  原生事件派帧，合并 effect 的修法只是「少等一帧」，不会变差但收益更小。
- **未穷举其它欠帧写法。** 本仓库只改了详情这一条链路；第六节列的是代码审查口径，
  不是已完成的清单。
- **未给 upstream 提 issue。** 彻底的解法要动 nucleus：给 `requestRedraw` 一条不依赖
  `WM_PAINT` 合成的唤醒路径（例如 `PostMessage` 自定义消息，或「有 pending frame 就
  定时 pump」的保险）。那是外部仓库，需要单独决策。

---

## 九、参考：涉及的类型

复现本文结论时按这个清单 `javap -p -c`：

| 类型 | 所在 jar | 看什么 |
| --- | --- | --- |
| `androidx.compose.ui.platform.FrameRecomposer` | `ui-desktop-1.12.0.jar` | `onNewAwaiters` / `performFrame` |
| `androidx.compose.ui.scene.SingleComposeSceneRenderingScope` | `ui-desktop-1.12.0.jar` | `render`：帧体三步 + `scheduleFrame` |
| `dev.nucleusframework.window.tao.TaoWindow` | `nucleus.decorated-window-tao-2.5.2.jar` | `requestRedraw` / `resetRedrawLatch$…` / `redrawListener` |
| `dev.nucleusframework.window.tao.scene.TaoComposeSceneHostWindows` | 同上 | `onRedrawRequested` / `attach` 里的 `requestFrame` lambda |
| `dev.nucleusframework.window.tao.scene.TaoSceneBundle` | 同上 | `render`：转发给 rendering scope |
| `dev.nucleusframework.window.tao.scene.TaoSceneBundleKt` | 同上 | `FrameRecomposer(ctx, requestFrame)` 的构造 |
| `dev.nucleusframework.window.tao.DecoratedWindowKt` | 同上 | `openDecoratedWindowWindows$lambda$20` 注册 `redrawListener` |
| `dev.nucleusframework.window.tao.dispatch.TaoMainDispatcher` | 同上 | `dispatch` 的 `wakePending` / `pump` 预算 |

事故与修复的完整上下文见 CHANGELOG 中 `[Unreleased]` 那条
「消息详情要 40 秒」。
