package gherkin.formatter;

import gherkin.formatter.model.*;
import gherkin.util.Mapper;

import java.util.*;
import java.util.regex.Pattern;

import static gherkin.util.FixJava.join;
import static gherkin.util.FixJava.map;

/**
 * This class pretty prints feature files like they were in the source, only
 * prettier. That is, with consistent indentation. This class is also a {@link Formatter},
 * which means it can be used to print execution results - highlighting arguments,
 * printing source information and exception information.
 */
public class PrettyFormatter implements Reporter, Formatter {
    private final StepPrinter stepPrinter = new StepPrinter();
    private final NiceAppendable out;
    private final boolean monochrome;
    private final boolean executing;

    private String uri;
    private Mapper tagNameMapper = new Mapper() {
        public String map(Object tag) {
            return ((Tag) tag).getName();
        }
    };
    private Formats formats;
    private Match match;
    private int[][] cellLengths;
    private int[] maxLengths;
    private int rowIndex;
    private List<Row> rows;
    private Integer rowHeight = null;
    private boolean rowsAbove = false;

    private List<Step> steps = new ArrayList<Step>();
    private List<Integer> indentations = new ArrayList<Integer>();
    private DescribedStatement statement;

    public PrettyFormatter(Appendable out, boolean monochrome, boolean executing) {
        this.out = new NiceAppendable(out);
        this.monochrome = monochrome;
        this.executing = executing;
        setFormats(monochrome);
    }

    private void setFormats(boolean monochrome) {
        if (monochrome) {
            formats = new MonochromeFormats();
        } else {
            formats = new AnsiFormats();
        }
    }

    public void uri(String uri) {
        this.uri = uri;
    }

    public void feature(Feature feature) {
        printComments(feature.getComments(), "");
        printTags(feature.getTags(), "");
        out.println(feature.getKeyword() + ": " + feature.getName());
        printDescription(feature.getDescription(), "  ", false);
    }

    public void background(Background background) {
        replay();
        statement = background;
    }

    public void scenario(Scenario scenario) {
        replay();
        statement = scenario;
    }

    public void scenarioOutline(ScenarioOutline scenarioOutline) {
        replay();
        statement = scenarioOutline;
    }

    private void replay() {
        printStatement();
        printSteps();
    }

    private void printSteps() {
        while (!steps.isEmpty()) {
            printStep("skipped", Collections.<Argument>emptyList(), null, true);
        }
    }

    private void printStatement() {
        if (statement == null) {
            return;
        }
        calculateLocationIndentations();
        out.println();
        printComments(statement.getComments(), "  ");
        if (statement instanceof TagStatement) {
            printTags(((TagStatement) statement).getTags(), "  ");
        }
        out.append("  ");
        out.append(statement.getKeyword());
        out.append(": ");
        out.append(statement.getName());
        String location = executing ? uri + ":" + statement.getLine() : null;
        out.println(indentedLocation(location, true));
        printDescription(statement.getDescription(), "    ", true);
        statement = null;
    }

    private String indentedLocation(String location, boolean proceed) {
        StringBuilder sb = new StringBuilder();
        int indentation = proceed ? indentations.remove(0) : indentations.get(0);
        if (location == null) {
            return "";
        }
        for (int i = 0; i < indentation; i++) {
            sb.append(' ');
        }
        sb.append(' ');
        sb.append(getFormat("comment").text("# " + location));
        return sb.toString();
    }

    public void examples(Examples examples) {
        replay();
        out.println();
        printComments(examples.getComments(), "    ");
        printTags(examples.getTags(), "    ");
        out.append("    ");
        out.append(examples.getKeyword());
        out.append(": ");
        out.append(examples.getName());
        out.println();
        printDescription(examples.getDescription(), "      ", true);
        table(examples.getRows());
    }

    public void step(Step step) {
        steps.add(step);
    }

    public void match(Match match) {
        this.match = match;
        printStatement();
        if (!monochrome) {
            printStep("executing", match.getArguments(), match.getLocation(), false);
        }
    }

    public void result(Result result) {
        if (!monochrome) {
            out.append(formats.up(1));
        }
        printStep(result.getStatus(), match.getArguments(), match.getLocation(), true);
        if (result.getErrorMessage() != null) {
            printError(result);
        }
    }

    private void printStep(String status, List<Argument> arguments, String location, boolean proceed) {
        Step step = proceed ? steps.remove(0) : steps.get(0);
        Format textFormat = getFormat(status);
        Format argFormat = getArgFormat(status);

        printComments(step.getComments(), "    ");
        out.append("    ");
        out.append(textFormat.text(step.getKeyword()));
        stepPrinter.writeStep(out, textFormat, argFormat, step.getName(), arguments);
        out.append(indentedLocation(location, proceed));

        out.println();
        if (step.getRows() != null) {
            table(step.getRows());
        } else if (step.getDocString() != null) {
            docString(step.getDocString());
        }
    }

    private Format getFormat(String key) {
        return formats.get(key);
    }

    private Format getArgFormat(String key) {
        return formats.get(key + "_arg");
    }

    public void table(List<Row> rows) {
        prepareTable(rows);
        if (!executing) {
            for (Row row : rows) {
                row(row.createResults("skipped"));
                nextRow();
            }
        }
    }

    private void prepareTable(List<Row> rows) {
        this.rows = rows;
        int columnCount = rows.get(0).getCells().size();
        cellLengths = new int[rows.size()][columnCount];
        maxLengths = new int[columnCount];
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            for (int colIndex = 0; colIndex < columnCount; colIndex++) {
                int length = escapeCell(rows.get(rowIndex).getCells().get(colIndex)).length();
                cellLengths[rowIndex][colIndex] = length;
                maxLengths[colIndex] = Math.max(maxLengths[colIndex], length);
            }
        }
        rowIndex = 0;
    }

    public void row(List<CellResult> cellResults) {
        Row row = rows.get(rowIndex);
        if (rowsAbove) {
            out.append(formats.up(rowHeight));
        } else {
            rowsAbove = true;
        }
        rowHeight = 1;

        for (Comment comment : row.getComments()) {
            out.append("      ");
            out.println(comment.getValue());
            rowHeight++;
        }
        switch (row.getDiffType()) {
            case NONE:
                out.append("      | ");
                break;
            case DELETE:
                out.append("    ").append(formats.get("skipped").text("-")).append(" | ");
                break;
            case INSERT:
                out.append("    ").append(formats.get("comment").text("+")).append(" | ");
                break;
        }
        for (int colIndex = 0; colIndex < maxLengths.length; colIndex++) {
            String cellText = escapeCell(row.getCells().get(colIndex));
            String status = null;
            switch (row.getDiffType()) {
                case NONE:
                    status = cellResults.get(colIndex).getStatus();
                    break;
                case DELETE:
                    status = "skipped";
                    break;
                case INSERT:
                    status = "comment";
                    break;
            }
            Format format = formats.get(status);
            out.append(format.text(cellText));
            int padding = maxLengths[colIndex] - cellLengths[rowIndex][colIndex];
            padSpace(padding);
            if (colIndex < maxLengths.length - 1) {
                out.append(" | ");
            } else {
                out.append(" |");
            }
        }
        out.println();
        rowHeight++;
        Set<Result> seenResults = new HashSet<Result>();
        for (CellResult cellResult : cellResults) {
            for (Result result : cellResult.getResults()) {
                if (result.getErrorMessage() != null && !seenResults.contains(result)) {
                    printError(result);
                    rowHeight += result.getErrorMessage().split("\n").length;
                    seenResults.add(result);
                }
            }
        }
    }

    private void printError(Result result) {
        Format failed = formats.get("failed");
        out.println(indent(failed.text(result.getErrorMessage()), "      "));
    }

    public void nextRow() {
        rowIndex++;
        rowsAbove = false;
    }

    public void syntaxError(String state, String event, List<String> legalEvents, String uri, int line) {
        throw new UnsupportedOperationException();
    }

    private String escapeCell(String cell) {
        return cell.replaceAll("\\\\(?!\\|)", "\\\\\\\\").replaceAll("\\n", "\\\\n").replaceAll("\\|", "\\\\|");
    }

    public void docString(DocString docString) {
        out.println("      \"\"\"");
        out.append(escapeTripleQuotes(indent(docString.getValue(), "      ")));
        out.println("\n      \"\"\"");
    }

    public void eof() {
        replay();
    }

    private void calculateLocationIndentations() {
        int[] lineWidths = new int[steps.size() + 1];
        int i = 0;

        List<BasicStatement> statements = new ArrayList<BasicStatement>();
        statements.add(statement);
        statements.addAll(steps);
        int maxLineWidth = 0;
        for (BasicStatement statement : statements) {
            int stepWidth = statement.getKeyword().length() + statement.getName().length();
            lineWidths[i++] = stepWidth;
            maxLineWidth = Math.max(maxLineWidth, stepWidth);
        }
        for (int lineWidth : lineWidths) {
            indentations.add(maxLineWidth - lineWidth);
        }
    }

    public void embedding(String mimeType, byte[] data) {
    }

    private void padSpace(int indent) {
        for (int i = 0; i < indent; i++) {
            out.append(" ");
        }
    }

    private void printComments(List<Comment> comments, String indent) {
        for (Comment comment : comments) {
            out.append(indent);
            out.println(comment.getValue());
        }
    }

    private void printTags(List<Tag> tags, String indent) {
        if (tags.isEmpty()) return;
        out.append(indent);
        out.println(join(map(tags, tagNameMapper), " "));
    }

    private void printDescription(String description, String indentation, boolean newline) {
        if (!"".equals(description)) {
            out.println(indent(description, indentation));
            if (newline) out.println();
        }
    }

    private static final Pattern START = Pattern.compile("^", Pattern.MULTILINE);

    private static String indent(String s, String indentation) {
        return START.matcher(s).replaceAll(indentation);
    }

    private static final Pattern TRIPLE_QUOTES = Pattern.compile("\"\"\"", Pattern.MULTILINE);
    private static final String ESCAPED_TRIPLE_QUOTES = "\\\\\"\\\\\"\\\\\"";

    private static String escapeTripleQuotes(String s) {
        return TRIPLE_QUOTES.matcher(s).replaceAll(ESCAPED_TRIPLE_QUOTES);
    }
}