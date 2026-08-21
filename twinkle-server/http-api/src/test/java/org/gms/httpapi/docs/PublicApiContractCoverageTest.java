package org.gms.httpapi.docs;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** 已冻结的第三方契约必须覆盖所有实际 public v1 路由。 */
class PublicApiContractCoverageTest {

    private static final Pattern PATH_LINE = Pattern.compile("^  (/api/v1(?:/[^:]+)?):\\s*$");
    private static final Pattern METHOD_LINE = Pattern.compile("^    (get|post|put|delete|patch):\\s*$");

    @Test
    void frozenContractContainsEveryPublicV1Route() throws Exception {
        String contract;
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("openapi/public/v1/openapi.yaml")) {
            assertThat(input).as("public v1 OpenAPI resource").isNotNull();
            contract = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        Set<Route> actualRoutes = new LinkedHashSet<>();
        for (Class<?> controllerType : publicV1Controllers()) {
            String base = controllerType.getAnnotation(Controller.class).value();
            for (Method method : controllerType.getDeclaredMethods()) {
                for (Route route : routesOf(method)) {
                    actualRoutes.add(new Route(route.method(), stripQueryTemplate(join(base, route.path()))));
                }
            }
        }
        assertThat(parseContractRoutes(contract))
                .as("public v1 OpenAPI path and method set")
                .containsExactlyInAnyOrderElementsOf(actualRoutes);
    }

    private static List<Class<?>> publicV1Controllers() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("org.gms.httpapi.api.v1.controller")
                .stream()
                .map(JavaClass::getName)
                .map(PublicApiContractCoverageTest::loadClass)
                .filter(type -> type.isAnnotationPresent(Controller.class))
                .toList();
    }

    private static Class<?> loadClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Route> routesOf(Method method) {
        if (method.isAnnotationPresent(Get.class)) {
            Get route = method.getAnnotation(Get.class);
            return values("get", route.value(), route.uri(), route.uris());
        }
        if (method.isAnnotationPresent(Post.class)) {
            Post route = method.getAnnotation(Post.class);
            return values("post", route.value(), route.uri(), route.uris());
        }
        if (method.isAnnotationPresent(Put.class)) {
            Put route = method.getAnnotation(Put.class);
            return values("put", route.value(), route.uri(), route.uris());
        }
        if (method.isAnnotationPresent(Delete.class)) {
            Delete route = method.getAnnotation(Delete.class);
            return values("delete", route.value(), route.uri(), route.uris());
        }
        return List.of();
    }

    private static List<Route> values(String method, String value, String uri, String[] uris) {
        if (!(uris.length == 1 && "/".equals(uris[0]))) {
            return java.util.Arrays.stream(uris).map(path -> new Route(method, path)).toList();
        }
        if (!"/".equals(uri)) {
            return List.of(new Route(method, uri));
        }
        return List.of(new Route(method, value));
    }

    private static String join(String base, String route) {
        if (route.isBlank() || "/".equals(route)) {
            return base;
        }
        if (base.isBlank()) {
            return route;
        }
        if (base.endsWith("/") && route.startsWith("/")) {
            return base.substring(0, base.length() - 1) + route;
        }
        return base.endsWith("/") || route.startsWith("/") ? base + route : base + "/" + route;
    }

    private static Set<Route> parseContractRoutes(String contract) {
        Set<Route> routes = new LinkedHashSet<>();
        String currentPath = null;
        for (String line : contract.lines().toList()) {
            if (line.startsWith("components:")) {
                break;
            }
            Matcher path = PATH_LINE.matcher(line);
            if (path.matches()) {
                currentPath = path.group(1);
                continue;
            }
            Matcher method = METHOD_LINE.matcher(line);
            if (currentPath != null && method.matches()) {
                routes.add(new Route(method.group(1), currentPath));
            }
        }
        return routes;
    }

    private static String stripQueryTemplate(String path) {
        int queryTemplate = path.indexOf("{?");
        return queryTemplate < 0 ? path : path.substring(0, queryTemplate);
    }

    private record Route(String method, String path) {
    }
}
