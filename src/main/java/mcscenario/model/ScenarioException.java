package mcscenario.model;

/** A scenario file is invalid, or a run could not be carried out. */
public class ScenarioException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ScenarioException(String message) {
        super(message);
    }

    public ScenarioException(String message, Throwable cause) {
        super(message, cause);
    }
}
