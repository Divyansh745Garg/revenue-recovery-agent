 = 'Stop'
 = Invoke-RestMethod -Method Post -Uri 'http://localhost:8086/api/v1/demo/scenario-b'
 | ConvertTo-Json -Depth 8
 = Invoke-RestMethod -Method Get -Uri 'http://localhost:8086/api/v1/approvals'
 =  | Where-Object { /bin/bash.orderId -eq .order_id } | Select-Object -First 1
if (-not ) { throw 'Scenario B did not create an approval request.' }
Invoke-RestMethod -Method Post -Uri ("http://localhost:8086/api/v1/approvals/{0}/approve" -f .id) | ConvertTo-Json -Depth 5
