package edu.nyu.jet.ice.models;// -*- tab-width: 4 -*-
//Title:        JET-ICE
//Version:      1.50
//Copyright:    Copyright (c) 2011
//Author:       Ralph Grishman
//Description:  A Java-based Information Extraction Tool

import AceJet.*;
import Jet.Control;
import edu.nyu.jet.ice.uicomps.Ice;
import edu.nyu.jet.ice.utils.FileNameSchema;
import edu.nyu.jet.ice.utils.IceUtils;
import edu.nyu.jet.ice.utils.ProgressMonitorI;
import Jet.JetTest;
import Jet.Lex.Stemmer;
import Jet.Parser.DepParser;
import Jet.Parser.SyntacticRelation;
import Jet.Parser.SyntacticRelationSet;
import Jet.Pat.Pat;
import Jet.Pat.PatternSet;
import Jet.Refres.Resolve;
import Jet.Tipster.Annotation;
import Jet.Tipster.Document;
import Jet.Tipster.ExternalDocument;
import Jet.Tipster.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;

/**
 *  collect a list of all dependency paths connecting two named entity mentions
 */

//  uses cache now


public class DepPaths {

    final static Logger logger = LoggerFactory.getLogger(DepPaths.class);

    static Stemmer stemmer = new Stemmer().getDefaultStemmer();

    static Map<String, Integer> relationTypeCounts = new TreeMap<String, Integer>();
    static Map<String, Integer> relationInstanceCounts = new TreeMap<String, Integer>();
    static Map<String, String> sourceDict = new TreeMap<String, String>();
    static Map<String, String> linearizationDict = new TreeMap<String, String>();

	static Map<String, String> pathRelations = new TreeMap<String, String>();

    static PrintWriter writer;
    static PrintWriter typeWriter;

    static String inputDir;
    static String inputFile;

    public static final int MAX_MENTIONS_IN_SENTENCE = 50;

    public static ProgressMonitorI progressMonitor = null;

    public static DepPathRegularizer depPathRegularizer = new DepPathRegularizer();

	public static void extractPathRelations(String inputFileList,
											String outputFile,
											String inputSuffix,
											String propsFile)  throws IOException {
		PrintWriter pw = new PrintWriter(outputFile);
		Properties props = new Properties();
		props.load(new FileReader(propsFile));
		loadPathRelations(FileNameSchema.getWD() + "data" + File.separator + props.getProperty("Ace.RelationModel.fileName"));
		for (String pathRelation : pathRelations.keySet()) {
			System.err.println(pathRelation + "->" + pathRelations.get(pathRelation));
		}
		System.err.println(props.getProperty("Ace.RelationModel.fileName"));
		JetTest.initializeFromConfig(propsFile);

		// load ACE type dictionary
		EDTtype.readTypeDict();
		// turn off traces
		Pat.trace = false;
		Resolve.trace = false;
		// ACE mode (provides additional antecedents ...)
		Resolve.ACE = true;


		String[] userDicts = IceUtils.findValuesWithPrefix(props, "Ice.UserEntities.");
//        DictionaryBasedNamedEntityPostprocessor nePostprocessor =
//                new DictionaryBasedNamedEntityPostprocessor(propsFile);

		String docName;
		int docCount = 0;

		BufferedReader docListReader = new BufferedReader(new FileReader (inputFileList));
		boolean isCanceled = false;
		while ((docName = docListReader.readLine()) != null) {
			docCount++;
			if ("*".equals(inputSuffix.trim())) {
				inputFile = docName;
			} else {
				inputFile = docName + "." + inputSuffix;
			}
			System.out.println ("DepPaths: Processing document " + docCount + ": " + inputFile);
			ExternalDocument doc = new ExternalDocument ("sgml", inputDir, inputFile);
			doc.setAllTags(true);
			doc.open();
			// process document
			Ace.monocase = Ace.allLowerCase(doc);
			// --------------- code from Ace.java
			doc.stretchAll();
			// process document
			Ace.monocase = Ace.allLowerCase(doc);
			/*
			gazetteer.setMonocase(AceJet.Ace.monocase);
			Jet.HMM.BigramHMMemitter.useBigrams = AceJet.Ace.monocase;
			Jet.HMM.HMMstate.otherPreference = AceJet.Ace.monocase ? 1.0 : 0.0;
			if (doc.annotationsOfType("dateline") == null &&
				doc.annotationsOfType("textBreak") == null)
				SpecialZoner.findSpecialZones (doc);
			*/
			Control.processDocument(doc, null, docCount == -1, docCount);
//            nePostprocessor.tagDocument(doc);
//
//            List<Annotation> sentences = doc.annotationsOfType("sentence");
//            if (sentences != null) {
//                for (Annotation sentence : sentences) {
//                    Resolve.references(doc, sentence.span());
//                }
//            }

			Ace.tagReciprocalRelations(doc);
			String docId = Ace.getDocId(doc);
			if (docId == null)
				docId = docName;
			// create empty Ace document
			String sourceType = "text";
			AceDocument aceDoc =
					new AceDocument(inputFile, sourceType, docId, doc.text());
			// build entities
			Ace.buildAceEntities (doc, docId, aceDoc);
			// ---------------
			// invoke parser on 'doc' and accumulate counts
			SyntacticRelationSet relations = null;
			try {
				relations = DepParser.parseDocument(doc);
			}
			catch (Exception e) {
				e.printStackTrace();
			}
			if (relations == null) {
				continue;
			}
			relations.addInverses();
			findPathRelations(doc, aceDoc, relations, pw);
		}
		pw.close();
	}

	public static void loadPathRelations(String fileName) throws IOException {
		String line = null;
		pathRelations.clear();
		BufferedReader br = new BufferedReader(new FileReader(fileName));
		while ((line = br.readLine()) != null) {
			String[] parts = line.split("\t");
			pathRelations.put(parts[0], parts[1]);
		}
		br.close();
	}

	public static void findPathRelations(Document doc,
										 AceDocument aceDoc,
										 SyntacticRelationSet relations,
										 PrintWriter pw) {

		List<Annotation> jetSentences = doc.annotationsOfType("sentence");
		if (jetSentences == null) return;
		Set<AceEntityMention> allMentions = new HashSet<AceEntityMention>();
		for (AceEntity entity : aceDoc.entities) {
			for (AceEntityMention mention : entity.mentions) {
				if (mention.type.startsWith("PRO")) continue;
				allMentions.add(mention);
			}
		}

		int sentCount = 0;
		for (Annotation sentence : jetSentences) {
			System.err.println(doc.text(sentence));
			sentCount++;

			List<AceEntityMention> localMentions = new ArrayList<AceEntityMention>();
			for (AceEntityMention mention : allMentions) {
				if (mentionInSentence(mention, sentence)) {
					localMentions.add(mention);
//                    System.err.println("Mention text:" + mention.text);
				}
			}
//            System.err.println("Total mentions in sentence:" + localMentions.size());
			if (localMentions.size() > MAX_MENTIONS_IN_SENTENCE) {
				System.err.println("Too many mentions in one sentence. Skipped.");
				continue;
			}
			for (AceEntityMention mention1 : localMentions) {
				for (AceEntityMention mention2 : localMentions) {
					if (mention1.entity == mention2.entity) continue;
//                    if (mention1.type.startsWith("PRO")) continue;
//                    if (mention2.type.startsWith("PRO")) continue;
					int h1 = mention1.getJetHead().start();
					int h2 = mention2.getJetHead().start();
					// - mention1 precedes mention2
					if (h1 >= h2) continue;
					// - in same sentence
//                    if (!sentences.inSameSentence(h1, h2)) continue;
					String path =
							EventSyntacticPattern.buildSyntacticPath(h1, h2, relations, localMentions);
					if (path != null) {
						path = AnchoredPath.lemmatizePath(path);
					}
					String key = mention1.entity.type + "--" + path + "--" + mention2.entity.type;
					System.err.println(doc.text(mention1.jetExtent));
					System.err.println(doc.text(mention2.jetExtent));
					System.err.println(key);
					if (pathRelations.containsKey(key)) {
						pw.println(pathRelations.get(key) + "\t" +
								doc.text(sentence).replaceAll("\\s+", " ").trim());
					}
				}
			}
		}
	}

    /**
     *  counts the number of instances of each dependency triple in a set
     *  of files.  Invoked by <br>
     *  DepCounter  propsFile docList inputDir inputSuffix outputFile
     *
     *    propsFile     Jet properties file
     *    docList       file containing list of documents to be processed, 1 per line
     *    inputDir      directory containing files to be processed
     *    inputSuffix   file extension to be added to document name to obtain name of input file
     *    outputFile    file to contain counts of dependency relations
     *    typeOutputFile    file to contain counts of dependency relations
     */

    public static void main (String[] args) throws IOException {
		relationTypeCounts.clear();
		relationInstanceCounts.clear();
		sourceDict.clear();
        linearizationDict.clear();

		if (args.length != 8) {
			System.err.println ("DepCounter requires 7 arguments:");
			System.err.println ("  propsFile docList inputDir inputSuffix outputFile cacheDir");
			System.exit (1);
		}
		String propsFile = args[0];
		String docList = args[1];
		inputDir = args[2];
		String inputSuffix = args[3];
		String outputFile = args[4];
		String typeOutputFile = args[5];
        String sourceDictFile = args[6];
		String cacheDir = args[7];

		// initialize Jet

		System.out.println("Starting Jet DepCounter ...");
		JetTest.initializeFromConfig(propsFile);
        PatternSet patternSet = IcePreprocessor.loadPatternSet(
		   FileNameSchema.getWD() +  JetTest.getConfig("Jet.dataPath") + File.separator +
                        JetTest.getConfig("Pattern.quantifierFileName"));
		// load ACE type dictionary
		EDTtype.readTypeDict();
		// turn off traces
		Pat.trace = false;
		Resolve.trace = false;
		// ACE mode (provides additional antecedents ...)
		Resolve.ACE = true;
        Properties props = new Properties();
		FileReader propsReader = new FileReader(propsFile);
		props.load(propsReader);
		propsReader.close();

		String docName;
		int docCount = 0;

		FileReader docListFileReader = new FileReader (docList);
		BufferedReader docListReader = new BufferedReader(docListFileReader);
        boolean isCanceled = false;
		while ((docName = docListReader.readLine()) != null) {
			docCount++;
            if ("*".equals(inputSuffix.trim())) {
                inputFile = docName;
            } else {
    			inputFile = docName + "." + inputSuffix;
            }
			System.out.println ("\nProcessing document " + docCount + ": " + inputFile);
			ExternalDocument doc = new ExternalDocument ("sgml", inputDir, inputFile);
			doc.setAllTags(true);
			doc.open();
			// process document
			Ace.monocase = Ace.allLowerCase(doc);
			// --------------- code from Ace.java
			doc.stretchAll();
			// process document
			Ace.monocase = Ace.allLowerCase(doc);
			Control.processDocument(doc, null, docCount == -1, docCount);

            // Ace.tagReciprocalRelations(doc);
//			String docId = Ace.getDocId(doc);
//			if (docId == null)
//				docId = docName;
//			// create empty Ace document
//			String sourceType = "text";
//			AceDocument aceDoc =
//				new AceDocument(inputFile, sourceType, docId, doc.text());
			// build entities
//			Ace.buildAceEntities (doc, docId, aceDoc);
			// ---------------
			// invoke parser on 'doc' and accumulate counts

			// ABG TODO: load this in args
//			AceDocument aceDoc = new AceDocument(inputFile,
//					IcePreprocessor.cacheFileName(cacheDir, inputDir, inputFile) + ".ace");
//			Map<String, Span> mentionSpanMap = IcePreprocessor.loadJetExtents(
//					cacheDir, inputDir, inputFile);
			SyntacticRelationSet relations = IcePreprocessor.loadSyntacticRelationSet(
					cacheDir, inputDir, inputFile
			);
			relations.addInverses();
			// IcePreprocessor.loadENAMEX(doc, cacheDir, inputDir, inputFile, patternSet);
            IcePreprocessor.loadENAMEX(doc, cacheDir, inputDir, inputFile);
			IcePreprocessor.loadAdditionalMentions(doc, cacheDir, inputDir, inputFile);
            collectPaths(doc, relations);
			if (progressMonitor != null) {
				progressMonitor.setProgress(docCount);
				progressMonitor.setNote(docCount + " files processed");
                if (progressMonitor.isCanceled()) {
                    isCanceled = true;
                    System.err.println("Relation path collection canceled.");
                    break;
                }
			}
		}
		docListReader.close();
		docListFileReader.close();
		// *** write counts
        if (!isCanceled) {
            writer = new PrintWriter (new FileWriter (outputFile));
            typeWriter = new PrintWriter (new FileWriter (typeOutputFile));
            String relationReprFile = outputFile.substring(0, outputFile.length()-1) + "Repr";
            PrintWriter relationReprWriter = new PrintWriter(new FileWriter(relationReprFile));
            PrintWriter sourceDictWriter = new PrintWriter(new FileWriter(sourceDictFile));
            for (String r : relationInstanceCounts.keySet()) {
                writer.println (relationInstanceCounts.get(r) + "\t" + r);
            }
            for (String r : relationTypeCounts.keySet()) {
                typeWriter.println (relationTypeCounts.get(r) + "\t" + r);
                sourceDictWriter.println(relationTypeCounts.get(r) + "\t" + r + " ||| " + sourceDict.get(r));
                relationReprWriter.println(r + ":::" + linearizationDict.get(r) + ":::" + sourceDict.get(r));
            }
            writer.close();
            typeWriter.close();
            relationReprWriter.close();
            sourceDictWriter.close();

        }
	}

	/*Partition POS tag into four types*/
	static String normalizePos(String pos){
		if (pos.toLowerCase().startsWith("nnp"))
			return "nnp";
		if (pos.toLowerCase().startsWith("nn"))
			return "nn";
		if (pos.toLowerCase().startsWith("vb"))
			return "vb";
		return "o";
	}
	
	static String normalizeWord(String word,String pos){
		String np=word.replaceAll("[ \t]+", "_");
		String ret = "";
		String[] tmp;
		tmp = np.split("_ \t");
		
		if(tmp.length==0)
			return "";
		
		if(tmp.length == 1)
			ret = stemmer.getStem(tmp[0].toLowerCase(), pos.toLowerCase());
		else {
			for(int i=0; i<tmp.length-1; i++) {
				ret += stemmer.getStem(tmp[i].toLowerCase(), pos.toLowerCase()) + "_";
			}
			ret += stemmer.getStem(tmp[tmp.length-1].toLowerCase(), pos.toLowerCase());
		}
		//System.out.println("[stemmer] " + ret);
		return ret;
	}

    static void collectPaths (Document doc,
							  SyntacticRelationSet relations) {

        List<Annotation> jetSentences = doc.annotationsOfType("sentence");
        if (jetSentences == null) return;
		List<Annotation> names = doc.annotationsOfType("ENAMEX");
		if (names == null) {
			return;
		}
        int sentCount = 0;
        for (Annotation sentence : jetSentences) {
            sentCount++;

            List<Annotation> localNames = new ArrayList<Annotation>();
			List<Span> localHeadSpans   = new ArrayList<Span>();
            for (Annotation name : names) {
                if (annotationInSentence(name, sentence)) {
                    localNames.add(name);
					localHeadSpans.add(
							IcePreprocessor.findTermHead(doc, name, relations).span());
                }
            }
            if (localNames.size() > MAX_MENTIONS_IN_SENTENCE) {
                System.err.println("Too many mentions in one sentence. Skipped.");
                continue;
            }
            for (int i = 0; i < localNames.size(); i++) {
                for (int j = 0; j < localNames.size(); j++) {
                    if (i == j) continue;
                    int h1 = localHeadSpans.get(i).start();
                    int h2 = localHeadSpans.get(j).start();
                    // - mention1 precedes mention2
                    if (h1 >= h2) continue;
                    // - in same sentence
//                    if (!sentences.inSameSentence(h1, h2)) continue;
                    // find and record dep path from head of m1 to head of m2
                    DepPath path = buildSyntacticPathOnSpans(h1, h2, relations, localHeadSpans);
                    recordPath(doc, sentence, relations, localNames.get(i), path, localNames.get(j));
                }
            }
        }
    }

    private static boolean mentionInSentence(AceEntityMention mention, Annotation sentence) {
        return mention.jetExtent.start() >= sentence.start() &&
                mention.jetExtent.end() <= sentence.end();
    }

	public static boolean annotationInSentence(Annotation mention, Annotation sentence) {
		return mention.start() >= sentence.start() &&
				mention.end() <= sentence.end();
	}
	
	static void recordPath (Document doc, Annotation sentence, SyntacticRelationSet relations,
		                AceEntityMention mention1, String path, AceEntityMention mention2) {
		if (path == null) return;
		int pathLength = 0;
		for (int i=0; i < path.length(); i++)
			if (path.charAt(i) == ':')
				pathLength++;
		if (pathLength > 5) return;
		String m1 = mention1.getHeadText();
		String m2 = mention2.getHeadText();
		String mm1 = m1.replaceAll(" ", "_").replaceAll("\n", "_");
		String mm2 = m2.replaceAll(" ", "_").replaceAll("\n", "_");
		// String source = inputDir + "/" + inputFile + " | " + start + " | " + end;
		String source = pathText(doc, sentence, mention1, mention2);

		count (relationInstanceCounts, mm1 + " -- " + path + " -- " + mm2);

		String type1 = mention1.entity.type;
		String type2 = mention2.entity.type;
		count (relationTypeCounts, type1 + " -- " + path + " -- " + type2);
        if (!sourceDict.containsKey(type1 + " -- " + path + " -- " + type2)) {
            sourceDict.put(type1 + " -- " + path + " -- " + type2, source);
        }
	}

	static void recordPath (Document doc, Annotation sentence, SyntacticRelationSet relations,
							Annotation mention1, String path, Annotation mention2) {
		if (path == null) return;
		int pathLength = 0;
		for (int i=0; i < path.length(); i++)
			if (path.charAt(i) == ':')
				pathLength++;
		if (pathLength > 5) return;
		String m1 = doc.text(mention1).replaceAll("\\s+", " ").trim();
		String m2 = doc.text(mention2).replaceAll("\\s+", " ").trim();

		// String source = inputDir + "/" + inputFile + " | " + start + " | " + end;
		String source = pathText(doc, sentence, mention1, mention2);

		count (relationInstanceCounts, m1 + " -- " + path + " -- " + m2);

		String type1 = mention1.get("TYPE") != null ? (String)mention1.get("TYPE") : "OTHER";
		String type2 = mention2.get("TYPE") != null ? (String)mention2.get("TYPE") : "OTHER";
		count (relationTypeCounts, type1 + " -- " + path + " -- " + type2);
		if (!sourceDict.containsKey(type1 + " -- " + path + " -- " + type2)) {
			sourceDict.put(type1 + " -- " + path + " -- " + type2, source);
		}
	}

    static void recordPath (Document doc, Annotation sentence, SyntacticRelationSet relations,
                            Annotation mention1, DepPath path, Annotation mention2) {

        if (path == null) return;
        DepPath regularizedPath = depPathRegularizer.regularize(path);

        if (regularizedPath.length() > 5) {
            return;
        }
        String m1 = doc.text(mention1).replaceAll("\\s+", " ").trim();
        String m1Val = m1;
        if (mention1.get("mType") != null &&
                mention1.get("mType").equals("PRO")) {
            m1Val = ((String)mention1.get("val")).replaceAll("\\s+", " ").trim();
        }
        String m2 = doc.text(mention2).replaceAll("\\s+", " ").trim();
        String m2Val = m2;
        if (mention2.get("mType") != null &&
                mention2.get("mType").equals("PRO")) {
            m2Val = ((String)mention2.get("val")).replaceAll("\\s+", " ").trim();
        }
        // String source = inputDir + "/" + inputFile + " | " + start + " | " + end;
        String source = pathText(doc, sentence, mention1, mention2);

        count (relationInstanceCounts, m1Val + " -- " + regularizedPath + " -- " + m2Val);

        String type1 = mention1.get("TYPE") != null ? (String)mention1.get("TYPE") : "OTHER";
        String type2 = mention2.get("TYPE") != null ? (String)mention2.get("TYPE") : "OTHER";
        String fullPath = type1 + " -- " + regularizedPath + " -- " + type2;
        count (relationTypeCounts, fullPath);
        if (!sourceDict.containsKey(fullPath)) {
            sourceDict.put(fullPath, source);
        }
        String linearizedPath = regularizedPath.linearize(doc, relations, type1, type2);
        if (!linearizationDict.containsKey(fullPath)) {
            linearizationDict.put(fullPath, linearizedPath);
        }
    }

    /**
     *
     *  returns the syntactic path from the anchor to an argument. path is not allowed if
     *  one of localMentions is on the path (but not at the beginning or end of the path)
     */
    public static DepPath buildSyntacticPathOnSpans
    (int fromPosn, int toPosn, SyntacticRelationSet relations, List<Span> localSpans) {
        Map<Integer, DepPath> path = new HashMap<Integer, DepPath>();
        DepPath p = new DepPath(fromPosn, toPosn);
        int variable = 0;
        LinkedList<Integer> todo = new LinkedList<Integer>();
        todo.add(new Integer(fromPosn));
        path.put(new Integer(fromPosn), p);

        while (todo.size() > 0) {
            Integer from = todo.removeFirst();
            logger.trace ("from = " + from);
            SyntacticRelationSet fromSet = relations.getRelationsFrom(from.intValue());
            logger.trace ("fromSet = " + fromSet);
            for (int ifrom = 0; ifrom < fromSet.size(); ifrom++) {
                SyntacticRelation r = fromSet.get(ifrom);
                Integer to = new Integer(r.targetPosn);
                // avoid loops
                if (path.get(to) != null) continue;
                // disallow mentions
                if (to.intValue() != toPosn &&
                        IceUtils.matchSpan(to, localSpans)) {
                    continue;
                }
                logger.trace ("to = " + to);
                // if 'to' is target
                if (to.intValue() == toPosn) {
                    logger.trace ("TO is an argument");
                    return path.get(from).extend(r);
                    // return (path.get(from) + ":" + r.type).substring(1);
                } else {
                    // 'to' is another node
                    path.put(to, path.get(from).extend(r));
                    // path.put(to, path.get(from) + ":" + r.type + ":" + r.targetWord);
                }
                todo.add(to);
            }
        }
        return null;
    }


	static void count (Map<String, Integer> map, String s) {
		Integer n = map.get(s);
		if (n == null) n = 0;
		map.put(s, n + 1);
	}


	static String pathText (Document doc, Annotation sentence,
		                AceEntityMention mention1, AceEntityMention mention2) {
		int head1start = mention1.getJetHead().start();
		int head1end = mention1.getJetHead().end();
		int head2start = mention2.getJetHead().start();
		int head2end = mention2.getJetHead().end();
		int start = sentence.start();  //mention1.jetExtent.start();
		int end = sentence.end();  //mention2.jetExtent.end();
		String text = "";
		if (start < head1start) text += doc.normalizedText(new Span(start, head1start));
		text += " [";
		text += doc.normalizedText(new Span(head1start, head1end));
		text += "] ";
		if (head1end < head2start) text += doc.normalizedText(new Span(head1end, head2start));
		text += " [";
		text += doc.normalizedText(new Span(head2start, head2end));
		text += "] ";
		if (head2end < end) text += doc.normalizedText(new Span(head2end, end));
		return text.trim();
	}


	static String pathText (Document doc, Annotation sentence,
							Annotation mention1, Annotation mention2) {
		int head1start = mention1.start();
		int head1end = mention1.end();
		int head2start = mention2.start();
		int head2end = mention2.end();
		int start = sentence.start();  //mention1.jetExtent.start();
		int end = sentence.end();  //mention2.jetExtent.end();
		int posn = head1start;
		String text = "";
		if (start < head1start) text += doc.normalizedText(new Span(start, head1start));
		text += " [";
		text += doc.normalizedText(new Span(head1start, head1end));
		text += "] ";
		if (head1end < head2start) text += doc.normalizedText(new Span(head1end, head2start));
		text += " [";
		text += doc.normalizedText(new Span(head2start, head2end));
		text += "] ";
		if (head2end < end) text += doc.normalizedText(new Span(head2end, end));
		return text.trim();
	}


}
