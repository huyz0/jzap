package io.github.huyz0.jzap.report;

import io.github.huyz0.jzap.model.AnalysisResult;

/** Turns a result into something a human or a machine downstream can consume. */
public interface Reporter {

    String id();

    void write(AnalysisResult result, ReportContext context);
}
