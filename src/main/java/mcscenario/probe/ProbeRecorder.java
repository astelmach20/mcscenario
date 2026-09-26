package mcscenario.probe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.regex.Matcher;

import mcscenario.model.Probe;
import mcscenario.model.ProbeMatch;

/** Matches log lines against probes and records every hit. Thread-safe. */
public final class ProbeRecorder {
    private static final String VALUE_GROUP = "value";

    private final List<Probe> probes;
    private final List<ProbeMatch> matches = new ArrayList<>();

    public ProbeRecorder(List<Probe> probes) {
        this.probes = List.copyOf(probes);
    }

    public void accept(String run, long lineNumber, String line) {
        for (Probe probe : probes) {
            Matcher matcher = probe.pattern().matcher(line);
            if (!matcher.find()) {
                continue;
            }
            Map<String, String> groups = new HashMap<>();
            matcher.namedGroups().keySet().forEach(name -> {
                String group = matcher.group(name);
                if (group != null) {
                    groups.put(name, group);
                }
            });
            ProbeMatch match = new ProbeMatch(run, probe.name(), lineNumber, line, groups, parseValue(groups.get(VALUE_GROUP)));
            synchronized (matches) {
                matches.add(match);
            }
        }
    }

    public List<ProbeMatch> matches() {
        synchronized (matches) {
            return List.copyOf(matches);
        }
    }

    public List<ProbeMatch> matches(String run) {
        return matches().stream().filter(match -> match.run().equals(run)).toList();
    }

    private static OptionalDouble parseValue(String value) {
        if (value == null) {
            return OptionalDouble.empty();
        }
        try {
            return OptionalDouble.of(Double.parseDouble(value.trim()));
        } catch (NumberFormatException e) {
            return OptionalDouble.empty();
        }
    }
}
