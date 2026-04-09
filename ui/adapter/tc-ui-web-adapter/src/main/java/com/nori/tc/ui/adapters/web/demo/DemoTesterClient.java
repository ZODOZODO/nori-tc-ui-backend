package com.nori.tc.ui.adapters.web.demo;

import java.util.List;

public interface DemoTesterClient {

    List<DemoScenarioCatalogItemResponse> getScenarios(String testType);

    DemoJobCreateResponse createJobs(DemoTesterCreateJobsRequest request);

    List<DemoJobSummaryResponse> getJobs(String states, Integer limit);

    DemoJobDetailResponse getJob(String jobId);

    DemoJobDetailResponse cancelJob(String jobId);

    DemoRunResponse createRun(DemoTesterCreateRunRequest request);

    DemoRunResponse startTarget(String runId, String eqpId, DemoTesterTargetStartRequest request);

    DemoRunResponse stopTarget(String runId, String eqpId);

    DemoRunResponse stopRun(String runId);

    DemoRunResponse getRun(String runId);

    List<DemoTargetResponse> getTargets(String runId);
}
