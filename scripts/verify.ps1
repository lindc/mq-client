# 质量门禁（Windows / PowerShell 版）—— 与 scripts/verify.sh 跑同一套命令。
#
# 为什么要有两份：CI 在 Linux 上跑 .sh，而开发机是 Windows，没法直接跑 bash 脚本。
# 两份的内容必须一致 —— 改动时请同步。
#
# 用法：
#   .\scripts\verify.ps1              # 全量：编译 + 全部单测 + i18n 检查
#   .\scripts\verify.ps1 -I18nOnly    # 只跑 i18n 检查（改文案时用，几秒钟）

param(
    [switch]$I18nOnly
)

$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')

function Invoke-I18nCheck {
    $python = $null
    foreach ($candidate in @('python', 'python3')) {
        if (Get-Command $candidate -ErrorAction SilentlyContinue) { $python = $candidate; break }
    }
    if (-not $python) {
        throw '找不到 python / python3，无法执行 i18n 检查'
    }
    # 脚本自身 print 中文；不设这个变量在 cp1252 终端会 UnicodeEncodeError。
    $env:PYTHONIOENCODING = 'utf-8'
    foreach ($lang in @('zh-CN', 'en-US')) {
        Write-Host "--- i18n check ($lang) ---"
        & $python scripts/check_i18n_refs.py $lang
        if ($LASTEXITCODE -ne 0) { throw "i18n 检查失败（$lang）" }
    }
}

if ($I18nOnly) {
    Invoke-I18nCheck
    Write-Host 'OK: i18n checks passed'
    exit 0
}

Write-Host '--- assemble + test + jvmTest ---'
# Windows 上必须调 gradlew.bat —— 无扩展名的 `gradlew` 是给 bash 用的，PowerShell 会报
# 「无法将“./gradlew”项识别为 cmdlet」。
$gradlew = if (Test-Path 'gradlew.bat') { '.\gradlew.bat' } else { '.\gradlew' }
& $gradlew assemble test jvmTest --console=plain
if ($LASTEXITCODE -ne 0) { throw '构建或单元测试失败' }

Invoke-I18nCheck

Write-Host 'OK: 全部通过（assemble + test + jvmTest + i18n）'
