Add-Type -AssemblyName System.Drawing
$assets = "C:\Users\jcgue\.gemini\antigravity\scratch\remux-player-android-tv\app\src\main\assets"

$movies = @('raiders', 'nightmare', 'apocalypse', 'whiplash', 'hailmary')

foreach ($m in $movies) {
    $src = Join-Path $assets "backdrops\${m}_card.jpg"
    if (-not (Test-Path $src)) {
        $src = Join-Path $assets "backdrops\${m}.jpg"
    }
    $destDir = Join-Path $assets "previews\$m"
    if (-not (Test-Path $destDir)) {
        New-Item -ItemType Directory -Path $destDir -Force | Out-Null
    }
    $orig = [System.Drawing.Image]::FromFile($src)
    
    # Generate 50 frames per movie
    for ($i = 0; $i -lt 50; $i++) {
        $bmp = New-Object System.Drawing.Bitmap 320, 180
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $g.DrawImage($orig, 0, 0, 320, 180)
        $g.Dispose()
        $outPath = Join-Path $destDir "frame_$i.jpg"
        $bmp.Save($outPath, [System.Drawing.Imaging.ImageFormat]::Jpeg)
        $bmp.Dispose()
    }
    $orig.Dispose()
    Write-Host "Generated 50 rich frames for $m"
}
