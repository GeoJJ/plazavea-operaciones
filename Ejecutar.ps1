$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if (-not $env:DB_USER) { $env:DB_USER = Read-Host 'Usuario PostgreSQL (Enter para postgres)' }
if (-not $env:DB_PASSWORD) {
    $clave = Read-Host 'Contraseña PostgreSQL' -AsSecureString
    $env:DB_PASSWORD = [System.Net.NetworkCredential]::new('', $clave).Password
}
try {
    if (-not (Test-Path 'target/plazavea-operaciones-1.4.0.jar')) {
        $maven = Get-Command mvn.cmd -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -First 1
        if (-not $maven) {
            $maven = Get-ChildItem 'C:/Program Files/JetBrains' -Filter mvn.cmd -Recurse -ErrorAction SilentlyContinue |
                Select-Object -ExpandProperty FullName -First 1
        }
        if (-not $maven) { throw 'Instala Maven o agrégalo al PATH.' }
        & $maven package
        if ($LASTEXITCODE -ne 0) { throw 'Falló la compilación.' }
    }
    & java -jar 'target/plazavea-operaciones-1.4.0.jar'
} finally {
    Remove-Item Env:DB_PASSWORD -ErrorAction SilentlyContinue
}









