package edu.nyu.jet.ice.models;

import Jet.Lex.Stemmer;
import Jet.Parser.SyntacticRelation;

import java.util.HashSet;
import java.util.Set;

/**
 * Regularizer for DepPath
 *
 * @author yhe
 * @version 1.0
 */
public class DepPathRegularizer {

    public Set<String> quantifiers = new HashSet<String>();

    private Stemmer stemmer = Stemmer.getDefaultStemmer();

    {
        quantifiers.add("ounce");
        quantifiers.add("gram");
        quantifiers.add("kilogram");
        quantifiers.add("quantity");
        quantifiers.add("kilo");
        quantifiers.add("pound");
        quantifiers.add("amount");
    }

    public DepPath regularize(DepPath p) {
        DepPath result =  new DepPath(p.start, p.end, p.arg1, p.arg2);

        SyntacticRelation prevRelation = null;
        for (SyntacticRelation r : p.relations) {
            // prep_of: when using transformation
            // prep: when not using transformation
            if ((r.type.equals("prep_of") || r.type.equals("prep")) &&
                    quantifiers.contains(
                            stemmer.getStem(r.sourceWord.trim().toLowerCase(), "NN")) &&
                    prevRelation != null) {
                prevRelation.targetPos = r.targetPos;
                prevRelation.targetPosn = r.targetPosn;
                prevRelation.targetWord = r.targetWord;
            }
            else {
                if (prevRelation != null) {
                    result.append(prevRelation);
                }
                if ((r.type.equals("prep_of-1") || r.type.equals("prep-1")) &&
                        quantifiers.contains(
                                stemmer.getStem(r.targetWord.trim().toLowerCase(), "NN"))) {
                    prevRelation = null;
                }
                else {
                    prevRelation = r;
                }
            }
        }
        if (prevRelation != null) {
            result.append(prevRelation);
        }
        return result;
    }

    public String regularize(String p) {
        String result = p;
        for (String w : quantifiers) {
            result = result.replaceAll(":" + w + ":prep_of", "");
        }
        result = result.replaceAll("rcmod:\\d+:", "");
        if (!p.equals(result)) {
//            System.err.println("Before regularization:" + p);
//            System.err.println("After  regularization:" + result);
        }
        return result;
    }

}
