package mcscenario.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.regex.Pattern;

import mcscenario.model.Probe;
import mcscenario.model.ProbeMatch;
import org.junit.jupiter.api.Test;

class ProbeRecorderTest {
    private static Probe probe(String name, String regex) {
        return new Probe(name, Pattern.compile(regex));
    }

    private static final Probe VALUE = probe("value", "\\[probe] (?<key>\\w+)=(?<value>\\S+)");

    @Test
    void recordsNamedGroupsAndParsedValue() {
        ProbeRecorder recorder = new ProbeRecorder(List.of(probe("ticks", "tick (?<tick>\\d+) chunk (?<x>-?\\d+),(?<z>-?\\d+)")));
        recorder.accept("first", 7, "[Server thread/INFO]: tick 120 chunk -3,4");
        assertEquals(List.of(new ProbeMatch("first", "ticks", 7, "[Server thread/INFO]: tick 120 chunk -3,4",
            Map.of("tick", "120", "x", "-3", "z", "4"), OptionalDouble.empty())), recorder.matches());
    }

    @Test
    void parsesValues() {
        ProbeRecorder recorder = new ProbeRecorder(List.of(VALUE));
        recorder.accept("r", 1, "[probe] a=42");
        recorder.accept("r", 2, "[probe] b=-3.25");
        recorder.accept("r", 3, "[probe] c=1e3");
        recorder.accept("r", 4, "[probe] d=abc");
        recorder.accept("r", 5, "[probe] e=1.2.3");
        assertEquals(List.of(OptionalDouble.of(42), OptionalDouble.of(-3.25), OptionalDouble.of(1000),
                OptionalDouble.empty(), OptionalDouble.empty()),
            recorder.matches().stream().map(ProbeMatch::value).toList());
    }

    @Test
    void trimsValueAndSkipsNonParticipatingGroups() {
        ProbeRecorder recorder = new ProbeRecorder(List.of(probe("p", "v=(?<value> *-?[\\d.]+ *)(?<unit>ms)?;")));
        recorder.accept("r", 1, "v= -0.5 ;");
        ProbeMatch match = recorder.matches().getFirst();
        assertEquals(OptionalDouble.of(-0.5), match.value());
        assertEquals(Map.of("value", " -0.5 "), match.groups());
    }

    @Test
    void oneLineCanMatchSeveralProbes() {
        ProbeRecorder recorder = new ProbeRecorder(List.of(VALUE, probe("any-probe", "\\[probe]"), probe("miss", "nope")));
        recorder.accept("r", 9, "[probe] tps=19.9");
        assertEquals(List.of("value", "any-probe"), recorder.matches().stream().map(ProbeMatch::probe).toList());
        assertEquals(OptionalDouble.of(19.9), recorder.matches().getFirst().value());
    }

    @Test
    void filtersByRun() {
        ProbeRecorder recorder = new ProbeRecorder(List.of(VALUE));
        recorder.accept("before", 1, "[probe] x=1");
        recorder.accept("after", 1, "[probe] x=2");
        recorder.accept("before", 2, "[probe] x=3");
        assertEquals(List.of(1L, 2L), recorder.matches("before").stream().map(ProbeMatch::lineNumber).toList());
        assertEquals(1, recorder.matches("after").size());
        assertTrue(recorder.matches("other").isEmpty());
    }

    @Test
    void noMatchRecordsNothing() {
        ProbeRecorder recorder = new ProbeRecorder(List.of(VALUE));
        recorder.accept("r", 1, "[Server thread/INFO]: Done (1.234s)!");
        assertTrue(recorder.matches().isEmpty());
    }

    @Test
    void snapshotIsImmutable() {
        ProbeRecorder recorder = new ProbeRecorder(List.of(VALUE));
        recorder.accept("r", 1, "[probe] x=1");
        List<ProbeMatch> snapshot = recorder.matches();
        recorder.accept("r", 2, "[probe] x=2");
        assertEquals(1, snapshot.size());
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
    }
}
