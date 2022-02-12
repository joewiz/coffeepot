package org.nineml.coffeepot;

import com.beust.jcommander.*;
import net.sf.saxon.s9api.*;
import org.nineml.coffeefilter.InvisibleXml;
import org.nineml.coffeefilter.InvisibleXmlDocument;
import org.nineml.coffeefilter.InvisibleXmlParser;
import org.nineml.coffeefilter.ParserOptions;
import org.nineml.coffeefilter.exceptions.IxmlException;
import org.nineml.coffeegrinder.parser.*;
import org.nineml.coffeepot.utils.ParserOptionsLoader;
import org.xml.sax.InputSource;

import javax.xml.transform.sax.SAXSource;
import java.io.*;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A command-line Invisible XML parser.
 */
class Main {
    Info info;

    public static void main(String[] args) throws IOException {
        Main driver = new Main();
        try {
            int rc = driver.run(args);
            System.exit(rc);
        } catch (Exception ex) {
            System.err.println(ex.getMessage());
            System.exit(2);
        }
    }

    private int run(String[] args) throws IOException {
        CommandMain cmain = new CommandMain();
        JCommander jc = JCommander.newBuilder().addObject(cmain).build();

        jc.setProgramName("IxmlParser");

        try {
            jc.parse(args);
            if (cmain.help) {
                usage(jc, true);
            }
        } catch (ParameterException pe) {
            System.err.println(pe.getMessage());
            usage(pe.getJCommander(), false);
        }

        info = new Info(cmain.verbose);

        int parseCount = 0;
        boolean allparses = false;

        ParserOptionsLoader loader = new ParserOptionsLoader(cmain.verbose);
        ParserOptions options = loader.loadOptions();

        options.verbose = options.verbose || cmain.verbose;
        options.prettyPrint = options.prettyPrint || cmain.prettyPrint;

        if (cmain.graphSvg != null) {
            if (options.graphviz == null) {
                System.err.println("Cannot output SVG; GraphViz is not configured.");
                cmain.graphSvg = null;
            } else {
                try {
                    new Processor(false);
                } catch (Exception ex) {
                    System.err.println("Cannot output SVG; failed to find Saxon on the classpath.");
                    cmain.graphSvg = null;
                }
            }
        }

        if (cmain.parse != null) {
            if (cmain.parse.equals("all")) {
                allparses = true;
            } else {
                parseCount = Integer.parseInt(cmain.parse);
                if (parseCount < 1) {
                    System.err.println("Ignoring absurd parse number: " + parseCount);
                    parseCount = 0;
                }
            }
        }

        String input = null;
        if (!cmain.inputText.isEmpty()) {
            boolean first = true;
            StringBuilder sb = new StringBuilder();
            for (String token : cmain.inputText) {
                if (!first) {
                    sb.append(" ");
                }
                first = false;
                sb.append(token);
            }
            input = sb.toString();
        }

        if (cmain.inputFile == null && input == null) {
            usage(jc, true);
        }

        if (cmain.inputFile != null && input != null) {
            usage(jc, false, "Input cannot come from both a file and the command line.");
        }

        InvisibleXmlParser parser;
        try {
            if (cmain.grammar == null) {
                info.detail("Parsing input with the ixml specification grammar.");
                parser = InvisibleXml.invisibleXmlParser();
            } else  {
                if (cmain.grammar.endsWith(".xml")) {
                    info.detail("Loading vxml grammar: " + cmain.grammar);
                    parser = InvisibleXml.parserFromVxml(cmain.grammar);
                } else if (cmain.grammar.endsWith(".cxml")) {
                    info.detail("Loading compiled grammar: " + cmain.grammar);
                    parser = InvisibleXmlParser.loadCompiledGrammar(new File(cmain.grammar));
                } else {
                    info.detail("Loading ixml grammar: " + cmain.grammar);
                    parser = InvisibleXml.parserFromFile(cmain.grammar);
                }
                if (cmain.timing) {
                    showTime(parser.getParseTime(), cmain.grammar);
                }
            }
        } catch (IOException ex) {
            System.err.println("Cannot read " + cmain.grammar);
            return 1;
        } catch (URISyntaxException ex) {
            System.err.println("Invalid URI: " + cmain.grammar);
            return 2;
        } catch (IxmlException ex) {
            System.err.println(ex.getMessage());
            return 2;
        }

        if (!parser.constructed()) {
            InvisibleXmlDocument doc = parser.getFailedParse();
            System.err.printf("Failed to parse grammar: could not match %s at line %d, column %d%n",
                    doc.getEarleyResult().getLastToken(), doc.getLineNumber(), doc.getColumnNumber());
            return 2;
        }

        if (cmain.compiledGrammar != null) {
            if ("-".equals(cmain.compiledGrammar)) {
                System.out.println(parser.getCompiledParser());
            } else {
                PrintStream ps = new PrintStream(new FileOutputStream(cmain.compiledGrammar));
                ps.println(parser.getCompiledParser());
                ps.close();
            }
        }

        parser.setOptions(options);

        if (!parser.constructed()) {
            InvisibleXmlDocument fail = parser.getFailedParse();
            System.out.println(fail.getTree());
            return 2;
        }

        InvisibleXmlDocument doc;
        if (cmain.inputFile != null) {
            info.detail("Loading input from " + cmain.inputFile);
            doc = parser.parseFromFile(cmain.inputFile);
        } else {
            info.detail("Input: " + input);
            doc = parser.parse(input);
        }

        if (cmain.timing) {
            showTime(doc.parseTime());
        }

        if (cmain.graphXml != null) {
            doc.getEarleyResult().getForest().serialize(cmain.graphXml);
        }

        if (cmain.graphSvg != null) {
            graphForest(doc.getEarleyResult(), options, cmain.graphSvg);
        }

        if (parseCount == 0 && !allparses) {
            if (doc.getNumberOfParses() > 1) {
                System.out.println("There are " + doc.getExactNumberOfParses() + " possible parses.");
                if (cmain.describeAmbiguity) {
                    Ambiguity ambiguity = doc.getEarleyResult().getForest().getAmbiguity();
                    if (ambiguity.getInfinitelyAmbiguous()) {
                        System.out.println("Infinite ambiguity:");
                    } else {
                        System.out.println("Ambiguity:");
                    }
                    if (ambiguity.getRoots().size() > 1) {
                        System.out.printf("Graph has %d roots.%n", ambiguity.getRoots().size());
                    }
                    for (ForestNode node : ambiguity.getChoices().keySet()) {
                        System.out.println(node);
                        for (Family family : ambiguity.getChoices().get(node)) {
                            System.out.println("\t" + family);
                        }
                    }
                }
            }
        }

        if (doc.getNumberOfParses() > 0 && parseCount > doc.getNumberOfParses()) {
            System.out.println("There are only " + doc.getExactNumberOfParses() + " possible parses.");
            return 1;
        }

        PrintStream output = System.out;
        if (cmain.outputFile != null) {
            output = new PrintStream(new FileOutputStream(cmain.outputFile));
        }

        if (parseCount > 0 || allparses) {
            long max = parseCount;
            if (allparses) {
                max = doc.getNumberOfParses();
            }

            output.printf("<ixml parses='%d' totalParses='%s'>%n", max, doc.getExactNumberOfParses());
            for (int pos = 0; pos < max; pos++) {
                doc.getTree(output);
                doc.nextTree();
            }
            output.println("</ixml>");
        } else {
            doc.getTree(output);

            if (cmain.treeXml != null) {
                doc.getParseTree().serialize(cmain.treeXml);
            }

            if (cmain.treeSvg != null) {
                graphTree(doc.getParseTree(), options, cmain.treeSvg);
            }
        }

        return 0;
    }

    private void usage(JCommander jc, boolean help) {
        usage(jc, help, null);
    }

    private void usage(JCommander jc, boolean help, String message) {
        if (jc != null) {
            DefaultUsageFormatter formatter = new DefaultUsageFormatter(jc);
            StringBuilder sb = new StringBuilder();
            formatter.usage(sb);
            System.err.println(sb);
            if (message != null) {
                System.err.println("\n" + message);
            }
        }
        if (help) {
            System.exit(0);
        } else {
            System.exit(1);
        }
    }

    private void graphForest(EarleyResult result, ParserOptions options, String output) {
        String stylesheet = "/org/nineml/coffeegrinder/forest2dot.xsl";
        try {
            // Get the graph as XML
            Processor processor = new Processor(false);
            ByteArrayInputStream bais = new ByteArrayInputStream(result.getForest().serialize().getBytes(StandardCharsets.UTF_8));
            DocumentBuilder builder = processor.newDocumentBuilder();
            graphXdm(builder.build(new SAXSource(new InputSource(bais))), options, stylesheet, output);
        } catch (Exception ex) {
            System.err.println("Failed to create SVG: " + ex.getMessage());
        }
    }

    private void graphTree(ParseTree result, ParserOptions options, String output) {
        String stylesheet = "/org/nineml/coffeegrinder/tree2dot.xsl";
        try {
            // Get the graph as XML
            Processor processor = new Processor(false);
            ByteArrayInputStream bais = new ByteArrayInputStream(result.serialize().getBytes(StandardCharsets.UTF_8));
            DocumentBuilder builder = processor.newDocumentBuilder();
            graphXdm(builder.build(new SAXSource(new InputSource(bais))), options, stylesheet, output);
        } catch (Exception ex) {
            System.err.println("Failed to create SVG: " + ex.getMessage());
        }
    }

    private void graphXdm(XdmNode document, ParserOptions options, String resource, String output) {
        try {
            Processor processor = document.getProcessor();

            // Transform the graph into dot
            InputStream stylesheet = getClass().getResourceAsStream(resource);
            if (stylesheet == null) {
                System.err.println("Failed to load stylesheet: " + resource);
            } else {
                XsltCompiler compiler = processor.newXsltCompiler();
                compiler.setSchemaAware(false);
                XsltExecutable exec = compiler.compile(new SAXSource(new InputSource(stylesheet)));
                XsltTransformer transformer = exec.load();
                transformer.setInitialContextNode(document);
                XdmDestination destination = new XdmDestination();
                transformer.setDestination(destination);
                transformer.transform();

                // Store the dot file somewhere
                File temp = File.createTempFile("jixp", ".dot");
                temp.deleteOnExit();
                PrintWriter dot = new PrintWriter(new FileOutputStream(temp));
                dot.println(destination.getXdmNode().getStringValue());
                dot.close();

                String[] args = new String[] { options.graphviz, "-Tsvg", temp.getAbsolutePath(), "-o", output};
                Process proc = Runtime.getRuntime().exec(args);
                proc.waitFor();
                temp.delete();

                info.detail("Wrote SVG: %s", output);
            }
        } catch (Exception ex) {
            System.err.println("Failed to write SVG: " + ex.getMessage());
        }
    }

    private void showTime(Long time) {
        showTime(time, "input");
    }

    private void showTime(Long time, String filename) {
        String prefix = "Parsed in ";
        if (filename != null) {
            prefix = "Parsed " + filename + " in ";
        }
        if (time > 1000) {
            System.out.println(prefix + time / 1000 + "s");
        } else {
            System.out.println(prefix + time  + "ms");
        }
    }

    @Parameters(separators = ":", commandDescription = "IxmlParser options")
    private static class CommandMain {
        @Parameter(names = {"-help", "-h", "--help"}, help = true, description = "Display help")
        public boolean help = false;

        @Parameter(names = {"-g", "--grammar"}, description = "The input grammar")
        public String grammar = null;

        @Parameter(names = {"--graph-xml"}, description = "Output an XML description of the forest")
        public String graphXml = null;

        @Parameter(names = {"-G", "--graph-svg"}, description = "Output an SVG graph of the forest")
        public String graphSvg = null;

        @Parameter(names = {"--tree-xml"}, description = "Output an XML description of the parse tree")
        public String treeXml = null;

        @Parameter(names = {"-T", "--tree-svg"}, description = "Output an SVG graph of the parse tree")
        public String treeSvg = null;

        @Parameter(names = {"-i", "--input"}, description = "The input")
        public String inputFile = null;

        @Parameter(names = {"-o", "--output"}, description = "The output, or stdout")
        public String outputFile = null;

        @Parameter(names = {"-c", "--compiled-grammar"}, description = "Save the compiled grammar")
        public String compiledGrammar = null;

        @Parameter(names = {"-t", "--time"}, description = "Display timing information")
        public boolean timing = false;

        @Parameter(names = {"-p", "--parse"}, description = "Select a parse, or all parses")
        public String parse = null;

        @Parameter(names = {"-pp", "--pretty-print"}, description = "Pretty-print (indent) the output")
        public boolean prettyPrint = false;

        @Parameter(names = {"-v", "--verbose"}, description = "Verbose output")
        public boolean verbose = false;

        @Parameter(names = {"-D", "--describe-ambiguity"}, description = "Verbose output")
        public boolean describeAmbiguity = false;

        @Parameter(description = "The input")
        public List<String> inputText = new ArrayList<>();
    }

    private static class Info {
        private final boolean verbose;
        public Info(boolean verbose) {
            this.verbose = verbose;
        }

        public void detail(String message, Object... params) {
            if (verbose) {
                System.out.printf(message+"%n", params);
            }
        }

        public void info(String message, Object... params) {
            System.out.printf(message+"%n", params);
        }
    }
}
