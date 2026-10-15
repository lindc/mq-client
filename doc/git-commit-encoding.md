# git commit 中文乱码：现象、根因与修复

## 现象

`git log` 里中文提交说明显示为乱码，例如：

```
815964c feat(kafka): truststore µö»µîü + ΘÜÉσ╝ÅΦ┐₧µÄÑ + Φí¿σìòσ»╣Θ╜É MQ + Consumer Group Θ¥óµ¥┐
```

但用 IDE / TortoiseGit 查看，或 `git show` 在正确解码的终端里看，内容完全正常。

## 根因：存储没坏，是显示层解码错了

**commit 对象里存的是正确的 UTF-8 字节**，可以用 `od` 直接验证（`git log` 加管道后 git 不做编码转换，输出原始字节）：

```
$ git log --oneline -1 | od -c | head -3
0000000   8   1   5   9   6   4   c       f   e   a   t   (   k   a   f
0000020   k   a   )   :       t   r   u   s   t   s   t   o   r   e
0000040 346 224 257 346 214 201 ...
```

`346 224 257`（E6 94 AF）就是「支」的 UTF-8 编码，`346 214 201`（E6 8C 81）是「持」——字节一个不差。

乱码的成因是**显示端拿 OEM 代码页（cp437）逐字节解码了这些 UTF-8 字节**：
`E6 94 AF E6 8C 81` → cp437 → `µ ö » µ î ü`，与看到的 `µö»µîü` 完全吻合。
Windows 控制台默认代码页是 OEM（中文系统 cp936/GBK、英文环境 cp437），而 git 在
**输出被管道/工具捕获**时不做编码转换、直接吐原始字节 —— 谁读谁负责解码，
读的一方按 cp437/GBK 解就花了。

一句话：**写入端（git）和存储（仓库对象）都没问题，坏的只是读的一方的解码假设。**
所以不需要 rebase / amend 修提交内容 —— 修了也是白修，字节本来就是对的。

## 修复（按场景）

### 1. 交互式 Git Bash（mintty）

通常本来就正常。若乱码，显式声明编码 + 让 pager 按 UTF-8 解：

```bash
git config --global i18n.commitEncoding utf-8
git config --global i18n.logEncoding utf-8
echo 'export LESSCHARSET=utf-8' >> ~/.bashrc   # git log 的 less 分页器
```

### 2. cmd / PowerShell 控制台

把代码页切到 UTF-8（只影响当前窗口）：

```
chcp 65001
```

要全局生效可改注册表 `HKCU\Console` 的 CodePage，或直接改用 Windows Terminal
（默认 UTF-8，推荐）。

### 3. 脚本 / 工具管道里读 git 输出

git 被管道捕获时输出原始 UTF-8，**读取方必须按 UTF-8 解码**（Java 里别用平台默认
charset；Python 里 `subprocess` 显式 `encoding='utf-8'`）。CI / 终端集成插件同理。

### 4. 顺手关掉路径转义（同类显示问题）

含中文的文件名在 `git status` 里显示成 `"\346\226\207..."` 八进制转义：

```bash
git config --global core.quotepath false
```

## 验证

修复后在同一环境跑：

```
git log --oneline -1        # 中文正常
git log --oneline -1 | od -c | head -3   # 字节仍是 346 224 257 ...
```

第一条看显示、第二条看存储 —— 两者都对才算真修好；只有第二条对（永远对）说明
你只是把显示层修好了，这也正是本说明要表达的：**这类乱码从来不是数据问题。**
