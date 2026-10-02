package com.geneinvoice.common.scope;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The only reader of the {@code regionIds} and {@code asOf} request parameters. It binds the request's
 * {@link ViewScope}, and refuses a historical request to anything that cannot answer one.
 */
public class ViewScopeInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-View-As-Of";
    public static final String LIVE = "live";

    public enum Mode { AWARE, IGNORED, WRITE, UNCLASSIFIED }

    private static final DateTimeFormatter STRICT_DATE =
            DateTimeFormatter.ISO_LOCAL_DATE.withResolverStyle(ResolverStyle.STRICT);

    private final Clock clock;
    private final AsOfSupport asOfSupport;

    public ViewScopeInterceptor(Clock clock, AsOfSupport asOfSupport) {
        this.clock = clock;
        this.asOfSupport = asOfSupport;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        ViewScopeHolder.clear();
        if (!(handler instanceof HandlerMethod hm)) return true;
        Set<Long> regionIds = parseRegionIds(req.getParameterValues("regionIds"));
        AsOfPoint point = null;
        String raw = req.getParameter("asOf");
        if (raw != null && !raw.isBlank()) {
            LocalDate d = parseDate(raw.trim());
            LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
            if (d.isAfter(today)) throw AsOfException.future();
            Mode mode = classify(hm, req.getMethod());
            if (d.equals(today) || mode == Mode.IGNORED) {
                res.setHeader(HEADER, LIVE);
            } else if (mode == Mode.WRITE) {
                throw AsOfException.readOnly(d);
            } else if (mode == Mode.UNCLASSIFIED) {
                throw AsOfException.unsupported();
            } else {
                asOfSupport.requireAvailable(d);
                point = AsOfPoint.endOfDay(d);
                res.setHeader(HEADER, d.toString());
            }
        }
        ViewScopeHolder.set(new ViewScope(regionIds, point));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex) {
        ViewScopeHolder.clear();
    }

    /**
     * A method-level annotation decides for any HTTP method. Otherwise a GET or HEAD follows its class, and
     * anything else is a write.
     */
    public static Mode classify(HandlerMethod hm, String httpMethod) {
        if (AnnotatedElementUtils.hasAnnotation(hm.getMethod(), AsOfAware.class)) return Mode.AWARE;
        if (AnnotatedElementUtils.hasAnnotation(hm.getMethod(), AsOfIgnored.class)) return Mode.IGNORED;
        if (!"GET".equalsIgnoreCase(httpMethod) && !"HEAD".equalsIgnoreCase(httpMethod)) return Mode.WRITE;
        Class<?> type = hm.getBeanType();
        if (AnnotatedElementUtils.hasAnnotation(type, AsOfAware.class)) return Mode.AWARE;
        if (AnnotatedElementUtils.hasAnnotation(type, AsOfIgnored.class)) return Mode.IGNORED;
        return Mode.UNCLASSIFIED;
    }

    static Set<Long> parseRegionIds(String[] values) {
        if (values == null) return null;
        Set<Long> ids = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) continue;
            for (String token : value.split(",")) {
                String t = token.trim();
                if (t.isEmpty()) continue;
                long id;
                try {
                    id = Long.parseLong(t);
                } catch (NumberFormatException e) {
                    throw CodedBadRequestException.regionIdsInvalid();
                }
                if (id <= 0) throw CodedBadRequestException.regionIdsInvalid();
                ids.add(id);
            }
        }
        return ids.isEmpty() ? null : ids;
    }

    private static LocalDate parseDate(String raw) {
        try {
            return LocalDate.parse(raw, STRICT_DATE);
        } catch (DateTimeParseException e) {
            throw AsOfException.invalid();
        }
    }
}
