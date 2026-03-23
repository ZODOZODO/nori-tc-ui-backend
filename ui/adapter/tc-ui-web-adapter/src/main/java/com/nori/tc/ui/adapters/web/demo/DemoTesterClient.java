package com.nori.tc.ui.adapters.web.demo;

import java.util.List;

public interface DemoTesterClient {

    DemoRunResponse createRun(DemoTesterCreateRunRequest request);

    DemoRunResponse startTarget(String runId, String eqpId, DemoTesterTargetStartRequest request);

    DemoRunResponse stopTarget(String runId, String eqpId);

    DemoRunResponse stopRun(String runId);

    DemoRunResponse getRun(String runId);

    List<DemoTargetResponse> getTargets(String runId);
}
