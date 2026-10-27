#requires -Version 5.1
<#
模块选择与报告入口。业务断言全部保留在 backend/src/test/java 中。
-PrepareOnly 生成简短入口及两个阶段的用例表，不启动测试。
#>
[CmdletBinding()]
param(
    [string[]] $Modules,
    [ValidateSet('All', 'Modules', 'Integrated')]
    [string] $Stage = 'All',
    [ValidateSet('All', 'Guest', 'Customer', 'Staff', 'Admin', 'System')]
    [string] $Role = 'All',
    [switch] $List,
    [switch] $PrepareOnly,
    [string[]] $MavenOptions = @()
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$sourceRoot = Join-Path $projectRoot 'backend/src/test/java'
$documentPath = Join-Path $projectRoot 'docs/testing/README.md'
$caseSpecPath = Join-Path $projectRoot 'scripts/backend-tests/case-specs.json'
$utf8 = New-Object System.Text.UTF8Encoding($false)
$package = 'com.holidaybooking.backend.'

# 每个模块独立启动 Maven/JVM；依赖的测试数据由该模块自行创建。
# 新增测试类时更新模块目录，并在 case-specs.json 登记详细规约；缺失时停止生成。
$catalog = @(
    @{ Key = 'Shared'; Name = '基础规则与启动'; Classes = @('shared.DomainRulesTest', 'shared.BackendApplicationTests'); Scope = '金额、入住区间、Spring 上下文'; Core = $true },
    @{ Key = 'Identity'; Name = '账户与安全'; Classes = @('identity.IdentityServiceTest', 'identity.ApiSecurityTest'); Scope = '注册、密码、管理员初始化、会话、CSRF、权限'; Core = $true },
    @{ Key = 'Catalog'; Name = '房源与库存'; Classes = @('catalog.CatalogServiceTest'); Scope = '房型、实体房、日期价、封房及连续可用性'; Core = $true },
    @{ Key = 'Booking'; Name = '报价与预订'; Classes = @('booking.BookingServiceTest'); Scope = '报价、占房、查询、归属、幂等、并发与回滚'; Core = $true },
    @{ Key = 'Membership'; Name = '会员未付款业务'; Classes = @('membership.MembershipServiceTest'); Scope = '方案、购买单、未付款无权益、归属'; Core = $true },
    @{ Key = 'Management'; Name = '后台管理 API'; Classes = @('management.ManagementApiTest'); Scope = '角色管理、员工接口、审计、参数校验'; Core = $true },
    @{ Key = 'Payment'; Name = '支付本地逻辑'; Classes = @('payment.PaymentRulesTest', 'payment.PaymentFlowTest', 'payment.PaymentRecoveryTest', 'payment.AlipaySignatureTest'); Scope = '金额、模拟付款、恢复、异常复核、本地签名；不访问真实渠道'; Core = $false },
    @{ Key = 'MembershipPaid'; Name = '会员支付后生命周期'; Classes = @('membership.MembershipLifecycleTest'); Scope = '通过本地模拟付款验证激活、续期、到期和折扣'; Core = $false },
    @{ Key = 'Notification'; Name = '通知本地逻辑'; Classes = @('notification.NotificationDeliveryTest', 'notification.EmailAdapterTest'); Scope = '调度、租约、重试、配额、模拟 SMTP；不发送真实邮件'; Core = $false },
    @{ Key = 'Integration'; Name = '本地跨模块流程'; Classes = @('integration.BusinessFlowTest'); Scope = '报价至模拟付款、确认及通知任务的业务闭环'; Core = $false },
    @{ Key = 'MySql'; Name = 'Docker MySQL 事务'; Classes = @('integration.MySqlBookingIT'); Scope = '在独立 MySQL 8.4 容器重用预订用例；需要 Docker'; Core = $false }
)

# 保留各阶段内部原顺序；全选时先完成单模块，再验证跨模块及MySQL。
$integratedKeys = @('MembershipPaid', 'Integration', 'MySql')
$catalog = @($catalog | Where-Object { $_.Key -notin $integratedKeys }) +
    @($catalog | Where-Object { $_.Key -in $integratedKeys })

function Show-Modules {
    Write-Host '模块编号 / 标识 / 功能：'
    for ($i = 0; $i -lt $catalog.Count; $i++) {
        if ($i -eq 0) { Write-Host '第一阶段：单模块检查（纯单元无需数据库；持久化与MockMvc使用MySQL）' }
        if ($catalog[$i].Key -eq 'MembershipPaid') { Write-Host '第二阶段：跨模块与MySQL集成' }
        Write-Host ('{0,2}. {1,-16} {2}：{3}' -f ($i + 1), $catalog[$i].Key, $catalog[$i].Name, $catalog[$i].Scope)
    }
    Write-Host 'All = 先单模块再集成；Core = 核心六模块；-Stage Modules/Integrated = 仅指定阶段。'
    Write-Host '-Role Customer/Staff/Admin/Guest/System 可按用例归属筛选；System不是账户角色。'
}

function Read-Cases {
    $detailedSpecs = Get-Content -LiteralPath $caseSpecPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($detailedSpecs.schemaVersion -ne 1) { throw '不支持的用例规约版本。' }
    $usedSpecKeys = @{}
    $caseIds = @{}
    $registered = @($catalog | ForEach-Object { $_.Classes } | ForEach-Object { $package + $_ })
    foreach ($file in Get-ChildItem -LiteralPath $sourceRoot -Recurse -Filter '*.java') {
        $content = [IO.File]::ReadAllText($file.FullName)
        if ($content -match '@Test\b') {
            $namespace = [regex]::Match($content, 'package\s+([\w.]+);').Groups[1].Value
            $testClass = $namespace + '.' + $file.BaseName
            if ($testClass -notin $registered) {
                throw "发现未登记的测试类：$testClass。请先在模块目录中归类。"
            }
        }
    }
    $pattern = '(?s)@CaseSpec\(\s*input\s*=\s*"(?<input>(?:\\.|[^"\\])*)"\s*,\s*expected\s*=\s*"(?<expected>(?:\\.|[^"\\])*)"\s*\)\s*(?:protected\s+|public\s+)?void\s+(?<method>\w+)\s*\('
    foreach ($module in $catalog) {
        foreach ($class in $module.Classes) {
            # MySQL 类继承 BookingServiceTest：说明和断言使用同一来源。
            $sourceClass = if ($module.Key -eq 'MySql') { 'booking.BookingServiceTest' } else { $class }
            $relativePath = ($package + $sourceClass).Replace('.', '/') + '.java'
            $source = [IO.File]::ReadAllText((Join-Path $sourceRoot $relativePath))
            $matches = [regex]::Matches($source, $pattern)
            $testCount = [regex]::Matches($source, '@Test\b').Count
            if ($matches.Count -ne $testCount -or $testCount -eq 0) {
                throw "测试注解或说明缺失：$relativePath（测试 $testCount，说明 $($matches.Count)）"
            }
            foreach ($match in $matches) {
                $specKey = $package + $sourceClass + '#' + $match.Groups['method'].Value
                $property = $detailedSpecs.cases.PSObject.Properties[$specKey]
                if ($null -eq $property) { throw "缺少详细测试规约：$specKey" }
                $spec = $property.Value
                foreach ($field in @('id', 'title', 'level', 'roles', 'fixture', 'input', 'steps', 'output', 'checks', 'coverageNote')) {
                    if ($null -eq $spec.PSObject.Properties[$field]) { throw "规约 $specKey 缺少 $field" }
                }
                $usedSpecKeys[$specKey] = $true
                $caseId = if ($module.Key -eq 'MySql') { 'MY-' + $spec.id } else { $spec.id }
                if ($caseIds.ContainsKey($caseId)) { throw "测试规约编号重复：$caseId" }
                $caseIds[$caseId] = $true
                [pscustomobject]@{
                    Module = $module.Key
                    Class = $package + $class
                    Source = 'backend/src/test/java/' + $relativePath
                    Method = $match.Groups['method'].Value
                    Input = [regex]::Unescape($match.Groups['input'].Value)
                    Expected = [regex]::Unescape($match.Groups['expected'].Value)
                    Specification = $spec
                    CaseId = $caseId
                }
            }
        }
    }
    foreach ($property in $detailedSpecs.cases.PSObject.Properties) {
        if (-not $usedSpecKeys.ContainsKey($property.Name)) {
            throw "存在未关联 Java 测试的过期规约：$($property.Name)"
        }
    }
}

function ConvertTo-TableCell {
    param([AllowEmptyString()][string] $Text)
    # 保留 JSON 中的尖括号占位符，并防止内容破坏 Markdown 表格。
    return $Text.Replace('\u003c', '<').Replace('\u003e', '>').Replace(
        '&', '&amp;').Replace('<', '&lt;').Replace('>', '&gt;').Replace('|', '&#124;').Replace(
        [Environment]::NewLine, '<br>').Replace([string][char]10, '<br>')
}

function Write-CaseDocument {
    param($Cases, [hashtable] $Evidence, [hashtable] $States, [string] $RunLabel)
    $pages = @(
        @{ File = '01-单模块测试.md'; Title = '第一阶段：单模块测试'; Integrated = $false },
        @{ File = '02-集成测试.md'; Title = '第二阶段：集成测试'; Integrated = $true }
    )
    foreach ($page in $pages) {
        $lines = New-Object 'System.Collections.Generic.List[string]'
        $lines.AddRange([string[]]@(
            "# $($page.Title)", '',
            '[返回测试入口](README.md)', '',
            "当前记录：$RunLabel。点击模块标题展开，只看当前准备测试的模块。", '',
            '每行是一项 Java 测试：功能 → 输入字段和数据 → 预期返回/状态 → 实际结果。ID 可点击查看源码。输入是测试数据描述，Java 测试会自动创建数据；无需手工填写这些 JSON。', '',
            '<details>', '<summary>共用测试数据（按需展开；用例有覆盖值时以该行为准）</summary>', '',
            '| 引用 | 具体值 |', '| --- | --- |',
            '| F0 / 公共数据 | 时钟 T0=2026-10-22T00:00:00Z；邮箱=<UUID>@example.com，密码=test-password-123；用户显示名=Test Guest；房型容量=2、单晚价格=100.05；入住=2026-11-10，离店=2026-11-12；联系人=Guest / guest@example.com / +86 13800000000。 |',
            '| $U/$V、$T、$R、$Q、$B、$P、$A | 当前测试创建的用户、房型、实体房、报价、预订、应付单、支付尝试 ID；不同用户/房间使用不同 UUID，不能写死 ID。S 表示上述入住区间。 |',
            '| F-Recovery | 同一入住区间，1间房、1人/间、100.00/晚；联系人电话=13800000000；已有200.00待付单和Alipay尝试；渠道替身初始返回PENDING。 |',
            '| F-Notification | 与F-Recovery相同的房价、人数和联系人；已模拟支付200.00，生成CONFIRMATION与REMINDER_D1两个任务。 |',
            '| F-Signature | app_id=test-app，seller_id=test-seller，out_trade_no=HB-test-transaction，trade_no=2026102212345678，total_amount=100.00，trade_status=TRADE_SUCCESS，gmt_payment=2026-10-22 08:00:00，sign_type=RSA2；sign由本次生成RSA2048密钥计算。 |',
            '| 固定邮件Task | id/bookingId/revision=1，type=CONFIRMATION，recipient=guest@example.com，subject=subject，body=body，state=READY，attempts=0，dueAt=1970-01-01T00:00:00Z，其余可空字段=null。 |', '', '</details>', '',
            '字符串金额按所列小数位比较；ID比较是否为同一个对象；HTTP测试比较状态码和指定响应字段，Service测试比较Java返回值/异常与数据库状态。未列出的字段不代表已被当前测试断言。', ''
        ))
        if (-not $page.Integrated) {
            $lines.AddRange([string[]]@(
                '纯单元测试不使用数据库；其余Spring/MockMvc测试全部使用独立Docker MySQL 8.4。角色标记表示业务归属，Service测试不代表已验证HTTP权限；权限矩阵见[角色说明](../README.md#roles)。Payment、Notification不访问真实渠道。', ''
            ))
        } else {
            $lines.AddRange([string[]]@(
                '建议先完成第一阶段。所有持久化测试均使用Docker MySQL；这里进一步验证跨模块联动及预订事务。Docker不可用会失败，不回退H2或连接开发库。', ''
            ))
        }
        foreach ($module in $catalog) {
            $isIntegrated = $module.Key -in @('MembershipPaid', 'Integration', 'MySql')
            if ($isIntegrated -ne $page.Integrated) { continue }
            $moduleCases = @($Cases | Where-Object Module -eq $module.Key)
            $lines.AddRange([string[]]@(
                '<details>', "<summary>$($module.Name)（$($module.Key)，$($moduleCases.Count)项）</summary>", '',
                '~~~powershell',
                "powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-Backend.ps1 -Modules $($module.Key)",
                '~~~', '',
                '| 用例 | 测试功能 | 具体输入字段与数据 | 预期返回数据 / 状态 | 实际结果 |',
                '| --- | --- | --- | --- | --- |'
            ))
            foreach ($case in $moduleCases) {
                $key = $case.Class + '#' + $case.Method
                $spec = $case.Specification
                $fixture = $spec.fixture -replace '^F0：.*?ID均绑定本用例返回值。', '公共数据；'
                $inputCell = (ConvertTo-TableCell $fixture) + '<br>' +
                    (ConvertTo-TableCell ($spec.input | ConvertTo-Json -Depth 20 -Compress))
                $expectedCell = ($spec.checks | ForEach-Object { ConvertTo-TableCell $_ }) -join '<br>'
                $level = if ($isIntegrated) { '集成' } else { $spec.level }
                $feature = (ConvertTo-TableCell $spec.title) + '<br>（' + $level + '；' + ($spec.roles -join '/') + '）'
                $actual = '未执行'
                if ($Evidence.ContainsKey($key)) {
                    $entry = $Evidence[$key]
                    $actual = ConvertTo-TableCell $entry.status
                    foreach ($value in $entry.actual) { $actual += '<br>' + (ConvertTo-TableCell $value) }
                    if ($entry.reason) { $actual += '<br>' + (ConvertTo-TableCell $entry.reason) }
                } elseif ($States.ContainsKey($key)) {
                    $actual = 'NOT_REPORTED：' + (ConvertTo-TableCell $States[$key])
                }
                $lines.Add("| [$($case.CaseId)](../../$($case.Source)) | $feature | $inputCell | $expectedCell | $actual |")
            }
            $lines.AddRange([string[]]@('', '</details>', ''))
        }
        [IO.File]::WriteAllLines((Join-Path $projectRoot ('docs/testing/' + $page.File)), $lines, $utf8)
    }
    $index = @(
        '# 后端测试：按顺序执行', '',
        '每项用例只列功能、具体输入、预期返回和实际结果。按下列顺序阅读，展开当前需要的模块即可。', '',
        '| 顺序 | 用例文档 | 范围 |',
        '| --- | --- | --- |',
        '| 1 | [单模块测试](01-单模块测试.md) | 基础、账户、房源、预订、会员、后台；可选支付/通知，共42项 |',
        '| 2 | [集成测试](02-集成测试.md) | 会员支付联动、完整预订流程、MySQL事务，共9项 |', '',
        '先看[角色功能与测试](../README.md#roles)，区分顾客、员工、管理员。第一阶段含纯单元与MySQL模块测试；第二阶段验证联动。所有数据库测试都需要Docker，不需安装MySQL或先启动后端；Testcontainers自动建立临时MySQL 8.4，不使用开发库。', '',
        '在项目根目录执行：', '',
        '~~~powershell',
        '# 当前先做核心模块，暂缓支付和邮件',
        'powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-Backend.ps1 -Modules Core',
        '# 单独选择一个或多个模块',
        'powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-Backend.ps1 -Modules Identity,Booking',
        '# 按角色检查核心模块（同一用例可关联多个角色）',
        'powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-Backend.ps1 -Modules Core -Role Customer',
        'powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-Backend.ps1 -Modules Core -Role Staff',
        'powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-Backend.ps1 -Modules Core -Role Admin',
        '# 第一阶段：所有单模块检查（含本地模拟支付/通知）',
        'powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-Backend.ps1 -Stage Modules',
        '# 第二阶段：跨模块与MySQL集成',
        'powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-Backend.ps1 -Stage Integrated',
        '# 全选：严格先第一阶段、再第二阶段',
        'powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-Backend.ps1 -Modules All',
        '~~~', '',
        '不带参数显示选择菜单；-List只列模块；-PrepareOnly只生成待执行文档，不运行测试。', '',
        "当前记录：$RunLabel。", '',
        '执行后自动更新两份用例表的实际结果，未选模块显示未执行。本次日志、逐类完整报告及两份表的快照保存在results/java-时间戳/，历史记录保留。单模块失败后仍会继续其他所选模块。', '',
        '退出码：0=全部所选用例通过；1=构建/测试/报告异常；2=存在跳过或中止。-PrepareOnly会重置当前用例表，历史results不受影响。'
    )
    [IO.File]::WriteAllLines($documentPath, $index, $utf8)
}
if ($List) { Show-Modules; exit 0 }
$cases = @(Read-Cases)
$evidence = @{}
$states = @{}
if ($PrepareOnly) {
    Write-CaseDocument $cases $evidence $states '待执行；仅生成说明，未运行测试'
    Write-Host "已生成 $($cases.Count) 项用例说明：$documentPath（未执行测试）"
    exit 0
}

if (-not $Modules -and ($Stage -ne 'All' -or $Role -ne 'All')) { $Modules = @('All') }
if (-not $Modules) {
    Show-Modules
    $Modules = @(Read-Host '请输入模块编号/标识（逗号分隔），或 All/Core；输入 Q 退出')
    if ($Modules[0] -eq 'Q') { exit 0 }
}
$selected = @{}
foreach ($token in (($Modules -join ',') -split '[,，\s]+')) {
    if (-not $token) { continue }
    if ($token -eq 'All' -or $token -eq 'Core') {
        foreach ($module in $catalog) {
            if ($token -eq 'All' -or $module.Core) { $selected[$module.Key] = $true }
        }
        continue
    }
    $number = 0
    if ([int]::TryParse($token, [ref]$number) -and $number -ge 1 -and $number -le $catalog.Count) {
        $selected[$catalog[$number - 1].Key] = $true
        continue
    }
    $match = @($catalog | Where-Object Key -eq $token)
    if ($match.Count -ne 1) { throw "未知模块：$token。请用 -List 查看可选值。" }
    $selected[$match[0].Key] = $true
}
foreach ($key in @($selected.Keys)) {
    if (($Stage -eq 'Modules' -and $key -in $integratedKeys) -or
        ($Stage -eq 'Integrated' -and $key -notin $integratedKeys)) {
        $selected.Remove($key)
    }
}
if ($selected.Count -eq 0) { throw '当前模块选择与阶段没有交集，请检查 -Modules 与 -Stage。' }
$selectedCases = @($cases | Where-Object {
    $selected.ContainsKey($_.Module) -and ($Role -eq 'All' -or $Role -in $_.Specification.roles)
})
if ($selectedCases.Count -eq 0) { throw '当前选择没有该角色的用例，请检查 -Modules、-Stage 和 -Role。' }

$runId = 'java-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 6)
$runRoot = Join-Path $projectRoot "results/$runId"
[IO.Directory]::CreateDirectory($runRoot) | Out-Null
$exitCode = 0
$runEntries = @()
Write-CaseDocument $cases $evidence $states "$runId（准备执行）"

foreach ($module in $catalog) {
    if (-not $selected.ContainsKey($module.Key)) { continue }
    $expectedCases = @($selectedCases | Where-Object Module -eq $module.Key)
    if ($expectedCases.Count -eq 0) { continue }
    $moduleRoot = Join-Path $runRoot $module.Key
    [IO.Directory]::CreateDirectory($moduleRoot) | Out-Null
    $log = Join-Path $moduleRoot 'console.log'
    # 方法级选择防止选择STAFF时连带执行同一类中无关的顾客/管理员用例。
    $classes = ($expectedCases | Group-Object Class | ForEach-Object {
        $_.Name + '#' + (($_.Group | ForEach-Object { $_.Method }) -join '+')
    }) -join ','
    $arguments = @('-B', '-f', (Join-Path $projectRoot 'backend/pom.xml')) + $MavenOptions
    $arguments += "-Dtest.results.directory=$moduleRoot"
    if ($module.Key -eq 'MySql') {
        $arguments += @("-Dit.test=$classes", 'test-compile', 'failsafe:integration-test', 'failsafe:verify')
    } else {
        $arguments += @("-Dtest=$classes", 'test')
    }
    Write-Host "`n===== $($module.Key)：$($module.Name) ====="
    $nativeExit = 1
    try {
        # Windows PowerShell 将本机 stderr 包装为 ErrorRecord；由退出码判定构建结果。
        $ErrorActionPreference = 'Continue'
        & (Join-Path $projectRoot 'backend/mvnw.cmd') @arguments 2>&1 |
            ForEach-Object { $line = $_.ToString(); Write-Host $line; $line } |
            Out-File -FilePath $log -Encoding utf8
        $nativeExit = $LASTEXITCODE
    } catch {
        $_ | Out-File -FilePath $log -Encoding utf8 -Append
        Write-Warning "模块启动失败：$_"
    } finally {
        $ErrorActionPreference = 'Stop'
    }
    $moduleResults = @()
    try {
        foreach ($file in @(Get-ChildItem -LiteralPath $moduleRoot -Recurse -Filter results.json)) {
            $data = Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
            foreach ($entry in $data.cases) {
                $evidence[$entry.testClass + '#' + $entry.method] = $entry
                $moduleResults += $entry
            }
        }
        $missing = @($expectedCases | Where-Object { -not $evidence.ContainsKey($_.Class + '#' + $_.Method) })
        $failed = @($moduleResults | Where-Object { $_.status -in @('FAILED', 'SETUP_FAILED') })
        $skipped = @($moduleResults | Where-Object { $_.status -in @('SKIPPED', 'ABORTED') })
        if ($nativeExit -ne 0 -or $missing.Count -gt 0 -or $failed.Count -gt 0) {
            $states[$module.Key] = "异常：Maven退出码=$nativeExit；未报告=$($missing.Count)；失败=$($failed.Count)"
            $exitCode = 1
        } elseif ($skipped.Count -gt 0) {
            $states[$module.Key] = "存在跳过/中止：$($skipped.Count) 项"
            if ($exitCode -eq 0) { $exitCode = 2 }
        } else {
            $states[$module.Key] = "PASSED：$($moduleResults.Count) 项"
        }
    } catch {
        $states[$module.Key] = "报告读取失败：$($_.Exception.Message)"
        $exitCode = 1
    }
    Write-Host "$($module.Key)：$($states[$module.Key])；日志：$log"
    foreach ($case in $expectedCases) { $states[$case.Class + '#' + $case.Method] = $states[$module.Key] }
    $runEntries += [pscustomobject]@{ module = $module.Key; mavenExitCode = $nativeExit; status = $states[$module.Key]; log = $log }
    Write-CaseDocument $cases $evidence $states $runId
    $indexSnapshot = [IO.File]::ReadAllText($documentPath).Replace(
        '(../README.md#roles)', '(../../docs/README.md#roles)').Replace(
        '(01-单模块测试.md)', '(testing/01-单模块测试.md)').Replace(
        '(02-集成测试.md)', '(testing/02-集成测试.md)')
    [IO.File]::WriteAllText((Join-Path $runRoot '测试说明与实际输出.md'), $indexSnapshot, $utf8)
    $snapshotDirectory = Join-Path $runRoot 'testing'
    [IO.Directory]::CreateDirectory($snapshotDirectory) | Out-Null
    foreach ($fileName in @('01-单模块测试.md', '02-集成测试.md')) {
        $snapshot = [IO.File]::ReadAllText((Join-Path $projectRoot "docs/testing/$fileName"))
        # 快照多一层results目录，保持源码链接和返回入口仍可点击。
        $snapshot = $snapshot.Replace('(../../backend/', '(../../../backend/').Replace(
            '(README.md)', '(../测试说明与实际输出.md)').Replace(
            '(../README.md#roles)', '(../../../docs/README.md#roles)')
        [IO.File]::WriteAllText((Join-Path $snapshotDirectory $fileName), $snapshot, $utf8)
    }
    $runEntries | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $runRoot 'run.json') -Encoding UTF8
}
Write-Host "`n结果目录：$runRoot"
Write-Host "统一说明已更新：$documentPath"
exit $exitCode
