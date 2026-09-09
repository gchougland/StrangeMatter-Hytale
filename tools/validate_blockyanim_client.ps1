param([string]$RegressionSnapshot)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

# The client log names System.Text.Json's Int32 conversion. Use that same
# conversion here: Python accepts 0.0 as a number, but GetInt32 rejects it.
if (-not ('StrangeMatterBlockAnimationChecks' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Text.Json;

public static class StrangeMatterBlockAnimationChecks {
    public static int Validate(string json, string name) {
        using (var document = JsonDocument.Parse(json)) {
            var root = document.RootElement;
            int version = root.GetProperty("formatVersion").GetInt32();
            int duration = root.GetProperty("duration").GetInt32();
            if (version != 1 || duration < 0) throw new FormatException(name + ": invalid format or duration");
            int count = 0;
            foreach (var bone in root.GetProperty("nodeAnimations").EnumerateObject()) {
                foreach (var track in bone.Value.EnumerateObject()) {
                    int previous = -1;
                    foreach (var key in track.Value.EnumerateArray()) {
                        int time;
                        try { time = key.GetProperty("time").GetInt32(); }
                        catch (Exception error) {
                            throw new FormatException(name + ": " + bone.Name + "." + track.Name + " has a time the client cannot read as Int32", error);
                        }
                        if (time < 0 || time > duration || time <= previous)
                            throw new FormatException(name + ": unordered or out of range keyframe on " + bone.Name + "." + track.Name);
                        previous = time;
                        count++;
                    }
                }
            }
            return count;
        }
    }

    public static void VerifyNumberRegression() {
        string[] invalid = { "0.0", "11.25", "0e0", "2147483648" };
        foreach (string value in invalid) {
            using (var document = JsonDocument.Parse("{\"time\":" + value + "}")) {
                bool rejected = false;
                try { document.RootElement.GetProperty("time").GetInt32(); }
                catch (FormatException) { rejected = true; }
                if (!rejected) throw new Exception("Client integer regression accepted " + value);
            }
        }
        using (var document = JsonDocument.Parse("{\"time\":0}")) {
            if (document.RootElement.GetProperty("time").GetInt32() != 0)
                throw new Exception("Valid integer frame was not accepted");
        }
    }
}
'@
}

[StrangeMatterBlockAnimationChecks]::VerifyNumberRegression()
$keyframes = 0
$files = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src/main/resources/Common') -Filter '*.blockyanim' -File -Recurse)
foreach ($file in $files) {
    $keyframes += [StrangeMatterBlockAnimationChecks]::Validate([IO.File]::ReadAllText($file.FullName), $file.Name)
}

$legacyRejected = 0
if ($RegressionSnapshot) {
    $archive = [IO.Compression.ZipFile]::OpenRead($RegressionSnapshot)
    try {
        foreach ($entry in $archive.Entries) {
            if ($entry.FullName -notmatch '/Hoverboard/Surf_[^/]+\.blockyanim$') { continue }
            $reader = [IO.StreamReader]::new($entry.Open())
            try { $json = $reader.ReadToEnd() } finally { $reader.Dispose() }
            $rejected = $false
            try { [void][StrangeMatterBlockAnimationChecks]::Validate($json, $entry.Name) }
            catch { $rejected = $true; $legacyRejected++ }
            if (-not $rejected) { throw "Expected the old client-invalid animation to fail: $($entry.Name)" }
        }
        if ($legacyRejected -ne 3) { throw "Expected three legacy surfing clips, found $legacyRejected" }
    } finally { $archive.Dispose() }
}

@{ result = 'PASS'; parser = 'System.Text.Json'; animationFiles = $files.Count; integerKeyframes = $keyframes; rejectedLegacyClips = $legacyRejected } | ConvertTo-Json -Compress
