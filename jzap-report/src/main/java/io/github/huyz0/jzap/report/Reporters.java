package io.github.huyz0.jzap.report;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Resolves reporter ids from a project model into reporter instances. */
public final class Reporters {

    private final Map<String, Supplier<Reporter>> registry = new LinkedHashMap<>();

    public Reporters(PrintStream console) {
        registry.put("console", () -> new ConsoleReporter(console));
        registry.put("json", NativeJsonReporter::new);
        registry.put("elements", ElementsJsonReporter::new);
        registry.put("html", HtmlReporter::new);
        registry.put("annotations", AnnotationsJsonReporter::new);
    }

    public List<String> known() {
        return List.copyOf(registry.keySet());
    }

    public Reporter byId(String id) {
        Supplier<Reporter> found = registry.get(id);
        if (found == null) {
            throw new IllegalArgumentException("unknown reporter '" + id + "'. Known reporters: "
                    + String.join(", ", registry.keySet()));
        }
        return found.get();
    }

    public List<Reporter> resolve(List<String> ids) {
        return ids.stream().map(this::byId).toList();
    }
}
