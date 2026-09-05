 = 'Stop'
Invoke-RestMethod -Method Post -Uri 'http://localhost:8086/api/v1/demo/scenario-a' | ConvertTo-Json -Depth 8
