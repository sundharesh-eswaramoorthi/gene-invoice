package com.geneinvoice.common;

import jakarta.persistence.Lob;
import org.junit.jupiter.api.Test;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * On PostgreSQL Hibernate keeps every {@code @Lob String} as a large object, and reading one outside a transaction
 * fails ("Large Objects may not be used in auto-commit mode", surfacing as "Unable to access lob stream"). The CRUD
 * methods a repository inherits run in {@code SimpleJpaRepository}'s read-only transaction, but a query method the
 * interface declares does not, so the worker and scheduler threads, which call them with none open, broke on
 * Postgres while H2 never noticed (E2E AUT-R1-1/2). This scan makes every such method carry its own
 * {@code @Transactional}.
 */
class LobReadsAreTransactionalTest {

    static final Path MAIN = Path.of("src/main/java");
    static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    static final Pattern JPA_REPOSITORY =
            Pattern.compile("\\binterface\\s+(\\w+)\\b[^{]*\\bextends\\b[^{]*\\bJpaRepository\\s*<\\s*(\\w+)\\s*,");

    @Test
    void everyQueryMethodReadingAnEntityWithALobRunsInATransaction() throws Exception {
        List<Class<?>> repositories = lobRepositories();
        assertThat(repositories).extracting(Class::getSimpleName)
                .as("the scan finds the repositories of entities with a @Lob")
                .contains("AutomationRuleRepository", "AutomationEventRepository",
                        "AutomationRuleRevisionRepository", "PendingChangeRepository", "AuditLogRepository",
                        "DisputeRepository");

        List<String> offences = new ArrayList<>();
        for (Class<?> repository : repositories) offences.addAll(offences(repository));
        assertThat(offences)
                .as("a query method returning an entity with a @Lob must be @Transactional(readOnly = true) "
                        + "(plain @Transactional for a @Lock); on Postgres it fails outside a transaction")
                .isEmpty();
    }

    @Test
    void theGuardFlagsAnUntransactionalReadAndPassesTheRest() {
        assertThat(offences(Sample.class)).containsExactlyInAnyOrder(
                "Sample.bare", "Sample.optional", "Sample.list", "Sample.array");
        assertThat(lobEntity(Sample.class)).contains(LobChild.class);
        assertThat(hasLob(LobChild.class)).as("a @Lob on a superclass counts").isTrue();
        assertThat(hasLob(String.class)).isFalse();

        Matcher m = JPA_REPOSITORY.matcher(
                "public interface DisputeRepository extends JpaRepository<Dispute, Long>, JpaSpecificationExecutor<Dispute> {");
        assertThat(m.find()).isTrue();
        assertThat(m.group(1)).isEqualTo("DisputeRepository");
        assertThat(m.group(2)).isEqualTo("Dispute");
    }

    static List<String> offences(Class<?> repository) {
        Class<?> entity = lobEntity(repository).orElseThrow();
        boolean typeLevel = AnnotatedElementUtils.hasAnnotation(repository, Transactional.class);
        List<String> offences = new ArrayList<>();
        for (Method method : repository.getMethods()) {
            if (method.getDeclaringClass().getName().startsWith("org.springframework.")) continue;
            if (!Modifier.isAbstract(method.getModifiers()) || method.isBridge() || method.isSynthetic()) continue;
            if (method.isAnnotationPresent(Modifying.class)) continue;
            if (!mentions(method.getGenericReturnType(), entity)) continue;
            boolean transactional = typeLevel
                    || AnnotatedElementUtils.hasAnnotation(method, Transactional.class)
                    || AnnotatedElementUtils.hasAnnotation(method.getDeclaringClass(), Transactional.class);
            if (!transactional) offences.add(repository.getSimpleName() + "." + method.getName());
        }
        return offences.stream().sorted().toList();
    }

    static Optional<Class<?>> lobEntity(Class<?> repository) {
        Class<?> entity = ResolvableType.forClass(repository).as(JpaRepository.class).getGeneric(0).resolve();
        return entity != null && hasLob(entity) ? Optional.of(entity) : Optional.empty();
    }

    static boolean hasLob(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            if (Arrays.stream(c.getDeclaredFields()).anyMatch(f -> f.isAnnotationPresent(Lob.class))) return true;
        }
        return false;
    }

    static boolean mentions(Type type, Class<?> entity) {
        if (type instanceof Class<?> c) {
            return c.isArray() ? mentions(c.getComponentType(), entity) : entity.isAssignableFrom(c);
        }
        if (type instanceof ParameterizedType p) {
            return mentions(p.getRawType(), entity)
                    || Arrays.stream(p.getActualTypeArguments()).anyMatch(a -> mentions(a, entity));
        }
        if (type instanceof GenericArrayType g) return mentions(g.getGenericComponentType(), entity);
        if (type instanceof WildcardType w) {
            return Stream.concat(Arrays.stream(w.getUpperBounds()), Arrays.stream(w.getLowerBounds()))
                    .anyMatch(b -> mentions(b, entity));
        }
        if (type instanceof TypeVariable<?> v) {
            return Arrays.stream(v.getBounds()).anyMatch(b -> mentions(b, entity));
        }
        return false;
    }

    static List<Class<?>> lobRepositories() throws IOException, ClassNotFoundException {
        assertThat(MAIN).isDirectory();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(MAIN)) {
            files = walk.filter(f -> f.toString().endsWith(".java")).sorted().toList();
        }
        List<Class<?>> repositories = new ArrayList<>();
        for (Path file : files) {
            String source = Files.readString(file);
            Matcher repo = JPA_REPOSITORY.matcher(source);
            if (!repo.find()) continue;
            Matcher pkg = PACKAGE.matcher(source);
            assertThat(pkg.find()).as("package of " + file).isTrue();
            Class<?> repository = Class.forName(pkg.group(1) + "." + repo.group(1));
            if (lobEntity(repository).isPresent()) repositories.add(repository);
        }
        return repositories;
    }

    static class LobParent {
        @Lob
        String body;
    }

    static class LobChild extends LobParent {
    }

    interface Sample extends JpaRepository<LobChild, Long> {
        LobChild bare(Long id);

        Optional<LobChild> optional(Long id);

        List<? extends LobChild> list();

        LobChild[] array();

        @Transactional(readOnly = true)
        List<LobChild> covered();

        @Modifying
        int modifying();

        long count(String name);

        List<Long> ids();

        default LobChild viaDefault() {
            return bare(1L);
        }
    }
}
