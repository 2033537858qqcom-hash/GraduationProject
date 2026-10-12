Set-StrictMode -Version Latest

function Assert-AgentEvalDefinition {
    param([Parameter(Mandatory)][object]$Definition)
    if ($Definition.schemaVersion -ne 1 -or $Definition.evaluationType -ne 'deterministic-regression' -or $Definition.id -notmatch '^[a-z0-9-]{6,80}$') { throw 'Invalid Agent evaluation definition.' }
    $evalCases=@($Definition.cases)
    if ($Definition.requiredCount -lt 1 -or $Definition.requiredCount -gt 1000 -or $evalCases.Count -ne $Definition.requiredCount) { throw 'Evaluation case count differs from the required count.' }
    $evalIds=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $evalTests=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($evalCase in $evalCases) {
        if ($evalCase.id -cnotmatch '^[A-Z]+-[0-9]{2,3}$' -or [string]::IsNullOrWhiteSpace($evalCase.group) -or [string]::IsNullOrWhiteSpace($evalCase.title) -or [string]::IsNullOrWhiteSpace($evalCase.criterion) -or $evalCase.testClass -cnotmatch '^Agent[A-Za-z0-9_]*Test$' -or $evalCase.testMethod -cnotmatch '^[a-z][A-Za-z0-9_]*$') { throw 'Invalid case ID, criterion or test reference.' }
        if (-not $evalIds.Add($evalCase.id) -or -not $evalTests.Add("$($evalCase.testClass)#$($evalCase.testMethod)")) { throw 'Duplicate case ID or test reference.' }
    }
}

function Get-AgentEvalDefinition {
    param([Parameter(Mandatory)][string]$Path)
    $evalDefinition=Get-Content -LiteralPath $Path -Raw -Encoding utf8 | ConvertFrom-Json
    Assert-AgentEvalDefinition $evalDefinition
    return $evalDefinition
}

function Get-AgentEvalReport {
    param([Parameter(Mandatory)][object]$Definition,[AllowEmptyCollection()][string[]]$JUnitFiles=@(),[int]$MavenExitCode=0)
    Assert-AgentEvalDefinition $Definition
    $evalResults=[Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
    $evalParseErrors=[Collections.Generic.List[object]]::new()
    foreach ($evalFile in $JUnitFiles) {
        $evalReader=$null
        try {
            $evalSettings=[Xml.XmlReaderSettings]::new()
            $evalSettings.DtdProcessing=[Xml.DtdProcessing]::Prohibit
            $evalSettings.XmlResolver=$null
            $evalSettings.MaxCharactersInDocument=10000000
            $evalReader=[Xml.XmlReader]::Create($evalFile,$evalSettings)
            $evalDocument=[Xml.XmlDocument]::new(); $evalDocument.XmlResolver=$null; $evalDocument.Load($evalReader)
            foreach ($evalNode in $evalDocument.SelectNodes('//testcase')) {
                $evalKey="$($evalNode.GetAttribute('classname'))#$($evalNode.GetAttribute('name'))"
                $evalState=if ($evalNode.SelectNodes('./failure').Count) { 'FAILED' } elseif ($evalNode.SelectNodes('./error').Count) { 'ERROR' } elseif ($evalNode.SelectNodes('./skipped').Count) { 'SKIPPED' } else { 'PASSED' }
                $evalSeconds=0.0
                if ($evalNode.GetAttribute('time') -and -not [double]::TryParse($evalNode.GetAttribute('time'),[Globalization.NumberStyles]::Float,[Globalization.CultureInfo]::InvariantCulture,[ref]$evalSeconds)) { $evalState='ERROR' }
                if ($evalSeconds -lt 0 -or [double]::IsNaN($evalSeconds) -or [double]::IsInfinity($evalSeconds)) { $evalState='ERROR'; $evalSeconds=0.0 }
                if ($evalResults.ContainsKey($evalKey)) { $evalResults[$evalKey]=[pscustomobject]@{status='DUPLICATE';seconds=0.0} }
                else { $evalResults[$evalKey]=[pscustomobject]@{status=$evalState;seconds=$evalSeconds} }
            }
        } catch { $evalParseErrors.Add([pscustomobject]@{file=[IO.Path]::GetFileName($evalFile);code='JUNIT_XML_INVALID'}) }
        finally { if ($null -ne $evalReader) { $evalReader.Dispose() } }
    }
    $evalRows=@(foreach ($evalCase in $Definition.cases) {
        $evalKey="cn.zzuit.campus.$($evalCase.testClass)#$($evalCase.testMethod)"
        $evalStatus='MISSING'; $evalDuration=0.0
        if ($evalResults.ContainsKey($evalKey)) { $evalStatus=$evalResults[$evalKey].status; $evalDuration=$evalResults[$evalKey].seconds }
        [pscustomobject][ordered]@{id=$evalCase.id;group=$evalCase.group;title=$evalCase.title;criterion=$evalCase.criterion;status=$evalStatus;durationSeconds=$evalDuration;test="$($evalCase.testClass)#$($evalCase.testMethod)"}
    })
    $evalGroups=@(foreach ($evalGroup in ($evalRows | Group-Object group)) {
        [pscustomobject][ordered]@{name=$evalGroup.Name;total=$evalGroup.Count;passed=@($evalGroup.Group | Where-Object status -EQ 'PASSED').Count;notPassed=@($evalGroup.Group | Where-Object status -NE 'PASSED').Count}
    })
    $evalPassed=@($evalRows | Where-Object status -EQ 'PASSED').Count
    $evalUnexpected=$evalResults.Count - @($evalRows | Where-Object status -NE 'MISSING').Count
    return [pscustomobject][ordered]@{
        schemaVersion=1;definitionId=$Definition.id;evaluationType='deterministic-regression';attempts=1
        externalModelCalls=$false;realModelQuality='NOT_EVALUATED';mavenExitCode=$MavenExitCode
        total=$evalRows.Count;passed=$evalPassed;notPassed=$evalRows.Count-$evalPassed
        passedAll=($MavenExitCode -eq 0 -and $evalPassed -eq $Definition.requiredCount -and $evalParseErrors.Count -eq 0 -and $evalUnexpected -eq 0)
        unexpectedTests=$evalUnexpected;reportErrors=@($evalParseErrors.ToArray());groups=$evalGroups;cases=$evalRows
    }
}

function ConvertTo-AgentEvalMarkdown {
    param([Parameter(Mandatory)][object]$Report)
    $evalLines=[Collections.Generic.List[string]]::new()
    $evalLines.Add('# 校园 Agent 固定场景回归报告')
    $evalLines.Add('')
    $evalLines.Add("本次首轮：$($Report.passed) / $($Report.total) 项通过；整体通过：$($Report.passedAll)。")
    $evalLines.Add('')
    $evalLines.Add('模式：独立数据库、真实领域服务、受控模型替身。没有调用外部模型，不代表真实 DeepSeek 理解准确率。')
    $evalLines.Add('')
    $evalLines.Add('| 分类 | 通过 | 未通过 |')
    $evalLines.Add('| --- | --- | --- |')
    foreach ($evalGroup in $Report.groups) { $evalLines.Add("| $($evalGroup.name) | $($evalGroup.passed) / $($evalGroup.total) | $($evalGroup.notPassed) |") }
    $evalLines.Add('')
    $evalLines.Add('| 案例 | 场景 | 状态 | 判定器 |')
    $evalLines.Add('| --- | --- | --- | --- |')
    foreach ($evalCase in $Report.cases) { $evalLines.Add("| $($evalCase.id) | $($evalCase.title.Replace('|','／')) | $($evalCase.status) | $($evalCase.test) |") }
    $evalLines.Add('')
    $evalLines.Add("Maven 退出码：$($Report.mavenExitCode)。无结果、跳过、重复结果、XML 错误及构建失败均不算通过。失败细节请在本次目录的本地 Maven 日志和 JUnit 文件中定位。")
    return ($evalLines -join [Environment]::NewLine)
}
