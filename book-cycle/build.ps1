param(
    [string]$Prism = (Join-Path $env:APPDATA 'PrismLauncher'),
    [string]$Instance = '1.21.11',
    [string]$Destination = (Join-Path $PSScriptRoot 'build/book-cycle-1.7.3+26.3.jar'),
    [switch]$Check
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true

$libraries = Join-Path $Prism 'libraries'
$minecraft = Join-Path $libraries 'com/mojang/minecraft/26.3/minecraft-26.3-client.jar'
$loader = Join-Path $libraries 'net/fabricmc/fabric-loader/0.20.1/fabric-loader-0.20.1.jar'
$fabricApi = Join-Path $Prism "instances/$Instance/minecraft/mods/fabric-api-0.165.2-26.3.jar"
$build = (New-Item -ItemType Directory -Force (Join-Path $PSScriptRoot 'build')).FullName
$classes = (New-Item -ItemType Directory -Force (Join-Path $build 'classes')).FullName

$modulePaths = jar tf $fabricApi | Where-Object {
    $_ -match '^META-INF/jars/fabric-(api-base|key-mapping-api-v1|lifecycle-events-v1|screen-api-v1)-.*\.jar$'
}
if (@($modulePaths).Count -ne 4) { throw 'Expected Fabric API modules were not found.' }
Push-Location -LiteralPath $build
try { jar xf $fabricApi @modulePaths } finally { Pop-Location }

$coordinates = foreach ($metadata in @('net.minecraft/26.3', 'org.lwjgl3/3.4.3')) {
    (Get-Content -Raw -LiteralPath (Join-Path $Prism "meta/$metadata.json") | ConvertFrom-Json).libraries
}
$dependencyJars = foreach ($library in $coordinates) {
    $relativePath = $library.downloads.artifact.path
    if (!$relativePath -and $library.downloads.artifact.url) {
        $relativePath = ([Uri]$library.downloads.artifact.url).AbsolutePath.TrimStart('/')
    }
    if ($relativePath) {
        $path = Join-Path $libraries $relativePath
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            $path
        } elseif ($library.name -match '^([^:]+):([^:]+-natives-windows):([^:]+)$') {
            $group, $name, $version = $Matches[1], $Matches[2], $Matches[3]
            $native = Join-Path $libraries "$($group.Replace('.', '/'))/$name/$version/$name-$version.jar"
            if (Test-Path -LiteralPath $native -PathType Leaf) { $native }
        }
    }
}
$apiJars = $modulePaths | ForEach-Object { Join-Path $build $_ }
$mixin = Join-Path $libraries 'net/fabricmc/sponge-mixin/0.17.5+mixin.0.8.7/sponge-mixin-0.17.5+mixin.0.8.7.jar'
$classpath = (@($minecraft, $loader, $mixin) + $apiJars + $dependencyJars) -join [IO.Path]::PathSeparator
$sources = (Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src/main/java/bookcycle') -Recurse -Filter '*.java').FullName
javac -proc:none --release 25 -encoding UTF-8 -cp $classpath -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed.' }

jar --create --file $Destination -C $classes . -C (Join-Path $PSScriptRoot 'src/main/resources') .
if ($LASTEXITCODE -ne 0) { throw 'JAR creation failed.' }

if ($Check) {
    $tests = (New-Item -ItemType Directory -Force (Join-Path $build 'tests')).FullName
    javac -proc:none --release 25 -encoding UTF-8 -cp "$classes;$classpath" -d $tests (Join-Path $PSScriptRoot 'src/test/java/bookcycle/BookCycleCheck.java')
    if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed.' }
    $testResources = (New-Item -ItemType Directory -Force (Join-Path $build 'test-resources')).FullName
    '{"schemaVersion":1,"id":"book_cycle_check","version":"1.0.0","environment":"client","entrypoints":{"client":["bookcycle.BookCycleCheck"]},"depends":{"book_cycle":"*"}}' |
        Set-Content -LiteralPath (Join-Path $testResources 'fabric.mod.json') -Encoding ascii

    $game = (New-Item -ItemType Directory -Force (Join-Path $build "test-$([DateTime]::UtcNow.ToString('yyyyMMddHHmmss'))")).FullName
    $mods = (New-Item -ItemType Directory -Force (Join-Path $game 'mods')).FullName
    jar --create --file (Join-Path $mods 'book-cycle-check.jar') -C $tests . -C $testResources .
    Copy-Item -LiteralPath $Destination, $fabricApi -Destination $mods
    $mouseTweaks = Join-Path $Prism "instances/$Instance/minecraft/mods/MouseTweaks-fabric-mc26.3-2.31.jar"
    if (Test-Path -LiteralPath $mouseTweaks) { Copy-Item -LiteralPath $mouseTweaks -Destination $mods }
    'pauseOnLostFocus:false', 'renderDistance:4', 'simulationDistance:5', 'enableVsync:false', 'maxFps:30', 'soundCategory_master:0.0' |
        Set-Content -LiteralPath (Join-Path $game 'options.txt') -Encoding ascii

    $loaderDependencies = (Get-Content -Raw -LiteralPath (Join-Path $Prism 'meta/net.fabricmc.fabric-loader/0.20.1.json') | ConvertFrom-Json).libraries
    $loaderJars = foreach ($library in $loaderDependencies) {
        $group, $name, $version = $library.name -split ':'
        Join-Path $libraries "$($group.Replace('.', '/'))/$name/$version/$name-$version.jar"
    }
    $runClasspath = (@($minecraft) + $loaderJars + $dependencyJars) -join [IO.Path]::PathSeparator
    $assets = Join-Path $Prism 'assets'
    $outputLog = Join-Path $game 'check-output.log'
    $errorLog = Join-Path $game 'check-error.log'
    $client = Start-Process -FilePath (Get-Command java.exe).Source -WindowStyle Hidden -WorkingDirectory $game -PassThru `
        -RedirectStandardOutput $outputLog -RedirectStandardError $errorLog -ArgumentList @(
            '-ea:bookcycle...', '-Xmx2G', '--enable-native-access=ALL-UNNAMED', '-cp', ('"' + $runClasspath + '"'),
            'net.fabricmc.loader.impl.launch.knot.KnotClient', '--accessToken', '0', '--username', 'BookCycleCheck',
            '--version', '26.3', '--assetIndex', '34', '--assetsDir', ('"' + $assets + '"'),
            '--gameDir', ('"' + $game + '"'), '--width', '854', '--height', '480')
    if (!$client.WaitForExit(190000)) {
        Stop-Process -Id $client.Id
        throw "Test client timed out. See $game"
    }
    Get-Content -LiteralPath $outputLog -Tail 25
    Get-Content -LiteralPath $errorLog -Tail 15
    if (!(Select-String -LiteralPath $outputLog -SimpleMatch -Quiet 'BOOK CYCLE CHECK PASSED')) {
        throw "Book Cycle check failed (exit $($client.ExitCode)). See $game"
    }
}
