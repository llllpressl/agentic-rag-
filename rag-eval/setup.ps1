param(
    [string]$Python = 'python',
    [string]$Maven = ''
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot
try {
    if (-not (Test-Path -LiteralPath "$PSScriptRoot/.venv/Scripts/python.exe")) {
        & $Python -m venv "$PSScriptRoot/.venv"
        if ($LASTEXITCODE -ne 0) {
            throw '创建独立 Python 环境失败'
        }
    }
    & "$PSScriptRoot/.venv/Scripts/python.exe" -m pip install --no-cache-dir -r "$PSScriptRoot/requirements.txt"
    if ($LASTEXITCODE -ne 0) {
        throw '安装评测依赖失败'
    }
    if ([string]::IsNullOrWhiteSpace($Maven)) {
        $Maven = Join-Path $repoRoot 'mvnw.cmd'
    }
    # JDK 23+ 显式启用 Lombok；跳过测试源码编译和所有测试执行。
    & $Maven -q -pl rag -am install '-Dmaven.test.skip=true' '-Dmaven.compiler.proc=full'
    if ($LASTEXITCODE -ne 0) {
        throw '编译现有 RAG 依赖失败'
    }
    & $Maven -q -f rag-eval/java/pom.xml package '-Dmaven.test.skip=true' '-Dmaven.compiler.proc=full'
    if ($LASTEXITCODE -ne 0) {
        throw '编译评测执行器失败'
    }
} finally {
    Pop-Location
}
