# Removes the MixinExtras copy NeoForge bundles in its dev artifact, index and all.
#
# Deleting the jar entry alone is not enough and fails in a way that points at the wrong thing:
# META-INF/jarjar/metadata.json still lists it, FML reads the stale index, and the *platform jar*
# is reported as "failed to load as a mod file". That is gotcha 2 in STATE, met again from the
# other side.
param([Parameter(Mandatory=$true)][string]$Jar)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$z = [System.IO.Compression.ZipFile]::Open($Jar, 'Update')
try {
  $meta = @($z.Entries | Where-Object { $_.FullName -eq 'META-INF/jarjar/metadata.json' })
  if ($meta.Count -eq 1) {
    $sr = New-Object System.IO.StreamReader($meta[0].Open())
    $json = $sr.ReadToEnd(); $sr.Close()
    $obj = $json | ConvertFrom-Json
    $obj.jars = @($obj.jars | Where-Object { $_.path -notlike '*mixinextras*' })
    $out = $obj | ConvertTo-Json -Depth 20 -Compress
    $meta[0].Delete()
    $e = $z.CreateEntry('META-INF/jarjar/metadata.json')
    $sw = New-Object System.IO.StreamWriter($e.Open())
    $sw.Write($out); $sw.Close()
  }
  @($z.Entries | Where-Object { $_.FullName -like '*jarjar/mixinextras*' }) | ForEach-Object { $_.Delete() }
} finally { $z.Dispose() }
