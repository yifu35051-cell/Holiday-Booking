#Requires -Version 5.1
<#
.SYNOPSIS
    Test the running backend without payment or email calls.
.DESCRIPTION
    Uses only PowerShell built-ins. Creates uniquely named test data through APIs.
    No direct SQL writes, fake payment confirmation or database cleanup is performed.
    Each business module builds its own fixtures and can run independently.
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [ValidateSet('All', 'Health', 'Identity', 'Catalog', 'Booking', 'Membership', 'Management')]
    [string]$Module = 'All',
    [string]$AdminEmail = $env:BOOTSTRAP_ADMIN_EMAIL,
    [string]$ReportDirectory = ''
)

$ErrorActionPreference = 'Stop'
if (-not $ReportDirectory) {
    $ReportDirectory = Join-Path $PSScriptRoot '../../backend/target/api-test-reports'
}
$script:BaseUrl = $BaseUrl.TrimEnd('/')
$uri = [Uri]$script:BaseUrl
if ($uri.Scheme -notin @('http', 'https') -or $uri.AbsolutePath -ne '/' -or $uri.Query -or $uri.UserInfo) {
    throw 'BaseUrl must be an HTTP(S) origin, for example http://localhost:8080.'
}

$script:RunId = [Guid]::NewGuid().ToString('N').Substring(0, 12)
$script:CustomerPassword = 'Test-Only-' + [Guid]::NewGuid().ToString('N')
$script:Results = New-Object 'System.Collections.Generic.List[object]'
$script:Resources = New-Object 'System.Collections.Generic.List[object]'
$script:CurrentModule = 'Setup'
$script:Admin = $null
$script:Customer = $null
$script:OtherCustomer = $null

# Hotel dates use UTC+08:00, regardless of the workstation's local timezone.
$today = [DateTimeOffset]::UtcNow.ToOffset([TimeSpan]::FromHours(8)).Date
$script:CheckIn = $today.AddDays(7).ToString('yyyy-MM-dd')
$script:CheckOut = $today.AddDays(9).ToString('yyyy-MM-dd')
$script:MiddleDate = $today.AddDays(8).ToString('yyyy-MM-dd')

. (Join-Path $PSScriptRoot 'ApiHelpers.ps1')

function Test-Health {
    $health = Invoke-Api -Path '/actuator/health'
    Assert-Equal $health.status 'UP' 'Database-backed application health'
    $null = Invoke-Api -Path '/api/v1/room-types'
    $null = Invoke-Api -Path '/api/v1/membership/plans'
}

function Test-Identity {
    $guest = New-ApiSession
    $null = Invoke-Api -Session $guest -Path '/api/v1/auth/me' -ExpectedStatus 401
    $me = (Invoke-Api -Session $script:Customer -Path '/api/v1/auth/me').data
    Assert-Equal $me.role 'CUSTOMER' 'Registration always creates a customer'
    Assert-Equal $me.id $script:Customer.User.id 'Current user belongs to this session'

    $null = Invoke-Api `
        -Session $script:Customer `
        -Method POST `
        -Path '/api/v1/auth/register' `
        -ExpectedStatus 409 `
        -ExpectedCode 'EMAIL_EXISTS' `
        -Body @{
            email = $me.email
            password = $script:CustomerPassword
            displayName = 'Duplicate'
        }
    $null = Invoke-Api `
        -Session $script:Customer `
        -Method POST `
        -Path '/api/v1/auth/register' `
        -ExpectedStatus 422 `
        -ExpectedCode 'VALIDATION_FAILED' `
        -Body @{
            email = 'invalid-email'
            password = 'short'
            displayName = 'Invalid'
        }
    $null = Invoke-Api -Session $script:Customer -Path '/api/v1/admin/room-types' -ExpectedStatus 403

    # A valid cookie alone must not authorize a state-changing request.
    $null = Invoke-Api -Session $script:Customer -Method POST -Path '/api/v1/auth/logout' -WithoutCsrf -ExpectedStatus 403
    $null = Invoke-Api -Session $script:Customer -Path '/api/v1/auth/me'
    $null = Invoke-Api -Session $script:Customer -Method POST -Path '/api/v1/auth/logout' -ExpectedStatus 204
    $null = Invoke-Api -Session $script:Customer -Path '/api/v1/auth/me' -ExpectedStatus 401

    Update-Csrf $guest
    $null = Invoke-Api `
        -Session $guest `
        -Method POST `
        -Path '/api/v1/auth/login' `
        -ExpectedStatus 401 `
        -ExpectedCode 'AUTHENTICATION_FAILED' `
        -Body @{
            email = $me.email
            password = 'An-Incorrect-Password-123'
        }
    Connect-Account $script:Customer $me.email $script:CustomerPassword
}

function Test-Catalog {
    $fixture = New-TestType 'CAT' 2
    $typeId = $fixture.Type.id
    $roomId = $fixture.Rooms[0].id
    $secondRoomId = $fixture.Rooms[1].id
    $detail = (Invoke-Api -Path "/api/v1/room-types/$typeId").data
    Assert-Equal $detail.basePrice '200.00' 'Public room-type detail'

    $updated = (Invoke-Api -Session $script:Admin -Method PUT -Path "/api/v1/admin/room-types/$typeId" -Body @{
        name = $fixture.Type.name
        description = 'Updated by catalog tests'
        capacity = 2
        basePrice = '220.00'
        active = $true
    }).data
    Assert-Equal $updated.basePrice '220.00' 'Update base price'
    $null = Invoke-Api -Session $script:Admin -Method PUT -Path "/api/v1/admin/room-types/$typeId/rates" -Body @{
        start = $script:CheckIn; end = $script:MiddleDate; price = '300.00'
    }
    $available = @(Get-TestAvailability $typeId)
    Assert-Equal $available[0].availableRooms 2 'Two available physical rooms'
    Assert-Equal $available[0].totalPerRoom '520.00' 'One override night plus one base-price night'

    $listed = (Invoke-Api -Session $script:Admin -Path "/api/v1/admin/rooms?roomTypeId=$typeId").data
    Assert-Equal @($listed).Count 2 'Admin room listing'

    # Complementary one-night blocks leave one free room each night,
    # but no single physical room is available for the complete two-night stay.
    $block = (Invoke-Api -Session $script:Admin -Method POST -Path "/api/v1/admin/rooms/$roomId/blocks" -Body @{
        start = $script:CheckIn; end = $script:MiddleDate; reason = 'Test first night'
    }).data
    $secondBlock = (Invoke-Api -Session $script:Admin -Method POST -Path "/api/v1/admin/rooms/$secondRoomId/blocks" -Body @{
        start = $script:MiddleDate; end = $script:CheckOut; reason = 'Test second night'
    }).data
    Assert-Equal @(Get-TestAvailability $typeId).Count 0 'Continuous availability uses the same physical room'
    $history = (Invoke-Api -Session $script:Admin -Path "/api/v1/admin/rooms/$roomId/blocks").data
    Assert-Equal (@($history | Where-Object { [string]$_.id -eq $block }).Count -eq 1) $true 'Block history contains created block'
    $null = Invoke-Api `
        -Session $script:Admin `
        -Method POST `
        -Path "/api/v1/admin/rooms/$roomId/blocks" `
        -ExpectedStatus 409 `
        -ExpectedCode 'DATA_CONFLICT' `
        -Body @{
            start = $script:CheckIn; end = $script:MiddleDate; reason = 'Overlapping block'
        }
    $null = Invoke-Api -Session $script:Admin -Method DELETE -Path "/api/v1/admin/room-blocks/$block"
    $null = Invoke-Api -Session $script:Admin -Method DELETE -Path "/api/v1/admin/room-blocks/$secondBlock"
    Assert-Equal (@(Get-TestAvailability $typeId)[0].availableRooms) 2 'Unblocking restores inventory'

    $retired = (Invoke-Api -Session $script:Admin -Method DELETE -Path "/api/v1/admin/rooms/$roomId").data
    Assert-Equal $retired.active $false 'Room deletion is logical deactivation'
    Assert-Equal (@(Get-TestAvailability $typeId)[0].availableRooms) 1 'Inactive room excluded'
    $reactivated = (Invoke-Api `
        -Session $script:Admin `
        -Method PATCH `
        -Path "/api/v1/admin/rooms/$roomId/active" `
        -Body @{ active = $true }).data
    Assert-Equal $reactivated.active $true 'Room can be reactivated'

    $null = Invoke-Api -Session $script:Admin -Method PUT -Path "/api/v1/admin/room-types/$typeId" -Body @{
        name = $fixture.Type.name; description = ''; capacity = 2; basePrice = '220.00'; active = $false
    }
    $null = Invoke-Api -Path "/api/v1/room-types/$typeId" -ExpectedStatus 404
    $public = (Invoke-Api -Path '/api/v1/room-types').data
    Assert-Equal @($public | Where-Object { $_.id -eq $typeId }).Count 0 'Inactive type excluded from public list'
    $adminTypes = (Invoke-Api -Session $script:Admin -Path '/api/v1/admin/room-types').data
    Assert-Equal @($adminTypes | Where-Object { $_.id -eq $typeId }).Count 1 'Inactive type retained in admin list'
    $null = Invoke-Api -Path "/api/v1/availability?checkIn=$script:CheckOut&checkOut=$script:CheckIn" -ExpectedStatus 422
}

function Test-Booking {
    $fixture = New-TestType 'BOOK'
    $typeId = $fixture.Type.id
    $roomId = $fixture.Rooms[0].id
    $quoteBody = New-QuoteBody $typeId
    $null = Invoke-Api -Session $script:Admin -Method PUT -Path "/api/v1/admin/room-types/$typeId/rates" -Body @{
        start = $script:CheckIn; end = $script:MiddleDate; price = '300.00'
    }
    $quote = (Invoke-Api -Session $script:Customer -Method POST -Path '/api/v1/quotes' -Body $quoteBody -ExpectedStatus 201).data
    Assert-Equal $quote.totalAmount '500.00' 'Server calculates two-night total'
    Assert-Equal (@(Get-TestAvailability $typeId)[0].availableRooms) 1 'A quote does not reserve inventory'
    $staleQuote = (Invoke-Api -Session $script:OtherCustomer -Method POST -Path '/api/v1/quotes' -Body $quoteBody -ExpectedStatus 201).data

    $invalid = New-QuoteBody $typeId
    $invalid.rooms[0].guestsPerRoom = 3
    $null = Invoke-Api `
        -Session $script:Customer `
        -Method POST `
        -Path '/api/v1/quotes' `
        -Body $invalid `
        -ExpectedStatus 422 `
        -ExpectedCode 'CAPACITY'
    $null = Invoke-Api `
        -Session $script:OtherCustomer `
        -Method POST `
        -Path '/api/v1/bookings' `
        -Body (New-BookingBody $quote.id) `
        -Key "$script:RunId-owner" `
        -ExpectedStatus 404
    $null = Invoke-Api -Session $script:Customer -Method POST -Path '/api/v1/bookings' -Body (New-BookingBody $quote.id) -ExpectedStatus 400

    # Changing current prices must not alter an already issued quote snapshot.
    $null = Invoke-Api -Session $script:Admin -Method PUT -Path "/api/v1/admin/room-types/$typeId/rates" -Body @{
        start = $script:CheckIn; end = $script:CheckOut; price = '900.00'
    }
    $body = New-BookingBody $quote.id
    $key = "$script:RunId-booking"
    $booking = (Invoke-Api -Session $script:Customer -Method POST -Path '/api/v1/bookings' -Body $body -Key $key -ExpectedStatus 201).data
    $script:Resources.Add([pscustomobject]@{ Kind = 'booking'; Id = $booking.id; Label = 'Pending booking' })
    Assert-Equal $booking.status 'PENDING_PAYMENT' 'Unpaid booking remains pending'
    Assert-Equal $booking.totalAmount '500.00' 'Booking preserves quoted price'
    Assert-Equal @($booking.rooms).Count 1 'One physical room assigned'
    Assert-Equal @(Get-TestAvailability $typeId).Count 0 'Pending booking holds inventory'

    $replay = (Invoke-Api -Session $script:Customer -Method POST -Path '/api/v1/bookings' -Body $body -Key $key -ExpectedStatus 201).data
    Assert-Equal $replay.id $booking.id 'Same key and payload returns the same booking'
    $changed = $body.Clone()
    $changed.contactName = 'Different guest'
    $null = Invoke-Api `
        -Session $script:Customer `
        -Method POST `
        -Path '/api/v1/bookings' `
        -Body $changed `
        -Key $key `
        -ExpectedStatus 409 `
        -ExpectedCode 'IDEMPOTENCY_CONFLICT'
    $null = Invoke-Api `
        -Session $script:Customer `
        -Method POST `
        -Path '/api/v1/bookings' `
        -Body $body `
        -Key "$key-again" `
        -ExpectedStatus 409 `
        -ExpectedCode 'QUOTE_CONSUMED'
    $null = Invoke-Api `
        -Session $script:OtherCustomer `
        -Method POST `
        -Path '/api/v1/bookings' `
        -Body (New-BookingBody $staleQuote.id) `
        -Key "$key-stale" `
        -ExpectedStatus 409 `
        -ExpectedCode 'NO_AVAILABILITY'

    $detail = (Invoke-Api -Session $script:Customer -Path "/api/v1/bookings/$($booking.id)").data
    Assert-Equal $detail.userId $script:Customer.User.id 'Customer reads own booking'
    $null = Invoke-Api -Session $script:OtherCustomer -Path "/api/v1/bookings/$($booking.id)" -ExpectedStatus 404
    $page = (Invoke-Api `
        -Session $script:Customer `
        -Path "/api/v1/bookings?status=PENDING_PAYMENT&from=$script:CheckIn&to=$script:CheckOut&page=0&size=10").data
    Assert-Equal @($page.items | Where-Object { $_.id -eq $booking.id }).Count 1 'Filtered customer page includes own booking'
    $null = Invoke-Api -Session $script:Customer -Path '/api/v1/bookings?size=101' -ExpectedStatus 422 -ExpectedCode 'INVALID_PAGINATION'
    $adminDetail = (Invoke-Api -Session $script:Admin -Path "/api/v1/admin/bookings/$($booking.id)").data
    Assert-Equal $adminDetail.id $booking.id 'Admin reads booking'
    $adminPage = (Invoke-Api `
        -Session $script:Admin `
        -Path "/api/v1/admin/bookings?userId=$($script:Customer.User.id)&status=PENDING_PAYMENT").data
    Assert-Equal @($adminPage.items | Where-Object { $_.id -eq $booking.id }).Count 1 'Admin filters by customer'
    $null = Invoke-Api `
        -Session $script:Admin `
        -Method DELETE `
        -Path "/api/v1/admin/rooms/$roomId" `
        -ExpectedStatus 409 `
        -ExpectedCode 'OCCUPIED'
    $null = Invoke-Api `
        -Session $script:Admin `
        -Method PUT `
        -Path "/api/v1/admin/room-types/$typeId" `
        -ExpectedStatus 409 `
        -ExpectedCode 'OCCUPIED' `
        -Body @{
            name = $fixture.Type.name; description = ''; capacity = 1; basePrice = '200.00'; active = $true
        }

    # Multi-type failure must roll back both inventory and quote consumption.
    $a = New-TestType 'ATOMA'
    $b = New-TestType 'ATOMB'
    $multiBody = New-QuoteBody $a.Type.id
    $multiBody.rooms += @{ roomTypeId = $b.Type.id; rooms = 1; guestsPerRoom = 2 }
    $multiQuote = (Invoke-Api -Session $script:Customer -Method POST -Path '/api/v1/quotes' -Body $multiBody -ExpectedStatus 201).data
    $block = (Invoke-Api -Session $script:Admin -Method POST -Path "/api/v1/admin/rooms/$($b.Rooms[0].id)/blocks" -Body @{
        start = $script:CheckIn; end = $script:CheckOut; reason = 'Inventory changes after quote'
    }).data
    $multiBookingBody = New-BookingBody $multiQuote.id
    $null = Invoke-Api `
        -Session $script:Customer `
        -Method POST `
        -Path '/api/v1/bookings' `
        -Body $multiBookingBody `
        -Key "$key-atomic" `
        -ExpectedStatus 409 `
        -ExpectedCode 'NO_AVAILABILITY'
    Assert-Equal (@(Get-TestAvailability $a.Type.id)[0].availableRooms) 1 'Failed multi-type booking leaves first type available'
    $null = Invoke-Api -Session $script:Admin -Method DELETE -Path "/api/v1/admin/room-blocks/$block"
    $multi = (Invoke-Api `
        -Session $script:Customer `
        -Method POST `
        -Path '/api/v1/bookings' `
        -Body $multiBookingBody `
        -Key "$key-atomic" `
        -ExpectedStatus 201).data
    Assert-Equal @($multi.rooms).Count 2 'Same quote and key succeed after stock is restored'
    Assert-Equal $multi.totalAmount '800.00' 'Multi-type total is calculated on server'
    $script:Resources.Add([pscustomobject]@{ Kind = 'booking'; Id = $multi.id; Label = 'Two-type pending booking' })
}

function Test-Membership {
    $member = (Invoke-Api -Session $script:Customer -Path '/api/v1/membership/me').data
    Assert-Equal $member.active $false 'Registration does not grant paid membership'
    Assert-Equal $member.discountRatio '1.0000' 'Non-member has no discount'

    $plan = (Invoke-Api -Session $script:Admin -Method POST -Path '/api/v1/admin/membership-plans' -Body @{
        name = "TEST-$script:RunId-PLAN"; fee = '99.00'; discountRatio = '0.8500'; durationDays = 30
    }).data
    $script:Resources.Add([pscustomobject]@{ Kind = 'membershipPlan'; Id = $plan.id; Label = $plan.name })
    $plans = (Invoke-Api -Path '/api/v1/membership/plans').data
    Assert-Equal @($plans | Where-Object { $_.id -eq $plan.id }).Count 1 'New plan appears in public list'

    $key = "$script:RunId-member"
    $body = @{ planId = $plan.id }
    $purchase = (Invoke-Api `
        -Session $script:Customer `
        -Method POST `
        -Path '/api/v1/membership/purchases' `
        -Body $body `
        -Key $key `
        -ExpectedStatus 201).data
    $script:Resources.Add([pscustomobject]@{ Kind = 'membershipPurchase'; Id = $purchase.id; Label = 'Unpaid purchase' })
    Assert-Equal $purchase.status 'PENDING_PAYMENT' 'Purchase remains pending without payment'
    Assert-Equal $purchase.fee '99.00' 'Purchase snapshots membership fee'
    Assert-Equal $purchase.discountRatio '0.8500' 'Purchase snapshots discount ratio'
    $replay = (Invoke-Api `
        -Session $script:Customer `
        -Method POST `
        -Path '/api/v1/membership/purchases' `
        -Body $body `
        -Key $key `
        -ExpectedStatus 201).data
    Assert-Equal $replay.id $purchase.id 'Membership purchase is idempotent'
    $null = Invoke-Api `
        -Session $script:Customer `
        -Method POST `
        -Path '/api/v1/membership/purchases' `
        -Body @{ planId = ([long]$plan.id + 100000) } `
        -Key $key `
        -ExpectedStatus 409 `
        -ExpectedCode 'IDEMPOTENCY_CONFLICT'
    $detail = (Invoke-Api -Session $script:Customer -Path "/api/v1/membership/purchases/$($purchase.id)").data
    Assert-Equal $detail.id $purchase.id 'Customer can read own membership purchase'
    $null = Invoke-Api -Session $script:OtherCustomer -Path "/api/v1/membership/purchases/$($purchase.id)" -ExpectedStatus 404
    Assert-Equal (Invoke-Api `
        -Session $script:Customer `
        -Path '/api/v1/membership/me').data.active $false 'Unpaid purchase does not activate benefits'

    $null = Invoke-Api -Session $script:Admin -Method DELETE -Path "/api/v1/admin/membership-plans/$($plan.id)"
    $plans = (Invoke-Api -Path '/api/v1/membership/plans').data
    Assert-Equal @($plans | Where-Object { $_.id -eq $plan.id }).Count 0 'Retired plan is hidden'
    $null = Invoke-Api `
        -Session $script:Customer `
        -Method POST `
        -Path '/api/v1/membership/purchases' `
        -Body $body `
        -Key "$key-new" `
        -ExpectedStatus 409 `
        -ExpectedCode 'PLAN_RETIRED'
    $oldPurchase = (Invoke-Api -Session $script:Customer -Path "/api/v1/membership/purchases/$($purchase.id)").data
    Assert-Equal $oldPurchase.fee '99.00' 'Retiring a plan preserves the old purchase snapshot'
}

function Test-Management {
    $userId = $script:OtherCustomer.User.id
    $null = Invoke-Api `
        -Session $script:Admin `
        -Method PUT `
        -Path "/api/v1/admin/users/$($script:Admin.User.id)/role" `
        -Body @{ role = 'CUSTOMER' } `
        -ExpectedStatus 409 `
        -ExpectedCode 'SELF_ROLE_CHANGE'
    try {
        $staff = (Invoke-Api -Session $script:Admin -Method PUT -Path "/api/v1/admin/users/$userId/role" -Body @{ role = 'STAFF' }).data
        Assert-Equal $staff.role 'STAFF' 'Admin can promote the suite-created account'
        # Existing sessions must use the new role without logging in again.
        $null = Invoke-Api -Session $script:OtherCustomer -Path '/api/v1/admin/room-types'
        $page = (Invoke-Api -Session $script:OtherCustomer -Path '/api/v1/admin/bookings?page=0&size=5').data
        Assert-Equal $page.size 5 'Staff can query the admin booking page'
        $null = Invoke-Api -Session $script:OtherCustomer -Path '/api/v1/admin/audit-logs' -ExpectedStatus 403
        $null = Invoke-Api -Session $script:OtherCustomer -Method POST -Path '/api/v1/admin/room-types' -Body @{
            name = 'Forbidden'; description = ''; capacity = 2; basePrice = '100.00'
        } -ExpectedStatus 403

        $fixture = New-TestType 'STAFF' 0
        $room = (Invoke-Api -Session $script:OtherCustomer -Method POST -Path '/api/v1/admin/rooms' -Body @{
            roomTypeId = $fixture.Type.id; number = "$script:RunId-STAFF-1"
        }).data
        Assert-Equal $room.roomTypeId $fixture.Type.id 'Staff can create a physical room'
        $script:Resources.Add([pscustomobject]@{ Kind = 'room'; Id = $room.id; Label = $room.number })
        $audits = (Invoke-Api -Session $script:Admin -Path '/api/v1/admin/audit-logs?page=0&size=100').data
        Assert-Equal (@($audits | Where-Object { $_.action -eq 'CHANGE_ROLE' }).Count -gt 0) $true 'Admin can inspect role-change audit records'
    }
    finally {
        # Only restore the account created by this run; never change an existing user.
        $null = Invoke-Api -Session $script:Admin -Method PUT -Path "/api/v1/admin/users/$userId/role" -Body @{ role = 'CUSTOMER' }
    }
    $null = Invoke-Api -Session $script:OtherCustomer -Path '/api/v1/admin/room-types' -ExpectedStatus 403
}

# Execution and reporting. A module failure does not hide results of other modules.
$startedAt = [DateTimeOffset]::UtcNow
$exitCode = 0
try {
    Write-Host "RunId=$script:RunId BaseUrl=$script:BaseUrl Module=$Module"
    Write-Host 'This suite creates test records. It does not clean or reset the database.'
    $script:CurrentModule = 'Health'
    Test-Health

    if ($Module -ne 'Health') {
        $script:CurrentModule = 'Setup'
        if (-not $AdminEmail) { $AdminEmail = Read-Host 'Existing administrator email' }
        $adminPassword = $env:BOOTSTRAP_ADMIN_PASSWORD
        if (-not $adminPassword) {
            $secret = Read-Host 'Existing administrator password' -AsSecureString
            $credential = New-Object System.Management.Automation.PSCredential($AdminEmail, $secret)
            $adminPassword = $credential.GetNetworkCredential().Password
        }
        $script:Admin = New-ApiSession
        try { Connect-Account $script:Admin $AdminEmail $adminPassword } finally { $adminPassword = $null }
        Assert-Equal $script:Admin.User.role 'ADMIN' 'Configured account is an administrator'
        $script:Customer = New-TestAccount 'customer'
        $script:OtherCustomer = New-TestAccount 'other'

        $modules = if ($Module -eq 'All') { @('Identity', 'Catalog', 'Booking', 'Membership', 'Management') } else { @($Module) }
        foreach ($name in $modules) {
            $script:CurrentModule = $name
            try { & "Test-$name" } catch {
                Add-TestResult "$name aborted" $false $_.Exception.Message
                $exitCode = 1
            }
        }
    }
}
catch {
    Add-TestResult 'Setup or health check failed' $false $_.Exception.Message
    $exitCode = 1
}
finally {
    # Best-effort session cleanup; business records are deliberately retained.
    $script:CurrentModule = 'SessionCleanup'
    foreach ($actor in @($script:Admin, $script:Customer, $script:OtherCustomer)) {
        if ($actor -and $actor.User) {
            try {
                Update-Csrf $actor
                $null = Invoke-Api -Session $actor -Method POST -Path '/api/v1/auth/logout' -ExpectedStatus 204
            }
            catch {
                Add-TestResult 'Session cleanup failed' $false $_.Exception.Message
                $exitCode = 1
            }
        }
    }

    $script:CustomerPassword = $null
    $failed = @($script:Results | Where-Object { -not $_.Passed }).Count
    if ($failed -gt 0) { $exitCode = 1 }
    $report = [ordered]@{
        RunId = $script:RunId
        BaseUrl = $script:BaseUrl
        Module = $Module
        StartedAt = $startedAt.ToString('o')
        FinishedAt = [DateTimeOffset]::UtcNow.ToString('o')
        CheckIn = $script:CheckIn
        CheckOut = $script:CheckOut
        Passed = $script:Results.Count - $failed
        Failed = $failed
        Resources = @($script:Resources.ToArray())
        Checks = @($script:Results.ToArray())
    }
    $null = New-Item -ItemType Directory -Force -Path $ReportDirectory
    $reportFile = Join-Path $ReportDirectory "$script:RunId.json"
    $report | ConvertTo-Json -Depth 15 | Set-Content -Encoding UTF8 -LiteralPath $reportFile
    Write-Host "Passed=$($report.Passed) Failed=$failed Report=$reportFile"
}
exit $exitCode
