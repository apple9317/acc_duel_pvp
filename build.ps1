param([string]$Out = "build-out")

$ErrorActionPreference = "Stop"
$m2 = "$env:USERPROFILE\.m2\repository"

function J($p) { Join-Path $m2 $p }

$cp = @(
  (J "io\papermc\paper\paper-api\1.21.1-R0.1-SNAPSHOT\paper-api-1.21.1-R0.1-SNAPSHOT.jar"),
  (J "com\google\code\gson\gson\2.10.1\gson-2.10.1.jar"),
  (J "net\kyori\adventure-api\4.17.0\adventure-api-4.17.0.jar"),
  (J "net\kyori\adventure-key\4.17.0\adventure-key-4.17.0.jar"),
  (J "net\kyori\adventure-text-minimessage\4.17.0\adventure-text-minimessage-4.17.0.jar"),
  (J "net\kyori\adventure-text-serializer-legacy\4.17.0\adventure-text-serializer-legacy-4.17.0.jar"),
  (J "net\kyori\adventure-text-serializer-plain\4.17.0\adventure-text-serializer-plain-4.17.0.jar"),
  (J "net\kyori\examination-api\1.3.0\examination-api-1.3.0.jar"),
  (J "net\kyori\examination-string\1.3.0\examination-string-1.3.0.jar"),
  (J "net\md-5\bungeecord-chat\1.21-R0.4\bungeecord-chat-1.21-R0.4.jar"),
  (J "org\slf4j\slf4j-api\2.0.17\slf4j-api-2.0.17.jar")
)

$missing = $cp | Where-Object { -not (Test-Path $_) }
if ($missing) { Write-Output "MISSING JARS:"; $missing; exit 2 }

$cpStr = ($cp -join ";")
$srcRoot = Join-Path $PSScriptRoot "src\main\java"
$outDir = Join-Path $PSScriptRoot $Out
if (Test-Path $outDir) { Remove-Item -Recurse -Force $outDir }
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$srcFile = Join-Path $PSScriptRoot "sources.txt"
Get-ChildItem -Path $srcRoot -Filter "*.java" -Recurse | ForEach-Object { $_.FullName } | Set-Content -Path $srcFile -Encoding ascii

& javac -encoding UTF-8 --release 21 -nowarn -cp $cpStr -d $outDir "@$srcFile"
$code = $LASTEXITCODE
Remove-Item $srcFile -ErrorAction SilentlyContinue
Write-Output "JAVAC_EXIT=$code"
exit $code
