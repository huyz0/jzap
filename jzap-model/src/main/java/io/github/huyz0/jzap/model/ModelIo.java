package io.github.huyz0.jzap.model;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Reads and writes project models and results.
 *
 * <p>Forward compatibility rule, tested: an unknown field is ignored with a warning, while
 * an unknown {@code schemaVersion} is a hard error. A tolerant reader that silently drops
 * a field the user meant to set is worse than one that refuses to run.
 */
public final class ModelIo {

    private static final Set<String> KNOWN_PROJECT_FIELDS = Set.of(
            "schemaVersion", "modules", "scope", "cache", "reporters", "threads",
            "timeoutFactor", "timeoutConstMillis", "maxMutantsPerMinion");

    private static final Set<String> KNOWN_MODULE_FIELDS = Set.of(
            "id", "mutableCodePaths", "sourceRoots", "testClassPaths", "testClasspath",
            "javaHome", "jvmArgs", "kotlinVersion");

    private static final Set<String> KNOWN_SCOPE_FIELDS = Set.of(
            "kind", "from", "to", "granularity", "patchFile", "includeClasses",
            "excludeClasses", "mutators", "disabledFilters", "enabledFilters");

    private final ObjectMapper mapper;
    private final List<String> warnings = new ArrayList<>();

    public ModelIo() {
        this.mapper = new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(SerializationFeature.INDENT_OUTPUT)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    /** Warnings accumulated by the most recent read. */
    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    public ProjectModel readProjectModel(Path file) {
        warnings.clear();
        try {
            JsonNode root = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isObject()) {
                throw new ModelValidationException(file + ": top level must be a JSON object");
            }
            checkSchemaVersion(file, root);
            warnUnknown(file, "", root, KNOWN_PROJECT_FIELDS);
            JsonNode modules = root.get("modules");
            if (modules != null && modules.isArray()) {
                for (int i = 0; i < modules.size(); i++) {
                    warnUnknown(file, "modules[" + i + "].", modules.get(i), KNOWN_MODULE_FIELDS);
                }
            }
            if (root.has("scope")) {
                warnUnknown(file, "scope.", root.get("scope"), KNOWN_SCOPE_FIELDS);
            }
            return convert(file, root);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read project model " + file, e);
        }
    }

    private void checkSchemaVersion(Path file, JsonNode root) {
        JsonNode v = root.get("schemaVersion");
        if (v == null || v.isNull()) {
            warnings.add(file + ": no schemaVersion, assuming " + ProjectModel.CURRENT_SCHEMA_VERSION);
            return;
        }
        if (!v.isInt()) {
            throw new ModelValidationException(file + ": schemaVersion must be an integer, got " + v);
        }
        if (v.asInt() != ProjectModel.CURRENT_SCHEMA_VERSION) {
            throw new ModelValidationException(file + ": unsupported schemaVersion " + v.asInt()
                    + "; this build of jzap understands version " + ProjectModel.CURRENT_SCHEMA_VERSION
                    + ". Upgrade jzap, or pin the adapter that produced this model.");
        }
    }

    private ProjectModel convert(Path file, JsonNode root) {
        try {
            return mapper.treeToValue(root, ProjectModel.class);
        } catch (Exception e) {
            String detail = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            throw new ModelValidationException(file + ": " + detail, e);
        }
    }

    private void warnUnknown(Path file, String prefix, JsonNode node, Set<String> known) {
        if (node == null || !node.isObject()) {
            return;
        }
        Iterator<String> names = ((ObjectNode) node).fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!known.contains(name)) {
                warnings.add(file + ": unknown field '" + prefix + name + "' ignored");
            }
        }
    }

    public String writeString(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot serialise " + value.getClass().getSimpleName(), e);
        }
    }

    public void write(Path file, Object value) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, writeString(value) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }
}
