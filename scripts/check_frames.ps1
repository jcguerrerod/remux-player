Add-Type -AssemblyName System.Drawing
$img = [System.Drawing.Bitmap]::FromFile('C:\Users\jcgue\.gemini\antigravity\scratch\remux-player-android-tv\app\src\main\assets\previews\raiders\frame_0.jpg')
Write-Host "Width: $($img.Width), Height: $($img.Height)"
$p = $img.GetPixel(100, 100)
Write-Host "Pixel at 100,100: R=$($p.R) G=$($p.G) B=$($p.B)"
$img.Dispose()

$img2 = [System.Drawing.Bitmap]::FromFile('C:\Users\jcgue\.gemini\antigravity\scratch\remux-player-android-tv\app\src\main\assets\previews\raiders\frame_1.jpg')
Write-Host "frame_1 Width: $($img2.Width), Height: $($img2.Height)"
$p2 = $img2.GetPixel(100, 100)
Write-Host "frame_1 Pixel: R=$($p2.R) G=$($p2.G) B=$($p2.B)"
$img2.Dispose()
