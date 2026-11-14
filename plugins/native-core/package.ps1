$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Push-Location $root
try {
    cargo build --release --manifest-path Cargo.toml
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    $exePath = Join-Path $root "target\release\psd2live-native-core.exe"
    $sha = (Get-FileHash -Algorithm SHA256 $exePath).Hash.ToLowerInvariant()

    $manifestObj = Get-Content (Join-Path $root "plugin.manifest.json") -Raw | ConvertFrom-Json
    $manifestObj | Add-Member -NotePropertyName "files" -NotePropertyValue ([PSCustomObject]@{ "psd2live-native-core.exe" = $sha }) -Force
    $manifestJson = $manifestObj | ConvertTo-Json -Depth 20

    $distDir = Join-Path $root "dist"
    New-Item -ItemType Directory -Path $distDir -Force | Out-Null
    $zipPath = Join-Path $distDir "psd2live.native.core-1.0.0.zip"

    $tmp = New-Item -ItemType Directory -Path ([System.IO.Path]::Combine([System.IO.Path]::GetTempPath(), [System.Guid]::NewGuid().ToString()))
    try {
        [System.IO.File]::WriteAllText((Join-Path $tmp.FullName "plugin.json"), $manifestJson, [System.Text.UTF8Encoding]::new($false))
        Copy-Item $exePath (Join-Path $tmp.FullName "psd2live-native-core.exe")
        Compress-Archive -Path (Join-Path $tmp.FullName "*") -DestinationPath $zipPath -Force
    } finally {
        Remove-Item $tmp.FullName -Recurse -Force -ErrorAction SilentlyContinue
    }
    Write-Output "Packaged $zipPath (sha256=$sha)"
} finally {
    Pop-Location
}
