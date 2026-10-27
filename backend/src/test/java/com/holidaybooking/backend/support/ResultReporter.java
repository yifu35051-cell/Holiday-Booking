package com.holidaybooking.backend.support;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.*;
import tools.jackson.databind.json.JsonMapper;

/** 按运行生成逐类及汇总报告；整个类因 Docker 不可用而跳过时也记录 SKIPPED。 */
public class ResultReporter implements TestExecutionListener {
    private final Map<String, CaseResult> results = new LinkedHashMap<>();
    private TestPlan plan;
    private Path runDirectory;
    private String startedAt;

    public record CaseResult(
            String testClass,
            String method,
            String input,
            String expected,
            String status,
            List<String> actual,
            String reason) {}

    @Override
    public synchronized void testPlanExecutionStarted(TestPlan testPlan) {
        plan = testPlan;
        startedAt = Instant.now().toString();
        String phase = System.getProperty("test.report.phase", "tests");
        String run =
                DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                                .withZone(ZoneId.of("Asia/Shanghai"))
                                .format(Instant.now())
                        + "-"
                        + phase
                        + "-"
                        + UUID.randomUUID().toString().substring(0, 8);
        runDirectory =
                Path.of(System.getProperty("test.results.directory", "results")).resolve(run);
    }

    @Override
    public void executionStarted(TestIdentifier identifier) {
        if (identifier.isTest()) {
            TestEvidence.begin();
        }
    }

    @Override
    public synchronized void executionSkipped(TestIdentifier identifier, String reason) {
        if (identifier.isTest()) {
            finish(identifier, "SKIPPED", List.of(), reason);
        } else {
            plan.getDescendants(identifier).stream()
                    .filter(TestIdentifier::isTest)
                    .sorted(Comparator.comparing(TestIdentifier::getUniqueId))
                    .forEach(test -> finish(test, "SKIPPED", List.of(), reason));
        }
    }

    @Override
    public synchronized void executionFinished(
            TestIdentifier identifier, TestExecutionResult result) {
        String reason =
                result.getThrowable()
                        .map(error -> error.getClass().getName() + ": " + error.getMessage())
                        .orElse("");
        if (identifier.isTest()) {
            String status =
                    switch (result.getStatus()) {
                        case SUCCESSFUL -> "PASSED";
                        case FAILED -> "FAILED";
                        case ABORTED -> "ABORTED";
                    };
            finish(identifier, status, TestEvidence.snapshot(), reason);
        } else if (result.getStatus() != TestExecutionResult.Status.SUCCESSFUL) {
            plan.getDescendants(identifier).stream()
                    .filter(TestIdentifier::isTest)
                    .filter(test -> !results.containsKey(test.getUniqueId()))
                    .forEach(test -> finish(test, "SETUP_FAILED", List.of(), reason));
        }
    }

    private void finish(
            TestIdentifier identifier, String status, List<String> actual, String reason) {
        if (results.containsKey(identifier.getUniqueId())) {
            return;
        }
        if (!(identifier.getSource().orElse(null) instanceof MethodSource source)) {
            return;
        }
        CaseSpec spec = specification(source);
        var entry =
                new CaseResult(
                        source.getClassName(),
                        source.getMethodName(),
                        spec == null ? "未声明输入" : spec.input(),
                        spec == null ? "未声明预期" : spec.expected(),
                        status,
                        actual,
                        reason);
        results.put(identifier.getUniqueId(), entry);
        System.out.printf(
                "%n[CASE %s] %s#%s%n输入: %s%n预期: %s%n实际: %s%n%s%n",
                status,
                entry.testClass(),
                entry.method(),
                entry.input(),
                entry.expected(),
                actual.isEmpty() ? "未产生业务观测值（查看执行状态/原因）" : String.join("\n", actual),
                reason);
        writeReports();
    }

    private CaseSpec specification(MethodSource source) {
        try {
            for (Class<?> type = Class.forName(source.getClassName());
                    type != null;
                    type = type.getSuperclass()) {
                for (Method method : type.getDeclaredMethods()) {
                    if (method.getName().equals(source.getMethodName())) {
                        return method.getAnnotation(CaseSpec.class);
                    }
                }
            }
            return null;
        } catch (ClassNotFoundException error) {
            throw new IllegalStateException(error);
        }
    }

    @Override
    public synchronized void testPlanExecutionFinished(TestPlan ignored) {
        writeReports();
        System.out.println("测试报告: " + runDirectory.resolve("summary.md").toAbsolutePath());
    }

    private void writeReports() {
        try {
            Files.createDirectories(runDirectory);
            List<CaseResult> entries = List.copyOf(results.values());
            Files.writeString(
                    runDirectory.resolve("summary.md"), markdown(entries), StandardCharsets.UTF_8);
            Files.writeString(
                    runDirectory.resolve("results.json"),
                    JsonMapper.builder()
                            .build()
                            .writerWithDefaultPrettyPrinter()
                            .writeValueAsString(Map.of("startedAt", startedAt, "cases", entries)),
                    StandardCharsets.UTF_8);
            for (String name : entries.stream().map(CaseResult::testClass).distinct().toList()) {
                Files.writeString(
                        runDirectory.resolve(name + ".md"),
                        markdown(entries.stream().filter(e -> e.testClass().equals(name)).toList()),
                        StandardCharsets.UTF_8);
            }
        } catch (IOException error) {
            throw new IllegalStateException("无法写入测试报告 " + runDirectory, error);
        }
    }

    private String markdown(List<CaseResult> entries) {
        StringBuilder text = new StringBuilder("# Java 后端测试实际执行报告\n\n");
        text.append("开始时间（UTC）：").append(startedAt).append("\n\n");
        for (String status : List.of("PASSED", "FAILED", "SKIPPED", "ABORTED", "SETUP_FAILED")) {
            text.append(status)
                    .append(" = ")
                    .append(entries.stream().filter(e -> e.status().equals(status)).count())
                    .append("；");
        }
        text.append("\n\n实际值按测试断言顺序记录。PASSED 表示全部断言通过；SKIPPED/ABORTED 不是通过。")
                .append("数据库用例使用隔离 MySQL；模拟渠道不能替代真实支付或邮件服务验收。\n\n");
        for (CaseResult entry : entries) {
            text.append("## ")
                    .append(entry.testClass())
                    .append("#")
                    .append(entry.method())
                    .append("\n\n")
                    .append("**输入：** ")
                    .append(entry.input())
                    .append("\n\n")
                    .append("**预期：** ")
                    .append(entry.expected())
                    .append("\n\n")
                    .append("**执行状态：** ")
                    .append(entry.status())
                    .append("\n\n")
                    .append("**实际输出：**\n\n~~~text\n")
                    .append(
                            entry.actual().isEmpty()
                                    ? "未产生业务观测值。"
                                    : String.join("\n", entry.actual()))
                    .append("\n~~~\n\n");
            if (!entry.reason().isEmpty()) {
                text.append("**异常/跳过原因：**\n\n~~~text\n").append(entry.reason()).append("\n~~~\n\n");
            }
        }
        return text.toString();
    }
}
