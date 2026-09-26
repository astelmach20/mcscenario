package mcscenario.yaml;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import mcscenario.model.ScenarioException;

/** A value from SnakeYAML's untyped object tree, with typed accessors that report errors by field path. */
record Node(String path, Object value) {

    boolean isAbsent() {
        return value == null;
    }

    ScenarioException error(String message) {
        return new ScenarioException((path.isEmpty() ? "scenario" : path) + ": " + message);
    }

    String string() {
        if (value instanceof String s) {
            return s;
        }
        throw error("expected a string, got " + describe(value));
    }

    String nonBlankString() {
        String s = string();
        if (s.isBlank()) {
            throw error("must not be blank");
        }
        return s;
    }

    String stringOr(String fallback) {
        return isAbsent() ? fallback : string();
    }

    int intOr(int fallback) {
        if (isAbsent()) {
            return fallback;
        }
        if (value instanceof Integer i) {
            return i;
        }
        throw error("expected an integer, got " + describe(value));
    }

    boolean boolOr(boolean fallback) {
        if (isAbsent()) {
            return fallback;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        throw error("expected true or false, got " + describe(value));
    }

    /** Numbers and booleans are stringified; collections and nulls are rejected. */
    String scalarString() {
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        throw error("expected a string, number or boolean, got " + describe(value));
    }

    /** An absent value is an empty list. */
    List<Node> list() {
        if (isAbsent()) {
            return List.of();
        }
        if (!(value instanceof List<?> items)) {
            throw error("expected a list, got " + describe(value));
        }
        List<Node> nodes = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            nodes.add(new Node(path + "[" + i + "]", items.get(i)));
        }
        return nodes;
    }

    /** A map whose keys must all be in {@code allowedKeys}. */
    MapNode map(List<String> allowedKeys) {
        MapNode map = anyMap();
        for (String key : map.keys()) {
            if (!allowedKeys.contains(key)) {
                throw map.get(key).error("unknown key (expected one of: " + String.join(", ", allowedKeys) + ")");
            }
        }
        return map;
    }

    /** A map with arbitrary keys; an absent value is an empty map. Scalar keys are stringified. */
    MapNode anyMap() {
        if (isAbsent()) {
            return new MapNode(path, Map.of());
        }
        if (!(value instanceof Map<?, ?> raw)) {
            throw error("expected a map, got " + describe(value));
        }
        return MapNode.of(this, raw);
    }

    static String describe(Object value) {
        return switch (value) {
            case null -> "nothing";
            case String s -> '"' + s + '"';
            case Map<?, ?> m -> "a map";
            case List<?> l -> "a list";
            case Number n -> n.toString();
            case Boolean b -> b.toString();
            default -> "a value of type " + value.getClass().getSimpleName();
        };
    }
}
