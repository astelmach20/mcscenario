package mcscenario.model;

/**
 * A condition over one probe's matches within one run.
 * <ul>
 *     <li>{@code ALL}/{@code ANY}/{@code NONE}: compare each match's {@code value} against {@code operand}</li>
 *     <li>{@code COUNT}: compare the number of matches against {@code operand}</li>
 * </ul>
 * {@code ALL} with zero matches fails: an assertion that never observed anything proves nothing.
 */
public record Assertion(String run, String probe, Quantifier quantifier, Comparison comparison, double operand) {
    public enum Quantifier { ALL, ANY, NONE, COUNT }

    public enum Comparison {
        LT("<"), LE("<="), EQ("=="), NE("!="), GE(">="), GT(">");

        private final String symbol;

        Comparison(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }

        public boolean test(double left, double right) {
            return switch (this) {
                case LT -> left < right;
                case LE -> left <= right;
                case EQ -> left == right;
                case NE -> left != right;
                case GE -> left >= right;
                case GT -> left > right;
            };
        }
    }

    public String describe() {
        String subject = quantifier == Quantifier.COUNT ? "count" : quantifier.name().toLowerCase() + " values";
        return "%s/%s: %s %s %s".formatted(run, probe, subject, comparison.symbol(), formatNumber(operand));
    }

    static String formatNumber(double d) {
        return d == Math.rint(d) && !Double.isInfinite(d) ? Long.toString((long) d) : Double.toString(d);
    }
}
