#Requires -Version 5.1
<#
    Mall 商品导购智能体（mall-shopping-agent）启动前环境校验脚本。

    用法：
      powershell -ExecutionPolicy Bypass -File document\docker\check-agent-env.ps1
      powershell -ExecutionPolicy Bypass -File document\docker\check-agent-env.ps1 -EnvFile .env
      powershell -ExecutionPolicy Bypass -File document\docker\check-agent-env.ps1 -EnvFile .env.example

    校验内容：
      1. MALL_AGENT_MODEL_MODE 只能是 openai 或 stub；
      2. openai 模式要求 MALL_AGENT_OPENAI_BASE_URL、MALL_AGENT_OPENAI_MODEL、
         MALL_AGENT_OPENAI_API_KEY 均为非占位值；stub 模式允许 API Key 为空；
      3. MALL_AGENT_OPENAI_BASE_URL 必须是 http(s) 地址并包含 /v1；
      4. MALL_AGENT_PORTAL_BASE_URL、MALL_AGENT_REDIS_URL 协议合法；
      5. AGENT_PORT 为 1..65535 的端口号；MALL_AGENT_LOG_LEVEL 取值合法；
      6. 必检变量出现重复定义时直接判定为失败（拒绝只取最后一个值）。

    退出码：
      0  校验通过
      1  存在未通过的校验项（占位值 / 缺失 / 非法地址 / 非法端口 / 非法模式 / 重复定义）
      2  找不到 env 文件

    安全约定：
      本脚本只输出变量名、固定提示与失败原因，绝不输出任何变量值，
      日志可以安全粘贴到聊天或报告。
#>
[CmdletBinding()]
param(
    [string] $EnvFile = ''
)

$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot  = Split-Path -Parent (Split-Path -Parent $scriptDir)

if ([string]::IsNullOrWhiteSpace($EnvFile)) {
    $EnvFile = Join-Path $repoRoot '.env'
}
elseif (-not [System.IO.Path]::IsPathRooted($EnvFile)) {
    $EnvFile = Join-Path (Get-Location).Path $EnvFile
}

Write-Host '=== 商品导购智能体环境校验（check-agent-env.ps1）==='
Write-Host ('env 文件: ' + $EnvFile)
Write-Host '安全约定：只输出变量名与失败原因，不输出任何变量值。'
Write-Host ''

if (-not (Test-Path -LiteralPath $EnvFile)) {
    Write-Host '  [FAIL] 找不到 env 文件。请先执行： Copy-Item .env.example .env'
    Write-Host ''
    Write-Host '校验未通过：缺少 env 文件。'
    exit 2
}

# ---------------------------------------------------------------------------
# 重复定义的变量名集合
# Compose 对重复变量使用「最后一个值」，本脚本保留「第一个值」，
# 两者可能不一致，因此对必检变量出现重复定义时直接判定为失败，
# 不使用「后值覆盖前值后继续通过」的方式。
# ---------------------------------------------------------------------------
$script:duplicateKeys = New-Object 'System.Collections.Generic.HashSet[string]'

function Read-DotEnv {
    param([string] $Path)

    $map = @{}
    foreach ($raw in [System.IO.File]::ReadAllLines($Path)) {
        $line = $raw.Trim()
        if ($line.Length -eq 0 -or $line.StartsWith('#')) { continue }

        $idx = $line.IndexOf('=')
        if ($idx -lt 1) { continue }

        $key = $line.Substring(0, $idx).Trim()
        $value = $line.Substring($idx + 1).Trim()

        if ($value.Length -ge 2) {
            $first = $value.Substring(0, 1)
            $last  = $value.Substring($value.Length - 1, 1)
            if (($first -eq '"' -and $last -eq '"') -or ($first -eq "'" -and $last -eq "'")) {
                $value = $value.Substring(1, $value.Length - 2)
            }
        }

        # 保留第一个值，并把重复出现的变量名记录下来
        if ($map.ContainsKey($key)) {
            [void] $script:duplicateKeys.Add($key)
        }
        else {
            $map[$key] = $value
        }
    }
    return $map
}

$envMap = Read-DotEnv -Path $EnvFile

$placeholderLiterals = @(
    '<', '>',
    'change-me', 'changeme', 'change_me',
    'please-change', 'pleasechange',
    'example', 'placeholder', 'your-',
    'todo', 'fixme',
    '请填写', '请设置', '请生成', '请替换'
)

function Test-Placeholder {
    param([string] $Value)

    if ([string]::IsNullOrWhiteSpace($Value)) { return $true }

    $lower = $Value.ToLowerInvariant()
    foreach ($token in $placeholderLiterals) {
        if ($lower.Contains($token.ToLowerInvariant())) { return $true }
    }
    return $false
}

function Get-EnvValue {
    param(
        [hashtable] $Map,
        [string] $Name,
        [string] $Default = ''
    )

    if ($Map.ContainsKey($Name)) { return [string] $Map[$Name] }
    return $Default
}

$failures = New-Object System.Collections.Generic.List[string]
$warnings = New-Object System.Collections.Generic.List[string]

# 命中重复定义时记录失败并返回 $true，调用方跳过后续取值校验。
function Test-Duplicate {
    param([string] $Name)

    if ($script:duplicateKeys.Contains($Name)) {
        Write-Host ("  [FAIL] {0}: .env 中存在重复定义（拒绝只取最后一个值）" -f $Name)
        $failures.Add(("{0}: 重复定义" -f $Name))
        return $true
    }
    return $false
}

# ---------------------------------------------------------------------------
# 1. 模型模式
# ---------------------------------------------------------------------------
Write-Host '--- 模型模式 ---'
$modelMode = 'openai'
if (-not (Test-Duplicate -Name 'MALL_AGENT_MODEL_MODE')) {
    $rawMode = (Get-EnvValue -Map $envMap -Name 'MALL_AGENT_MODEL_MODE' -Default 'openai').ToLowerInvariant()
    if ($rawMode -ne 'openai' -and $rawMode -ne 'stub') {
        Write-Host '  [FAIL] MALL_AGENT_MODEL_MODE: 只能是 openai 或 stub'
        $failures.Add('MALL_AGENT_MODEL_MODE: 取值非法')
    }
    else {
        $modelMode = $rawMode
    }
}

if ($modelMode -eq 'stub') {
    Write-Host '  [ OK ] MALL_AGENT_MODEL_MODE: 已设置为 stub（离线演示，允许没有模型 Key）'
    $warnings.Add('当前为 stub 模式，不会调用真实模型')
}
else {
    Write-Host '  [ OK ] MALL_AGENT_MODEL_MODE: 已设置为 openai'
}

# ---------------------------------------------------------------------------
# 2. 模型服务地址与模型名
# ---------------------------------------------------------------------------
Write-Host ''
Write-Host '--- 模型服务配置 ---'

if (-not (Test-Duplicate -Name 'MALL_AGENT_OPENAI_BASE_URL')) {
    $baseUrl = Get-EnvValue -Map $envMap -Name 'MALL_AGENT_OPENAI_BASE_URL'
    if (Test-Placeholder -Value $baseUrl) {
        Write-Host '  [FAIL] MALL_AGENT_OPENAI_BASE_URL: 缺失或仍是占位值'
        $failures.Add('MALL_AGENT_OPENAI_BASE_URL: 缺失或占位值')
    }
    elseif (-not ($baseUrl -match '^https?://')) {
        Write-Host '  [FAIL] MALL_AGENT_OPENAI_BASE_URL: 必须是 http(s) 地址'
        $failures.Add('MALL_AGENT_OPENAI_BASE_URL: 协议非法')
    }
    elseif (-not ($baseUrl.TrimEnd('/') -match '/v1$')) {
        Write-Host '  [FAIL] MALL_AGENT_OPENAI_BASE_URL: 必须以 /v1 结尾（服务端会据此拼接 /chat/completions）'
        $failures.Add('MALL_AGENT_OPENAI_BASE_URL: 缺少 /v1')
    }
    else {
        Write-Host '  [ OK ] MALL_AGENT_OPENAI_BASE_URL: 已设置为 http(s) 且包含 /v1'
    }
}

if (-not (Test-Duplicate -Name 'MALL_AGENT_OPENAI_MODEL')) {
    $modelName = Get-EnvValue -Map $envMap -Name 'MALL_AGENT_OPENAI_MODEL'
    if (Test-Placeholder -Value $modelName) {
        Write-Host '  [FAIL] MALL_AGENT_OPENAI_MODEL: 缺失或仍是占位值'
        $failures.Add('MALL_AGENT_OPENAI_MODEL: 缺失或占位值')
    }
    else {
        Write-Host '  [ OK ] MALL_AGENT_OPENAI_MODEL: 已设置且非占位值'
    }
}

# ---------------------------------------------------------------------------
# 3. 模型 Key（openai 模式必填，stub 模式可空）
# ---------------------------------------------------------------------------
Write-Host ''
Write-Host '--- 模型凭据 ---'

if (-not (Test-Duplicate -Name 'MALL_AGENT_OPENAI_API_KEY')) {
    $apiKey = Get-EnvValue -Map $envMap -Name 'MALL_AGENT_OPENAI_API_KEY'

    if ($modelMode -eq 'stub') {
        if ([string]::IsNullOrWhiteSpace($apiKey)) {
            Write-Host '  [ OK ] MALL_AGENT_OPENAI_API_KEY: stub 模式下允许为空'
        }
        else {
            Write-Host '  [ OK ] MALL_AGENT_OPENAI_API_KEY: 已设置（stub 模式不会使用）'
        }
    }
    elseif ([string]::IsNullOrWhiteSpace($apiKey)) {
        Write-Host '  [FAIL] MALL_AGENT_OPENAI_API_KEY: openai 模式必须提供真实 Key'
        $failures.Add('MALL_AGENT_OPENAI_API_KEY: 缺失')
    }
    elseif (Test-Placeholder -Value $apiKey) {
        Write-Host '  [FAIL] MALL_AGENT_OPENAI_API_KEY: 仍是占位值，openai 模式会拒绝启动聊天能力'
        $failures.Add('MALL_AGENT_OPENAI_API_KEY: 未替换占位值')
    }
    else {
        Write-Host '  [ OK ] MALL_AGENT_OPENAI_API_KEY: 已设置且非占位值'
    }
}

# ---------------------------------------------------------------------------
# 4. 只读依赖地址
# ---------------------------------------------------------------------------
Write-Host ''
Write-Host '--- 只读依赖地址 ---'

if (-not (Test-Duplicate -Name 'MALL_AGENT_PORTAL_BASE_URL')) {
    $portalUrl = Get-EnvValue -Map $envMap -Name 'MALL_AGENT_PORTAL_BASE_URL' -Default 'http://localhost:8085'
    if (-not ($portalUrl -match '^https?://')) {
        Write-Host '  [FAIL] MALL_AGENT_PORTAL_BASE_URL: 必须是 http(s) 地址'
        $failures.Add('MALL_AGENT_PORTAL_BASE_URL: 协议非法')
    }
    else {
        Write-Host '  [ OK ] MALL_AGENT_PORTAL_BASE_URL: 已设置为 http(s) 地址'
    }
}

if (-not (Test-Duplicate -Name 'MALL_AGENT_REDIS_URL')) {
    $redisUrl = Get-EnvValue -Map $envMap -Name 'MALL_AGENT_REDIS_URL' -Default 'redis://localhost:6379/0'
    if (-not ($redisUrl -match '^rediss?://')) {
        Write-Host '  [FAIL] MALL_AGENT_REDIS_URL: 必须是 redis:// 或 rediss:// 地址'
        $failures.Add('MALL_AGENT_REDIS_URL: 协议非法')
    }
    else {
        Write-Host '  [ OK ] MALL_AGENT_REDIS_URL: 已设置为 redis 地址'
    }
}

# ---------------------------------------------------------------------------
# 5. 端口与日志级别
# ---------------------------------------------------------------------------
Write-Host ''
Write-Host '--- 端口与日志 ---'

if (-not (Test-Duplicate -Name 'AGENT_PORT')) {
    $agentPort = Get-EnvValue -Map $envMap -Name 'AGENT_PORT' -Default '8086'
    $portNumber = 0
    if (-not [int]::TryParse($agentPort, [ref] $portNumber) -or $portNumber -lt 1 -or $portNumber -gt 65535) {
        Write-Host '  [FAIL] AGENT_PORT: 必须是 1..65535 的端口号'
        $failures.Add('AGENT_PORT: 取值非法')
    }
    else {
        Write-Host '  [ OK ] AGENT_PORT: 已设置为合法端口'
    }
}

if (-not (Test-Duplicate -Name 'MALL_AGENT_LOG_LEVEL')) {
    $logLevel = (Get-EnvValue -Map $envMap -Name 'MALL_AGENT_LOG_LEVEL' -Default 'INFO').ToUpperInvariant()
    $validLevels = @('CRITICAL', 'ERROR', 'WARNING', 'INFO', 'DEBUG')
    if ($validLevels -notcontains $logLevel) {
        Write-Host '  [FAIL] MALL_AGENT_LOG_LEVEL: 取值不在允许范围内'
        $failures.Add('MALL_AGENT_LOG_LEVEL: 取值非法')
    }
    else {
        Write-Host '  [ OK ] MALL_AGENT_LOG_LEVEL: 取值合法'
    }
}

# ---------------------------------------------------------------------------
# 6. 提示：不要把模型 Key 写进仓库
# ---------------------------------------------------------------------------
Write-Host ''
Write-Host '--- 安全提示 ---'
Write-Host '  [INFO] MALL_AGENT_OPENAI_API_KEY 只应存在于本机 .env 或进程环境变量中。'
Write-Host '  [INFO] 服务日志不会记录 Authorization、API Key、完整提示词或完整模型响应。'
Write-Host '  [INFO] 本服务只读访问 mall-portal，不直连 MySQL / MongoDB / RabbitMQ / Elasticsearch。'

# ---------------------------------------------------------------------------
# 结果
# ---------------------------------------------------------------------------
Write-Host ''
if ($failures.Count -gt 0) {
    Write-Host ('校验未通过：{0} 项。' -f $failures.Count)
    Write-Host '请修改 .env 中对应变量后重试；不要把填写后的 .env 提交到仓库。'
    exit 1
}

Write-Host ('校验通过；警告 {0} 项。' -f $warnings.Count)
Write-Host '下一步可执行： docker compose --env-file .env --profile app config --quiet'
exit 0
