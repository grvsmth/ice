package edu.nyu.jet.ice.relation;

import AceJet.AceDocument;
import AceJet.AceEntityMention;
import AceJet.AceRelationMention;
import AceJet.EventSyntacticPattern;
import Jet.Parser.SyntacticRelationSet;
import Jet.Tipster.Annotation;
import Jet.Tipster.Document;
import opennlp.model.Event;

import java.util.List;

/**
 * A Feature extractor using dependency path features for supervised/simulated active learning relation extraction.
 *
 * This class is not used by ICE GUI/CLI.
 */
public class DepPathFeatureExtractor implements RelationFeatureExtractor {
    public Event extractFeatures(AceEntityMention m1,
                                 AceEntityMention m2,
                                 AceRelationMention r,
                                 Annotation sentence,
                                 SyntacticRelationSet paths,
                                 List<AceEntityMention> mentions,
                                 AceDocument aceDoc,
                                 Document doc) {
        String label = r == null ? "NONE" : r.relation.type;
        int h1 = m1.getJetHead().start();
        int h2 = m2.getJetHead().start();
        if (h1 >= h2) {
            int tmp = h1;
            h1 = h2;
            h2 = tmp;
        }
        String path = EventSyntacticPattern.buildSyntacticPath(h1, h2, paths);
        path = path == null ? "EMPTY" : path.replaceAll("\\s+", "_");
        String type1 = m1.entity.type;
        String type2 = m2.entity.type;
        String concatTypes = type1 + ":::" + type2;
        String concatAll = type1 + ":::" + path + ":::" + type2;
        return new Event(label, new String[]{path});
    }
}
