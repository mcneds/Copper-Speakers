# Draw the speaker directly on a 16x16 grid. Each character is one opaque pixel.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$projectRoot = Split-Path -Parent $PSScriptRoot
$targetPath = Join-Path $projectRoot 'src/main/resources/assets/copper_speakers/textures/block/speaker_front.png'

# Warm note-block wood, copper fasteners, and a charcoal paper cone.
$palette = @{
    'W' = '#945D41' # Wood highlight
    'n' = '#7B5039' # Wood midtone
    'v' = '#5A3C29' # Wood shadow
    'b' = '#412818' # Recessed wood edge
    's' = '#B17A50' # Copper fastener
    '.' = '#211D1A' # Front baffle
    'h' = '#746D5F' # Upper rim highlight
    'r' = '#3B3731' # Rubber surround
    'm' = '#565249' # Paper cone
    'd' = '#403E37' # Inner cone slope
    't' = '#373830' # Dust-cap highlight
    'c' = '#24241F' # Dust-cap shadow
}

# Small upper driver, one clear row of baffle, then an 8x8 lower driver.
# All contours, highlights, and frame details align to the same pixel grid.
$pixels = @(
    'vWWWWWWWWWWWWWWv'
    'Wsbbbbbbbbbbbbsb'
    'Wb.....hh.....vb'
    'Wb....htcr....vb'
    'nb.....rr.....vb'
    'Wb............vb'
    'nb....hhhh....vb'
    'Wb...hmmmmr...vb'
    'Wb..hmdttdmr..vb'
    'nb..hmdccdmr..vb'
    'Wb..rmdccdmr..vb'
    'Wb..rmmddmrr..vb'
    'nb...rmmmrr...vb'
    'Wb....rrrr....vb'
    'nsbbbbbbbbbbbbsb'
    'vbbbbbbbbbbbbbbv'
)

if ($pixels.Count -ne 16 -or @($pixels | Where-Object { $_.Length -ne 16 }).Count -ne 0) {
    throw 'The speaker texture must contain exactly 16 rows of 16 pixels.'
}

$sprite = [System.Drawing.Bitmap]::new(16, 16)
try {
    for ($y = 0; $y -lt 16; $y++) {
        for ($x = 0; $x -lt 16; $x++) {
            $key = [string]$pixels[$y][$x]
            if (-not $palette.ContainsKey($key)) {
                throw "Unknown palette entry '$key' at ($x, $y)."
            }
            $sprite.SetPixel($x, $y, [System.Drawing.ColorTranslator]::FromHtml($palette[$key]))
        }
    }
    $sprite.Save($targetPath, [System.Drawing.Imaging.ImageFormat]::Png)
} finally {
    $sprite.Dispose()
}
