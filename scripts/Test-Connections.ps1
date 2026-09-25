param()
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$configuration = Get-Content -LiteralPath (Join-Path $projectDirectory '.env') -Raw | ConvertFrom-StringData
$kommoHeaders = @{ Authorization = 'Bearer ' + $configuration.KOMMO_TOKEN }
$kommoBase = $configuration.KOMMO_BASE_URL.TrimEnd('/')
$checks = @(
    @{ Name = 'Kommo catalog'; Url = "$kommoBase/api/v4/catalogs/$($configuration.KOMMO_PRODUCT_CATALOG_ID)" },
    @{ Name = 'Kommo product fields'; Url = "$kommoBase/api/v4/catalogs/$($configuration.KOMMO_PRODUCT_CATALOG_ID)/custom_fields" },
    @{ Name = 'Kommo FOP field'; Url = "$kommoBase/api/v4/leads/custom_fields/$($configuration.KOMMO_FOP_FIELD_ID)" }
)
foreach ($check in $checks) {
    try {
        $result = Invoke-RestMethod -Uri $check.Url -Headers $kommoHeaders -TimeoutSec 20
        switch ($check.Name) {
            'Kommo catalog' { @{ Check = $check.Name; Success = $true; CatalogId = $result.id; CatalogType = $result.type } | ConvertTo-Json -Compress }
            'Kommo product fields' {
                $priceFields = @($result._embedded.custom_fields | Where-Object { $_.code -eq $configuration.KOMMO_PRICE_FIELD_CODE } | Select-Object id, code, type)
                @{ Check = $check.Name; Success = $true; PriceFields = $priceFields } | ConvertTo-Json -Compress -Depth 4
            }
            'Kommo FOP field' { @{ Check = $check.Name; Success = $true; FieldId = $result.id; FieldType = $result.type; OptionCount = @($result.enums).Count } | ConvertTo-Json -Compress }
        }
    } catch {
        # Do not print exception bodies/headers, which may contain credentials or account data.
        $status = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
        @{ Check = $check.Name; Success = $false; HttpStatus = $status; ErrorType = $_.Exception.GetType().Name } | ConvertTo-Json -Compress
    }
}
try {
    # task 18 reads PRRO status; it does not open a shift or create a receipt.
    $cash = Invoke-RestMethod -Uri 'https://kasa.vchasno.ua/api/v3/fiscal/execute' -Method Post -ContentType 'application/json' -Headers @{ Authorization = $configuration.VCHASNO_TEST_TOKEN } -Body '{"fiscal":{"task":18}}' -TimeoutSec 30
    $isFiscal = $cash.info.PSObject.Properties['isFis']
    $isTest = if ($null -ne $isFiscal) { $isFiscal.Value -eq 0 } else { $null }
    @{ Check = 'Vchasno register status'; Success = ($cash.res -eq 0 -and $cash.res_action -eq 0); Result = $cash.res; Action = $cash.res_action; TestRegister = $isTest } | ConvertTo-Json -Compress
} catch {
    $status = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
    @{ Check = 'Vchasno register status'; Success = $false; HttpStatus = $status; ErrorType = $_.Exception.GetType().Name } | ConvertTo-Json -Compress
}
