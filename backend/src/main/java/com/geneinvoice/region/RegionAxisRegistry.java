package com.geneinvoice.region;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

/**
 * Where a feature that owns entities of its own says which region they are in. The whole body of
 * this class is the list below: one line per feature, appended by that feature's unit in the same
 * commit that adds its entities, so a new @Entity cannot reach production unclassified (B1).
 *
 * <p>The line names a class rather than importing it. The blueprint forbids region from importing
 * approval, task, automation or history, and those are exactly the packages that will append here;
 * a compile-time reference would be that forbidden edge under another name. The contributor class
 * lives in its OWN package (approval -> region is the permitted direction), declares a public
 * static {@code CONTRIBUTION} field of type {@link Contribution}, and therefore writes
 * {@code entry(PendingChange.class, RegionAxis.OWN_ID)} with full type safety; only the lookup of
 * the contributor is by name (B1 INTEGRATION).
 */
public final class RegionAxisRegistry {

    private RegionAxisRegistry() {
    }

    /** The name of the public static field each contributor class exposes. */
    public static final String FIELD = "CONTRIBUTION";

    /**
     * One feature's answer for its own entities.
     *
     * @param axes              entity class -> axis, for every entity the feature introduces
     * @param unregionedBecause entity class -> the sentence a reviewer reads, for every NONE
     */
    public record Contribution(Map<Class<?>, RegionAxis> axes,
                               Map<Class<?>, String> unregionedBecause) {

        public Contribution {
            axes = Map.copyOf(axes);
            unregionedBecause = Map.copyOf(unregionedBecause);
        }
    }

    // ONE LINE PER FEATURE. Nothing else belongs in this class.
    private static final List<String> CONTRIBUTORS = List.of(
            "com.geneinvoice.approval.ApprovalRegionAxes",  // PendingChange, ApprovalThreshold (B2)
            "com.geneinvoice.task.TaskRegionAxes",          // Task, TaskAssignee (A6)
            "com.geneinvoice.automation.AutomationRegionAxes", // AutomationEvent (A1-A5)
            // The eleven interval mirrors and the floor table. Its class is HistoryAxes, not the
            // HistoryRegionAxes this placeholder guessed at: UNITS-B3 names the file and the
            // package already has a RegionAxes-shaped neighbour in HistoryRegistry (B3).
            "com.geneinvoice.history.HistoryAxes"
    );

    static List<Contribution> contributions() {
        return CONTRIBUTORS.stream().map(RegionAxisRegistry::load).toList();
    }

    private static Contribution load(String className) {
        try {
            Field field = Class.forName(className).getField(FIELD);
            Object value = field.get(null);
            if (!(value instanceof Contribution c)) {
                throw new IllegalStateException(className + "." + FIELD
                        + " is not a RegionAxisRegistry.Contribution (B1)");
            }
            return c;
        } catch (ReflectiveOperationException e) {
            // A contributor that cannot be loaded means a feature's entities are unclassified, and
            // an unclassified entity that silently answered "visible everywhere" is exactly the
            // leak B1 exists to close, so it fails loudly at class-init instead (B1).
            throw new IllegalStateException("Region axis contributor " + className
                    + " could not be read; it needs a public static " + FIELD + " field (B1)", e);
        }
    }
}
