package edu.nyu.jet.ice.views.cli;

import edu.nyu.jet.ice.controllers.Nice;
import edu.nyu.jet.ice.entityset.EntitySetIndexer;
import edu.nyu.jet.ice.models.Corpus;
import edu.nyu.jet.ice.models.IcePreprocessor;
import edu.nyu.jet.ice.models.RelationFinder;
import edu.nyu.jet.ice.uicomps.Ice;
import edu.nyu.jet.ice.utils.FileNameSchema;
import edu.nyu.jet.ice.views.swing.SwingEntitiesPanel;
import org.apache.commons.cli.*;

import java.io.File;
import java.util.Properties;


/**
 * Command line interface for running Ice processing tasks.
 *
 * @author yhe
 * @version 1.0
 */
public class IceCLI {

    public static void main(String[] args) {
        // create Options object
        Options options = new Options();
        Option inputDir = Option.builder().longOpt("inputDir").hasArg().argName("inputDirName")
                .desc("Location of new corpus").build();
        Option background = Option.builder().longOpt("background").hasArg().argName("backgroundCorpusName")
                .desc("Name of the background corpus").build();
        Option filter = Option.builder().longOpt("filter").hasArg().argName("filterFileExtension")
                .desc("File extension to process: sgm, txt, etc.").build();
        Option entityIndexCutoff = Option.builder().longOpt("entityIndexCutoff").hasArg().argName("cutoff")
                .desc("Cutoff of entity index: 1.0-25.0").build();
        options.addOption(inputDir);
        options.addOption(background);
        options.addOption(filter);
        options.addOption(entityIndexCutoff);

        CommandLineParser parser = new DefaultParser();
        try {
            CommandLine cmd = parser.parse(options, args);
            String[] arguments = cmd.getArgs();
            if (arguments.length != 2) {
                System.err.println("Must provide exactly 2 arguments: ACTION CORPUS");
                printHelp(options);
                System.exit(-1);
            }
            String action  = arguments[0];
            String corpusName  = arguments[1];

            if (action.equals("addCorpus")) {
                String inputDirName = cmd.getOptionValue("inputDir");
                if (inputDirName == null) {
                    System.err.println("--inputDir must be set for the preprocess action.");
                    printHelp(options);
                    System.exit(-1);
                }
                String filterName = cmd.getOptionValue("filter");
                if (filterName == null) {
                    System.err.println("--filter must be set for the preprocess action.");
                    printHelp(options);
                    System.exit(-1);
                }
                File inputDirFile = new File(inputDirName);
                if (inputDirFile.exists() && inputDirFile.isDirectory()) {
                    init();
                    if (Ice.corpora.containsKey(corpusName)) {
                        System.err.println("Name of corpus already exist. Please choose another name.");
                        System.exit(-1);
                    }

                    String backgroundCorpusName = cmd.getOptionValue("background");
                    if (backgroundCorpusName != null) {
                        if (!Ice.corpora.containsKey(backgroundCorpusName)) {
                            System.err.println("Cannot find background corpus. Use one of the current corpora as background:");
                            printCorporaListExcept(corpusName);
                            System.exit(-1);
                        }
                    }

                    Corpus newCorpus = new Corpus(corpusName);
                    if (backgroundCorpusName != null) {
                        newCorpus.setBackgroundCorpus(backgroundCorpusName);
                    }
                    Ice.corpora.put(corpusName, newCorpus);
                    Ice.selectCorpus(corpusName);
                    Ice.selectedCorpus.setDirectory(inputDirName);
                    Ice.selectedCorpus.setFilter(filterName);
                    Ice.selectedCorpus.writeDocumentList();
                    if (Ice.selectedCorpus.docListFileName == null) {
                        System.err.println("Unable to find any file that satisfies the filter.");
                        System.exit(-1);
                    }
                    IcePreprocessor icePreprocessor = new IcePreprocessor(
                            Ice.selectedCorpus.directory,
                            Ice.iceProperties.getProperty("Ice.IcePreprocessor.parseprops"),
                            Ice.selectedCorpus.docListFileName,
                            filterName,
                            FileNameSchema.getPreprocessCacheDir(Ice.selectedCorpusName)
                    );
                    icePreprocessor.run();
                    saveStatus();
                    if (backgroundCorpusName == null) {
                        System.err.println("[WARNING]\tBackground corpus is not set.");
                    }
                    System.err.println("Corpus added successfully.");
                }
                else {
                    System.err.println("--inputDir should specify a valid directory.");
                    printHelp(options);
                    System.exit(-1);
                }

            }
            else if (action.equals("setBackgroundFor")) {
                init();
                validateCorpus(corpusName);
                String backgroundCorpusName = cmd.getOptionValue("background");
                validateBackgroundCorpus(corpusName, backgroundCorpusName);
                Ice.selectCorpus(corpusName);
                Ice.selectedCorpus.backgroundCorpus = backgroundCorpusName;
                saveStatus();
                System.err.println("Background corpus set successfully.");
            }
            else if (action.equals("findEntities")) {
                init();
                validateCorpus(corpusName);
                Ice.selectCorpus(corpusName);
                validateCurrentBackground();
                SwingEntitiesPanel entitiesPanel = new SwingEntitiesPanel();
                entitiesPanel.findTerms();
                Ice.selectedCorpus.setTermFileName(FileNameSchema.getTermsFileName(Ice.selectedCorpus.getName()));
                saveStatus();
                System.err.println("Entities extracted successfully.");
            }
            else if (action.equals("indexEntities")) {
                init();
                validateCorpus(corpusName);
                Ice.selectCorpus(corpusName);
                Corpus selectedCorpus = Ice.selectedCorpus;
                String termFileName = FileNameSchema.getTermsFileName(selectedCorpus.getName());
                File termFile = new File(termFileName);
                if (!termFile.exists() || ! termFile.isFile()) {
                    System.err.println("Entities file does not exist. Please use findEntities to generate entities file first.");
                    System.exit(-1);
                }
                String cutoff = "3.0";
                String userCutoff = cmd.getOptionValue("entityIndexCutoff");
                if (userCutoff != null) {
                    try {
                        double cutOffVal = Double.valueOf(userCutoff);
                        if (cutOffVal < 1.0 || cutOffVal > 25.0) {
                            System.err.println("Please specify an entityIndexCutoff value between 1.0 and 25.0.");
                            System.exit(-1);
                        }
                    }
                    catch (Exception e) {
                        System.err.println(userCutoff + " is not a valid value of entityIndexCutoff. Please use a number between 1.0 and 25.0.");
                        System.exit(-1);
                    }
                    cutoff = userCutoff;
                }
                else {
                    System.err.println("Using default cutoff: " + cutoff);
                }
                EntitySetIndexer.main(new String[]{FileNameSchema.getTermsFileName(selectedCorpus.getName()),
                        "nn",
                        String.valueOf(cutoff),
                        "onomaprops",
                        selectedCorpus.getDocListFileName(),
                        selectedCorpus.getDirectory(),
                        selectedCorpus.getFilter(),
                        FileNameSchema.getEntitySetIndexFileName(selectedCorpus.getName(), "nn")});
                System.err.println("Entities index successfully. Please use ICE GUI to build entity sets.");

            }
            else if (action.equals("findPhrases")) {
                init();
                validateCorpus(corpusName);
                Ice.selectCorpus(corpusName);
                // validateCurrentBackground();
                RelationFinder finder = new RelationFinder(
                        Ice.selectedCorpus.docListFileName, Ice.selectedCorpus.directory,
                        Ice.selectedCorpus.filter, FileNameSchema.getRelationsFileName(Ice.selectedCorpusName),
                        FileNameSchema.getRelationTypesFileName(Ice.selectedCorpusName), null,
                        Ice.selectedCorpus.numberOfDocs,
                        null);
                finder.run();
                Ice.selectedCorpus.relationTypeFileName =
                        FileNameSchema.getRelationTypesFileName(Ice.selectedCorpus.name);
                Ice.selectedCorpus.relationInstanceFileName =
                        FileNameSchema.getRelationsFileName(Ice.selectedCorpusName);
                if (Ice.selectedCorpus.backgroundCorpus != null) {
                    System.err.println("Generating path ratio file by comparing phrases in background corpus.");
                    Corpus.rankRelations(Ice.selectedCorpus.backgroundCorpus,
                            FileNameSchema.getPatternRatioFileName(Ice.selectedCorpusName,
                                    Ice.selectedCorpus.backgroundCorpus));
                }
                else {
                    System.err.println("Background corpus is not selected, so pattern ratio file is not generated. " +
                            "Use setBackgroundFor to set the background corpus.");
                }
                saveStatus();
                System.err.println("Phrases extracted successfully.");
            }
            else {
                System.err.println("Invalid action: " + action);
                printHelp(options);
                System.exit(-1);
            }
        }
        catch (MissingOptionException e) {
            System.err.println(e.getMessage());
            printHelp(options);
            System.exit(-1);
        }
        catch (ParseException e) {
            System.err.println(e.getMessage());
            printHelp(options);
            System.exit(-1);
        }
    }

    private static void validateCurrentBackground() {
        if (Ice.selectedCorpus.backgroundCorpus == null) {
            System.err.println("Background corpus is not set yet. Please use setBackground to pick a background corpus.");
            System.exit(-1);
        }
    }

    private static void validateCorpus(String corpusName) {
        if (!Ice.corpora.containsKey(corpusName)) {
            System.err.println("corpusName does not exist. Please pick a corpus from the list below:");
            printCorporaList();
            System.exit(-1);
        }
    }

    private static void validateBackgroundCorpus(String corpusName, String backgroundCorpusName) {
        if (backgroundCorpusName != null) {
            if (!Ice.corpora.containsKey(backgroundCorpusName)) {
                System.err.println("Cannot find background corpus. Use one of the current corpora as background:");
                printCorporaListExcept(corpusName);
                System.exit(-1);
            }
            else if (corpusName.equals(backgroundCorpusName)) {
                System.err.println("Foreground and background corpus should not be the same.");
                System.exit(-1);
            }
        }
        else {
            System.err.println("--background must be set for the selected action.");
            System.exit(-1);
        }
    }

    private static void saveStatus() {
        try {
            Nice.saveStatus();
        }
        catch (Exception e) {
            System.err.println("Unable to save status. Please check if the ice config file is writable.");
            System.err.println(-1);
        }
    }

    private static void printCorporaList() {
        for (String key : Ice.corpora.keySet()) {
            System.err.println("\t" + key);
        }
    }

    private static void printCorporaListExcept(String corpusName) {
        for (String key : Ice.corpora.keySet()) {
            if (!key.equals(corpusName)) {
                System.err.println("\t" + key);
            }
        }
    }

    public static void init() {
        Nice.printCover();
        Properties iceProperties = Nice.loadIceProperties();
        Nice.initIce();
        //Nice.loadPathMatcher(iceProperties);
    }

    private static void printHelp(Options options) {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("IceCLI ACTION CORPUS [OPTIONS]\n" +
                        "ACTION=addCorpus|setBackgroundFor|findEntities|indexEntities|findPhrases",
                options);
    }

}