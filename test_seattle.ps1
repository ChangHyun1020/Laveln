[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 -bor [Net.SecurityProtocolType]::Tls13
[Net.ServicePointManager]::ServerCertificateValidationCallback = {$true}
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

try {
    $res = Invoke-WebRequest -Uri "https://info.dgtbusan.com/DGT/esvc/vessel/vesselStatus" -WebSession $session -ErrorAction Stop
    $html = $res.Content
    $token = ""
    $header = ""
    if ($html -match '<meta name="_csrf" content="([^"]+)"') { $token = $matches[1] }
    if ($html -match '<meta name="_csrf_header" content="([^"]+)"') { $header = $matches[1] }

    $headers = @{}
    if ($header) { $headers[$header] = $token }

    $now = Get-Date
    $from = $now.AddDays(-5).ToString("yyyyMMdd")
    $to = $now.AddDays(20).ToString("yyyyMMdd")
    
    $body = @{ fromDate = $from; toDate = $to } | ConvertTo-Json
    
    $headers["Content-Type"] = "application/json"

    $scheduleRes = Invoke-RestMethod -Uri "https://info.dgtbusan.com/DGT/berth/vesselSchedule" -Method Post -Body $body -Headers $headers -WebSession $session -ErrorAction Stop
    
    foreach ($v in $scheduleRes.vesselSchedules) {
        if ($v.vesselName -match "(?i)SEATTLE") {
            Write-Host ("FOUND: " + $v.vesselName)
            Write-Host ("vCode: " + $v.vesselCode)
            Write-Host ("vSeq: " + $v.voyageSeq)
            Write-Host ("vYear: " + $v.voyageYear)
            
            $qcBody = @{
                vessel = $v.vesselCode
                voyage = ($v.voyageSeq + "/" + $v.voyageYear)
                inOutCodes = @("D", "L")
            } | ConvertTo-Json
            
            $qcRes = Invoke-RestMethod -Uri "https://info.dgtbusan.com/DGT/document/vesselContainer" -Method Post -Body $qcBody -Headers $headers -WebSession $session -ErrorAction Stop
            Write-Host ("QC Containers: " + $qcRes.containers.Count)
            Write-Host ("QC Error: " + $qcRes.message)
        }
    }
} catch {
    Write-Host $_.Exception.Message
}
