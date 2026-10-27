# HTTP helpers for Windows PowerShell 5.1 and PowerShell 7.
# Each actor owns a separate cookie session and CSRF token.

function New-ApiSession {
    return @{
        WebSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
        Token = $null
        User = $null
    }
}

function Add-TestResult {
    param([string]$Name, [bool]$Passed, [string]$Detail = '')

    $script:Results.Add([pscustomobject]@{
        Module = $script:CurrentModule
        Name = $Name
        Passed = $Passed
        Detail = $Detail
    })
    $mark = if ($Passed) { 'PASS' } else { 'FAIL' }
    Write-Host "[$mark] [$script:CurrentModule] $Name $Detail"
}

function Assert-Equal {
    param($Actual, $Expected, [string]$Name)

    $passed = $Actual -ceq $Expected
    Add-TestResult -Name $Name -Passed $passed
    if (-not $passed) {
        throw "$Name : expected [$Expected], actual [$Actual]"
    }
}

function Invoke-Api {
    param(
        [hashtable]$Session,
        [string]$Method = 'GET',
        [Parameter(Mandatory)][string]$Path,
        $Body = $null,
        [int]$ExpectedStatus = 200,
        [string]$ExpectedCode = '',
        [string]$Key = '',
        [switch]$WithoutCsrf
    )

    # Keep payment and notification endpoints outside this suite.
    $publicRoutes = 'auth|room-types|availability|quotes|bookings|membership'
    $adminRoutes = 'room-types|rooms|room-blocks|bookings|membership-plans|users|audit-logs'
    $allowedRoute = "^/(actuator/health|api/v1/($publicRoutes|admin/($adminRoutes)))([/?]|$)"
    if ($Path -notmatch $allowedRoute) {
        throw "Endpoint is outside this suite's scope: $Path"
    }

    $headers = @{}
    if ($Session -and $Session.Token -and -not $WithoutCsrf) {
        $headers['X-CSRF-TOKEN'] = $Session.Token
    }
    if ($Key) { $headers['Idempotency-Key'] = $Key }

    $request = @{
        Uri = "$script:BaseUrl$Path"
        Method = $Method
        Headers = $headers
        UseBasicParsing = $true
        TimeoutSec = 30
        MaximumRedirection = 0
        ErrorAction = 'Stop'
    }
    if ($Session) {
        # Invoke-WebRequest can persist custom headers in WebRequestSession.
        # Clear old values so negative-CSRF tests and new idempotency keys are real.
        $Session.WebSession.Headers.Remove('X-CSRF-TOKEN')
        $Session.WebSession.Headers.Remove('Idempotency-Key')
        $request.WebSession = $Session.WebSession
    }
    if ($null -ne $Body) {
        $request.ContentType = 'application/json; charset=utf-8'
        $request.Body = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 15 -Compress))
    }

    $status = 0
    $raw = ''
    try {
        $response = Invoke-WebRequest @request
        $status = [int]$response.StatusCode
        # Actuator uses a vendor JSON media type; PS 5.1 may return raw bytes.
        $raw = if ($response.Content -is [byte[]]) {
            [Text.Encoding]::UTF8.GetString($response.Content)
        } else {
            [string]$response.Content
        }
    }
    catch {
        if ($null -eq $_.Exception.Response) {
            throw "Cannot reach $script:BaseUrl ($Method $Path): $($_.Exception.Message)"
        }
        $status = [int]$_.Exception.Response.StatusCode
        $raw = $_.ErrorDetails.Message
        # Windows PowerShell may leave ErrorDetails empty.
        if (-not $raw -and $_.Exception.Response -is [System.Net.HttpWebResponse]) {
            $reader = New-Object IO.StreamReader($_.Exception.Response.GetResponseStream())
            try { $raw = $reader.ReadToEnd() } finally { $reader.Dispose() }
        }
    }

    $payload = $null
    if ($raw) {
        try { $payload = $raw | ConvertFrom-Json } catch {
            throw "Non-JSON response for $Method $Path (HTTP $status)."
        }
    }

    # Never store passwords, cookies or request bodies in the report.
    $code = if ($payload -and $payload.PSObject.Properties['code']) { $payload.code } else { '' }
    $trace = if ($payload -and $payload.PSObject.Properties['traceId']) { $payload.traceId } else { '' }
    $passed = $status -eq $ExpectedStatus -and (-not $ExpectedCode -or $code -ceq $ExpectedCode)
    Add-TestResult "$Method $Path" $passed "HTTP=$status code=$code trace=$trace"
    if (-not $passed) {
        throw "Expected HTTP $ExpectedStatus code=$ExpectedCode, received HTTP $status code=$code trace=$trace"
    }
    return $payload
}

function Update-Csrf {
    param([hashtable]$Session)

    $response = Invoke-Api -Session $Session -Path '/api/v1/auth/csrf'
    $Session.Token = $response.data.token
    if (-not $Session.Token) { throw 'CSRF token is missing.' }
}

function Connect-Account {
    param([hashtable]$Session, [string]$Email, [string]$Password)

    Update-Csrf $Session
    $response = Invoke-Api -Session $Session -Method POST -Path '/api/v1/auth/login' -Body @{
        email = $Email
        password = $Password
    }
    $Session.User = $response.data
    # Successful login invalidates the previous CSRF token.
    Update-Csrf $Session
}

function New-TestAccount {
    param([string]$Suffix)

    $session = New-ApiSession
    $email = "test-$script:RunId-$Suffix@example.com"
    Update-Csrf $session
    $registered = Invoke-Api -Session $session -Method POST -Path '/api/v1/auth/register' -ExpectedStatus 201 -Body @{
        email = $email
        password = $script:CustomerPassword
        displayName = "Test $Suffix"
    }
    $script:Resources.Add([pscustomobject]@{ Kind = 'user'; Id = $registered.data.id; Label = $email })
    Connect-Account $session $email $script:CustomerPassword
    return $session
}

function New-TestType {
    param([string]$Label, [int]$RoomCount = 1)

    $type = (Invoke-Api -Session $script:Admin -Method POST -Path '/api/v1/admin/room-types' -Body @{
        name = "TEST-$script:RunId-$Label"
        description = 'Created by the non-payment backend API test suite.'
        capacity = 2
        basePrice = '200.00'
    }).data
    $script:Resources.Add([pscustomobject]@{ Kind = 'roomType'; Id = $type.id; Label = $type.name })

    $rooms = @()
    for ($i = 1; $i -le $RoomCount; $i++) {
        $room = (Invoke-Api -Session $script:Admin -Method POST -Path '/api/v1/admin/rooms' -Body @{
            roomTypeId = $type.id
            number = "$script:RunId-$Label-$i"
        }).data
        $rooms += $room
        $script:Resources.Add([pscustomobject]@{ Kind = 'room'; Id = $room.id; Label = $room.number })
    }
    return @{ Type = $type; Rooms = $rooms }
}

function Get-TestAvailability {
    param([string]$TypeId)

    $response = Invoke-Api -Path "/api/v1/availability?checkIn=$script:CheckIn&checkOut=$script:CheckOut&guestsPerRoom=2&rooms=1"
    return @($response.data | Where-Object { $_.roomType.id -eq $TypeId })
}

function New-QuoteBody {
    param([string]$TypeId, [int]$Rooms = 1)

    return @{
        checkIn = $script:CheckIn
        checkOut = $script:CheckOut
        rooms = @(@{ roomTypeId = $TypeId; rooms = $Rooms; guestsPerRoom = 2 })
    }
}

function New-BookingBody {
    param([string]$QuoteId)

    return @{
        quoteId = $QuoteId
        contactName = 'API Test Guest'
        contactEmail = $script:Customer.User.email
        contactPhone = '+86 13800000000'
    }
}
