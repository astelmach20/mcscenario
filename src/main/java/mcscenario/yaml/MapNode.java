package mcscenario.yaml;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** A YAML mapping with string keys, in document order. */
final class MapNode {
    private final String path;
    private final Map<String, Object> entries;

    MapNode(String path, Map<String, Object> entries) {
        this.path = path;
        this.entries = entries;
    }

    static MapNode of(Node node, Map<?, ?> raw) {
        Map<String, Object> entries = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            Object key = entry.getKey();
            if (!(key instanceof String || key instanceof Number || key instanceof Boolean)) {
                throw node.error("map keys must be scalars, got " + Node.describe(key));
            }
            if (entries.containsKey(key.toString())) {
                throw node.error("duplicate key \"" + key + "\"");
            }
            entries.put(key.toString(), entry.getValue());
        }
        return new MapNode(node.path(), entries);
    }

    Set<String> keys() {
        return Collections.unmodifiableSet(entries.keySet());
    }

    int size() {
        return entries.size();
    }

    Node get(String key) {
        return new Node(path.isEmpty() ? key : path + "." + key, entries.get(key));
    }

    Node required(String key) {
        Node node = get(key);
        if (node.isAbsent()) {
            throw node.error("required");
        }
        return node;
    }
}
