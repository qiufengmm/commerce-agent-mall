#Requires -Version 5.1
<#
    Mall 本地 Docker Compose 启动前环境校验脚本（PowerShell）

    用法：
      powershell -ExecutionPolicy Bypass -File document\docker\check-env.ps1
      powershell -ExecutionPolicy Bypass -File document\docker\check-env.ps1 -EnvFile .env

    退出码：
      0  校验通过
      1  存在未通过的校验项（占位凭据 / 缺失 / 长度不足 / 重复定义）
      2  找不到 .env 文件

    安全约定：
      本脚本只输出变量名与失败原因，绝不输出任何变量值，日志可安全粘贴到聊天或报告。
#>
[CmdletBinding()]
param(
    [string] $EnvFile = ''
)

$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------------------
# 定位仓库根目录：本脚本位于 <repo>/document/docker/
# ---------------------------------------------------------------------------
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot  = Split-Path -Parent (Split-Path -Parent $scriptDir)

if ([string]::IsNullOrWhiteSpace($EnvFile)) {
    $EnvFile = Join-Path $repoRoot '.env'
}
elseif (-not [System.IO.Path]::IsPathRooted($EnvFile)) {
    $EnvFile = Join-Path (Get-Location).Path $EnvFile
}

Write-Host '=== Mall 本地环境校验（check-env.ps1）==='
Write-Host ('env 文件: ' + $EnvFile)
Write-Host '安全约定：只输出变量名与失败原因，不输出任何变量值。'
Write-Host ''

if (-not (Test-Path -LiteralPath $EnvFile)) {
    Write-Host '  [FAIL] 找不到 .env 文件。请先执行： Copy-Item .env.example .env'
    Write-Host ''
    Write-Host '校验未通过：缺少 .env 文件。'
    exit 2
}

# ---------------------------------------------------------------------------
# 重复定义的变量名集合
# Compose 对重复变量使用「最后一个值」，而只取第一个值的解析方式可能得到不同结果，
# 因此对必检变量出现重复定义时直接判定为失败。
# ---------------------------------------------------------------------------
$script:duplicateKeys = New-Object 'System.Collections.Generic.HashSet[string]'

# ---------------------------------------------------------------------------
# 解析 .env（不支持多行值，本项目无需）
# ---------------------------------------------------------------------------
function Read-DotEnv {
    param([string] $Path)

    $map = @{}
    foreach ($raw in [System.IO.File]::ReadAllLines($Path)) {
        $line = $raw.Trim()
        if ($line.Length -eq 0 -or $line.StartsWith('#')) { continue }

        $idx = $line.IndexOf('=')
        if ($idx -lt 1) { continue }

        $key = $line.Substring(0, $idx).Trim()
        $val = $line.Substring($idx + 1).Trim()

        # 去掉成对的单引号 / 双引号
        if ($val.Length -ge 2) {
            $first = $val.Substring(0, 1)
            $last  = $val.Substring($val.Length - 1, 1)
            if (($first -eq '"' -and $last -eq '"') -or ($first -eq "'" -and $last -eq "'")) {
                $val = $val.Substring(1, $val.Length - 2)
            }
        }

        if ($map.ContainsKey($key)) {
            [void] $script:duplicateKeys.Add($key)
        }
        else {
            $map[$key] = $val
        }
    }
    return $map
}

$envMap = Read-DotEnv -Path $EnvFile

# ---------------------------------------------------------------------------
# 占位值特征：命中任意一个即视为「复制后未修改」
#   - 尖括号包裹的说明文字，如 <请填写本地 MySQL root 密码>
#   - 约定的示例 / 待替换关键字
# ---------------------------------------------------------------------------
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

# ---------------------------------------------------------------------------
# 必检变量：MinLength 为 0 表示不校验长度
#   MINIO_ROOT_PASSWORD >= 8 是 MinIO 服务端强制要求
#   MALL_SEARCH_INTERNAL_TOKEN >= 16 避免弱令牌
# ---------------------------------------------------------------------------
$requiredVars = @(
    @{ Name = 'MYSQL_ROOT_PASSWORD';        MinLength = 0 },
    @{ Name = 'RABBITMQ_PASSWORD';          MinLength = 0 },
    @{ Name = 'MINIO_ROOT_USER';            MinLength = 3 },
    @{ Name = 'MINIO_ROOT_PASSWORD';        MinLength = 8 },
    @{ Name = 'MALL_SEARCH_INTERNAL_TOKEN'; MinLength = 16 }
)

$failures = New-Object System.Collections.Generic.List[string]
$warnings = New-Object System.Collections.Generic.List[string]

Write-Host '--- 敏感变量校验 ---'
foreach ($item in $requiredVars) {
    $name  = $item.Name
    $minLen = $item.MinLength

    if ($script:duplicateKeys.Contains($name)) {
        Write-Host ("  [FAIL] {0}: .env 中存在重复定义（Compose 取最后一个值，可能与首个值不一致）" -f $name)
        $failures.Add(("{0}: 重复定义" -f $name))
        continue
    }

    if (-not $envMap.ContainsKey($name)) {
        Write-Host ("  [FAIL] {0}: .env 中缺少该变量" -f $name)
        $failures.Add(("{0}: 缺失" -f $name))
        continue
    }

    $value = [string] $envMap[$name]

    if ([string]::IsNullOrWhiteSpace($value)) {
        Write-Host ("  [FAIL] {0}: 值为空" -f $name)
        $failures.Add(("{0}: 空值" -f $name))
        continue
    }

    if (Test-Placeholder -Value $value) {
        Write-Host ("  [FAIL] {0}: 仍是占位值，请替换为自己的本地凭据" -f $name)
        $failures.Add(("{0}: 未替换占位值" -f $name))
        continue
    }

    if ($minLen -gt 0 -and $value.Length -lt $minLen) {
        Write-Host ("  [FAIL] {0}: 长度不足（要求 >= {1} 个字符）" -f $name, $minLen)
        $failures.Add(("{0}: 长度不足" -f $name))
        continue
    }

    Write-Host ("  [ OK ] {0}: 已设置且非占位值" -f $name)
}

# ---------------------------------------------------------------------------
# 网络暴露检查（仅提示，不阻断）
# ---------------------------------------------------------------------------
Write-Host ''
Write-Host '--- 网络暴露检查 ---'

$exposed = $false
foreach ($bindVar in @('MINIO_BIND_ADDR', 'NGINX_BIND_ADDR')) {
    if ($envMap.ContainsKey($bindVar) -and $envMap[$bindVar] -eq '0.0.0.0') {
        Write-Host ("  [WARN] {0}=0.0.0.0：该服务将暴露到局域网，请确认所处网络可信。" -f $bindVar)
        $warnings.Add(("{0}=0.0.0.0" -f $bindVar))
        $exposed = $true
    }
}
if (-not $exposed) {
    Write-Host '  [ OK ] MINIO_BIND_ADDR / NGINX_BIND_ADDR 未设置为 0.0.0.0'
}
Write-Host '  [INFO] Redis、MongoDB、Elasticsearch 本地配置无认证，Compose 已硬编码绑定 127.0.0.1，'
Write-Host '         且未提供任何把它们整体暴露到 0.0.0.0 的开关，请勿手工修改端口映射。'

# ---------------------------------------------------------------------------
# 结果
# ---------------------------------------------------------------------------
Write-Host ''
if ($failures.Count -gt 0) {
    Write-Host ('校验未通过：{0} 项。' -f $failures.Count)
    Write-Host '请修改 .env 中对应变量后重试；不要把填写后的 .env 提交到仓库。'
    Write-Host '注意：docker compose config --quiet 只校验 YAML 与变量渲染，不能代替本脚本。'
    exit 1
}

Write-Host ('校验通过：{0} 个敏感变量均已替换；警告 {1} 项。' -f $requiredVars.Count, $warnings.Count)
Write-Host '下一步可执行： docker compose --env-file .env config --quiet'
exit 0
