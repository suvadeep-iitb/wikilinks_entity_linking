package el;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.TreeMap;
import java.util.Vector;

import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.process.CoreLabelTokenFactory;
import edu.stanford.nlp.process.PTBTokenizer;
import edu.stanford.nlp.process.WordToSentenceProcessor;

import el.context_probs.LoadContextProbs;
import el.crosswikis.LoadCrosswikisDict;
import el.crosswikis.LoadCrosswikisInvdict;
import el.entity_existence_probs.DummyIntPair;
import el.entity_existence_probs.LoadExistenceCrosswikisProbs;
import el.entity_existence_probs.LoadKeyphrasenessDummyProbs;
import el.input_data_pipeline.*;
import el.utils.Utils;
import el.wikilinks_ents_or_names_with_freqs.LoadWikilinksEntsOrNamesWithFreqs;

public class GenCandidateEntityNamePairs {
    /////////////////////////////// Fields ////////////////////////////////////////////////////////////

    // invdict: P(n|e)
    // invdict[url] = treemap<name, cprob>
    private static HashMap<String, TreeMap<String, Double>> invdict = null;

    // dict: P(e|n)
    // dict [name] = treemap<url, cprob>
    private static HashMap<String, TreeMap<String, Double>> dict = null;

    // Insert a dummy entity to represent all names that do not refer to a real Wikipedia entity.
    // Compute the p(M.ent = dummy | M.name = n) from doc frequencies (see the generation file).
    // This will augment the actual P(e|n) probabilities that we have from Crosswiki corpus which
    // actually represent p(M.ent = e | M.name = n, M.ent != dummy) instead of p(M.ent = e | M.name = n)
    private static HashMap<String, DummyIntPair> dummyProbabilities = null;

    // all Wikipedia entities (excluding redirects)
    // entsDocFreqsInCorpus[url] = doc_frequency
    private static HashMap<String, Integer> entsDocFreqsInCorpus = null;

    // P(context | n,e) mapping. Key = e + "\t" + n + "\t" + context; val = probability
    private static HashMap<String,Double> contextProbs = null;
    
    // Total number of docs from the Wikilinks corpus.
    private static int totalNumDocs = 10000000;

 
    //////////////// Methods ////////////////////////////////////////////////////////////////////////////
    
    // Get the set of all entities from all the pages by looking at the truth mentions.
    private static HashSet<String> GetAllEntitiesFromAllPages(GenericPagesIterator inputPagesIterator) {
        GenericPagesIterator pagesIterator = inputPagesIterator.hardCopy();

        HashSet<String> allEntitiesFromAllPages = new HashSet<String>();
       
        while (pagesIterator.hasNext()) {
            GenericSinglePage i = pagesIterator.next();
            for (TruthMention m : i.truthMentions) {
                allEntitiesFromAllPages.add(m.wikiUrl);
            }
        }
        
        System.err.println("[INFO] Number of all entities from all input docs = " + allEntitiesFromAllPages.size());
        
        int nrEntsNotInentsDocFreqsInCorpus = 0;
        for (String url : allEntitiesFromAllPages) {
            if (!entsDocFreqsInCorpus.containsKey(url)) {
                nrEntsNotInentsDocFreqsInCorpus++;
            }
        }
        System.err.println("[INFO] Number of all entities from all input docs that are not in the file with Entities Frequencies = " + nrEntsNotInentsDocFreqsInCorpus);

        return allEntitiesFromAllPages;
    }

    // For a given Webpage, select all candidate pairs (n,e) such that P(n|e) >= theta
    // TODO: delete HashMap<String,HashMap<String, Boolean>> debugMentionsInfos
    private static int GenAllCandidatesFromOnePage(
            GenericSinglePage doc, 
            double theta, 
            Vector<Candidate> outputCandidates, 
            HashMap<String,HashMap<String, Boolean>> debugMentionsInfos) {
                
        int nrTruthCorrectCandidates = 0;
        
        HashSet<String> setOfUrls = new HashSet<String>();
        for (TruthMention m : doc.truthMentions) {
            if (m.wikiUrl.length() > 0) {
                setOfUrls.add(m.wikiUrl);
            }
        }

        Iterator<String> it = setOfUrls.iterator();		
        while(it.hasNext()) {
            String url = it.next();

            if (!invdict.containsKey(url)) {
                continue;
            }

            Set<Entry<String,Double>> set = invdict.get(url).entrySet();
            for (Entry<String, Double> entry : set) {
                if (entry.getValue() >= theta) {
                    String name = entry.getKey();
                    
                    // Candidate names that end in a word separator are bad, because they will be matched almost for sure.
                    // Example: "gluatmine," appears in inv.dict for Glutamine
                    // TODO: something must be done here.
                    if (name.length() == 0) { // || Utils.isWordSeparator(name.charAt(0)) || Utils.isWordSeparator(name.charAt(name.length() - 1))) {
                        continue;
                    }

                    int index = doc.getRawText().indexOf(name);

                    while (index != -1) {
                        // Keep just candidates that are separate words.
                        if (index > 0 && !Utils.isWordSeparator(doc.getRawText().charAt(index-1))) {
                            index = doc.getRawText().indexOf(name, index + 1);
                            continue;
                        }
                        if (index + name.length() < doc.getRawText().length() &&
                                !Utils.isWordSeparator(doc.getRawText().charAt(index + name.length()))) {
                            index = doc.getRawText().indexOf(name, index + 1);
                            continue;
                        }
                        outputCandidates.add(
                                new Candidate(url, null, name, index, entry.getValue()));
                        index = doc.getRawText().indexOf(name, index + 1);							
                    }
                }
            }
        }
        
        // TODO FOR DEBUG ONLY:
        for (TruthMention m : doc.truthMentions) {
                
            String key = "URL=" + m.wikiUrl + ";name=" + m.anchorText + ";offset=" + m.mentionOffsetInText + ";doc=" + doc.pageName;
            debugMentionsInfos.put(key, new HashMap<String, Boolean>());
            debugMentionsInfos.get(key).put("isCandidate", true);
            debugMentionsInfos.get(key).put("EntIsInInvdict", true);
            debugMentionsInfos.get(key).put("NameIsInInvdictForEnt", true);
            debugMentionsInfos.get(key).put("EntIsInAllEnts", true);
            
            if (!invdict.containsKey(m.wikiUrl)) {
                debugMentionsInfos.get(key).put("EntIsInInvdict", false);
            }
            if (!entsDocFreqsInCorpus.containsKey(m.wikiUrl))
                debugMentionsInfos.get(key).put("EntIsInAllEnts", false);
            if (invdict.containsKey(m.wikiUrl) && !invdict.get(m.wikiUrl).containsKey(m.anchorText))
                debugMentionsInfos.get(key).put("NameIsInInvdictForEnt", false);
            
            
            boolean isNotACandidate = true;
            for (Candidate c : outputCandidates) {
                if (c.entityURL.compareTo(m.wikiUrl) == 0 && c.name.compareTo(m.anchorText) == 0 && c.textIndex == m.mentionOffsetInText) {
                    isNotACandidate = false;
                    break;
                }
            }

            if (isNotACandidate) {
                debugMentionsInfos.get(key).put("isCandidate", false);
            } else {
                nrTruthCorrectCandidates++;
            }
        }        
        
        return nrTruthCorrectCandidates;
    }
      
    
    // TODO : delete debugMEntionsInfos in the end
    private static void GenAllCandidatesFromAllPages(
            GenericPagesIterator pagesIterator,
            double theta,
            HashSet<String> allCandidateNames,
            Vector<Vector<Candidate>> allCandidates,
            HashMap<String,HashMap<String, Boolean>> debugMentionsInfos) {
        System.err.println("[INFO] Generating all candidates and their names ...");
        int nr_page = -1;
        int nrCandidates = 0;
        int nrMentions = 0;
        int nrTruthCandidates = 0;        
        
        HashSet<String> allMentionsNames = new HashSet<String>();
        while (pagesIterator.hasNext()) {
            nr_page++;
            if (nr_page % 10000 == 0) {
                System.err.println("[INFO] " + nr_page);
            }
            GenericSinglePage doc = pagesIterator.next();

            Vector<Candidate> thisPageCandidates = new Vector<Candidate>();
            
            nrTruthCandidates += GenAllCandidatesFromOnePage(doc, theta, thisPageCandidates, debugMentionsInfos);
            allCandidates.add(thisPageCandidates);
            for (Candidate cand : thisPageCandidates) {
                allCandidateNames.add(cand.name);
            }
            nrCandidates += thisPageCandidates.size();
            
            for (TruthMention m : doc.truthMentions) {
                allMentionsNames.add(m.anchorText);
            }
            nrMentions += doc.truthMentions.size();
        }
        
        System.err.println("[INFO] Done. Num all generated candidates = " + nrCandidates);
        System.err.println("[INFO] Num all candidates' names = " + allCandidateNames.size());
        System.err.println("[INFO] Num all mentions from all input docs = " + nrMentions);
        System.err.println("[INFO] Num all mentions' names = " + allMentionsNames.size());
        System.err.println("[INFO] Num truth candidates (candidates that are ground truth mentions) = " + nrTruthCandidates);
    }
        

    // Compute denominator(n,e,E) = \sum_{e' \neq e} p(e'|n) \frac{1}{p(e' \not\in E)}
    // This does not use the dummy entity.
    private static double ComputeDenominator(String name, String entity, HashSet<String> docEntities) {
        double denominator = 0.0;
        if (!dict.containsKey(name)) {
            return 0;
        }
        Set<Entry<String,Double>> set = dict.get(name).entrySet();
        for (Entry<String, Double> entry : set) {
            String url = entry.getKey();
            double cprob = entry.getValue();
            if (entity.compareTo(url) == 0) {
                continue;
            }

            // if e \in E
            if (docEntities.contains(url) && entsDocFreqsInCorpus.containsKey(url)) {
                denominator += cprob * totalNumDocs / entsDocFreqsInCorpus.get(url);
            }
            // if e \notin E
            else if (entsDocFreqsInCorpus.containsKey(url)) {
                denominator += cprob * totalNumDocs / (totalNumDocs - entsDocFreqsInCorpus.get(url));
            } else {
                denominator += cprob;						
            }
        }	
        return denominator;
    }

    // numerator(e,n,E) = p(e|n)/(p(e \in E)
    // score(candidate,E) = score(e,n,E) =  numerator(n,e,E) / (denominator(n,e,E) + p(dummy|n)/(1 - p(dummy|n))))
    // Compute also debug values for candidate: 
    //    - dummyPosteriorProb = score(dummy,n,E) = p(dummy|n) / ((1-p(dummy|n)) * (numerator(n,e,E)+denominator(n,e,E)))
    private static void ComputeSimpleScoreForOneCandidate(
            Candidate cand, 
            HashSet<String> docEntities,
            boolean withDummyEnt) {
        
        double numerator = dict.get(cand.name).get(cand.entityURL);
        numerator *= totalNumDocs / entsDocFreqsInCorpus.get(cand.entityURL);

        // Compute denominator = \sum_{e' \neq e} p(e'|n) \frac{1}{p(e' \not\in E)}
        double denominator = ComputeDenominator(cand.name, cand.entityURL, docEntities);

        // Insert dummy probabilities: p(dummy | n) / (1 - p(dummy|n))
        double dummyContributionProb = 0.0;
        
        if (withDummyEnt && dummyProbabilities.containsKey(cand.name)) {
            DummyIntPair dp = dummyProbabilities.get(cand.name);
            if (dp.numDocsWithAnchorName == 0) {
                dummyContributionProb = Double.POSITIVE_INFINITY;
            } else {
                dummyContributionProb = 
                    (dp.numDocsWithName - dp.numDocsWithAnchorName + 0.0) / dp.numDocsWithAnchorName;
            }
        }
        

        if (denominator + dummyContributionProb == 0) {
            cand.posteriorProb = Double.POSITIVE_INFINITY;
        } else cand.posteriorProb = numerator / (denominator + dummyContributionProb);

        // DEBUG VALUES:
        cand.debug.dummy_contribution = dummyContributionProb;
        cand.debug.dummyPosteriorProb = dummyContributionProb / (denominator + numerator);

        cand.debug.prob_e_cond_n = dict.get(cand.name).get(cand.entityURL);
        cand.debug.prob_e_in_E_times_nr_docs = entsDocFreqsInCorpus.get(cand.entityURL);
        cand.debug.denominator = denominator;
    }

    // 2 steps: 
    // ** for each candidate - compute posterior probability and keep the highest scored one for each (offset,name)
    // ** for each winner candidate - print it and print debug info
    private static void GenWinningEntities(
            GenericSinglePage doc,
            Vector<Candidate> candidates,
            HashSet<String> docEntities,
            boolean includeDummyEnt) {
        
        HashSet<String> serializedMentions = new HashSet<String>();
        for (TruthMention m : doc.truthMentions) {
            if (m.mentionOffsetInText >= 0) {
                serializedMentions.add(m.wikiUrl + "\t" + m.anchorText + "\t" + m.mentionOffsetInText);
            }
        }
        
        // Winning candidates grouped by their starting index in allAnnotatedText
        // Key of the hash map: allAnnotatedText offset + "\t" + name
        HashMap<String, Candidate> winners = new HashMap<String,Candidate>();

        // For each candidate, compute the score as described in formula (8).
        for (Candidate cand : candidates) {				
            if (!dict.containsKey(cand.name)) {
                continue;
            }
            // if P(cand.wikiUrl | cand.name) = 0 , we ignore this candidate.
            if (!dict.get(cand.name).containsKey(cand.entityURL)) {
                continue;
            }

            ComputeSimpleScoreForOneCandidate(cand, docEntities, includeDummyEnt);

            String key = cand.textIndex + "\t" + cand.name;
            if ((!winners.containsKey(key) || winners.get(key).posteriorProb < cand.posteriorProb)) {	
                //if (dummyPosteriorProb < cand.posteriorProb)
                winners.put(key, cand);
            }
        }

        // **************** WRITING WINNER CANDIDATES ******************************
        for (Candidate c : winners.values()) {
            System.out.println();
            System.out.println("entity=" + c.entityURL + "; name=" + c.name + "; page offset=" +
                    c.textIndex);
            System.out.println("l(dummy,n)=" + c.debug.dummyPosteriorProb + "; l(e,n)=" + c.posteriorProb);
            System.out.print("; P(n|e)=" + c.prob_n_cond_e );
            System.out.print("; p(e|n)=" + c.debug.prob_e_cond_n + "; p(e in E)=" + c.debug.prob_e_in_E_times_nr_docs);
            System.out.println("; denominator=" + c.debug.denominator + "; p(d|n)/(1-p(d|n)) = " + c.debug.dummy_contribution);

            int start = Math.max(0, c.textIndex - 50);
            int end = Math.min(c.textIndex + 50, doc.getRawText().length() - 1);

            System.out.println(";context=" + doc.getRawText().substring(start, end).replace('\n', ' '));

            if (c.debug.dummyPosteriorProb > 0 && c.debug.dummyPosteriorProb < c.posteriorProb) {
                System.out.println("## DUMMY WORSE THAN ME ### ");
            }

            if (c.debug.dummyPosteriorProb > 0 && c.debug.dummyPosteriorProb >= c.posteriorProb) {
                System.out.println("## DUMMY BETTER THAN ALL CANDIDATES ### ");
            } else {
                String key = c.entityURL +"\t" + c.name + "\t" + c.textIndex;      
                if (serializedMentions.contains(key)) {
                    System.out.println("## GOOD RESULT ##");
                }
            }
        }
    }


    /* Baseline approach: 
     * - Mention detection: the same as we do now: select candidate token spans n by considering each e \in E and all names n with p(n|e) >= theta
     * - Link generation: for each name, select the highest e in E ranked by p(e|n,\exists e) , or DUMMY if such an entity does not exist
     */
    private static Vector<Candidate> GenWinningEntitiesWithBaselineApproach(
            GenericSinglePage doc,
            Vector<Candidate> candidates,
            HashSet<String> docEntities,
            boolean includeDummyEnt,
            // TODO: delete this in the end
            HashMap<String,HashMap<String, Boolean>> debugMentionsInfos) {

        Vector<Candidate> winnerMatchings = new Vector<Candidate>();
        
        // This is used just to know if a winner was printed or not.
        // The same (e,n,offset) might be discovered from multiple candidates in this token span approach.
        HashSet<String> winnersSerialized = new HashSet<String>();
        
        HashSet<String> serializedMentions = new HashSet<String>();
        for (TruthMention m : doc.truthMentions) {
            serializedMentions.add(m.wikiUrl + "\t" + m.anchorText + "\t" + m.mentionOffsetInText);
        }
             
        for (Candidate cand : candidates) {
            String winnerURL= "DUMMY";
            double winnerScore = 0.0;
            String winnerName = cand.name;
            int winnerOffset = cand.textIndex;
            
            if (!dict.containsKey(winnerName)) {
                continue;
            }
            
            for (String url : dict.get(winnerName).keySet()) {
                if (docEntities.contains(url) && winnerScore < dict.get(winnerName).get(url)) {
                    winnerURL = url;
                    winnerScore = dict.get(winnerName).get(url);
                }
            }
                        
            String winnerKey = winnerURL + "\t" + winnerName + "\t" + winnerOffset;
            if (winnersSerialized.contains(winnerKey) || winnerURL.equals("DUMMY")) {
                continue;
            }
            winnersSerialized.add(winnerKey);
            
            if (serializedMentions.contains(winnerKey)) {
                System.out.println("## GOOD RESULT ##");
            }
            System.out.println("## ANNOTATION ##\n");    
        }
        
        return winnerMatchings;
    }
   
    
    // Tells if name n1 should be chosen over n2 when we know the given entity e.
    private static boolean isBetterNameGivenEntity(String e, String n1, double prob_n1_cond_e, String n2, double prob_n2_cond_e) {
        String context = Utils.getContext(n1, n2);
        
        String context_key_n1 = e + "\t" + n1 + "\t" + context;
        double contextProb_n1 = contextProbs.containsKey(context_key_n1) ? contextProbs.get(context_key_n1) : 1.0;

        String context_key_n2 = e + "\t" + n2 + "\t" + context;
        double contextProb_n2 = contextProbs.containsKey(context_key_n2) ? contextProbs.get(context_key_n2) : 1.0;

        return prob_n1_cond_e * contextProb_n1 > prob_n2_cond_e * contextProb_n2;
    }
    
    /*  Extended token span approach.
     *  Steps: 
     *** retain the set of (name, page_offset) for each page
     *** for each (n, offset): 
     ***   - compute the set (n',e') with p(n'|e') >= theta and n' \in t=n-,n,n+
     ***   - for each such e' compute l(n' \in t, e')
     ***   - retain the highest scored e'
     ***   - compute the name n' \in t with the highest p(n'|e')
     ***   - output winning pair (n',e') - print it and print debug info
     *
     *  Returns a set of winning candidates.   
     */
    private static Vector<Candidate> GenWinningEntitiesWithExtendedTokenSpan(
            GenericSinglePage doc,
            Vector<Candidate> candidates,
            HashSet<String> docEntities,
            boolean includeDummyEnt,
            // TODO: delete this in the end
            HashMap<String,HashMap<String, Boolean>> debugMentionsInfos) {

        Vector<Candidate> winnerMatchings = new Vector<Candidate>();
        
        HashSet<String> serializedMentions = new HashSet<String>();
        for (TruthMention m : doc.truthMentions) {
            serializedMentions.add(m.wikiUrl + "\t" + m.anchorText + "\t" + m.mentionOffsetInText);
        }
        
        // Key of the hash map: name + "\t" + allAnnotatedText offset
        HashSet<String> namesOfCandidates = new HashSet<String>();
        // Key of the hash map: name
        HashMap<String,Vector<Candidate>> indexOfCandidates = new HashMap<String,Vector<Candidate>>();
        
        for (Candidate cand : candidates) {
            String key = cand.name + "\t" + cand.textIndex;
            namesOfCandidates.add(key);
            
            if (!indexOfCandidates.containsKey(cand.name)) {
                indexOfCandidates.put(cand.name, new Vector<Candidate>());
            }
            indexOfCandidates.get(cand.name).add(cand);
        }

        // This is used just to know if a winner was printed or not.
        // The same (e,n,offset) might be discovered from multiple candidates in this token span approach.
        HashSet<String> winnersSerialized = new HashSet<String>();
        
        // for each (n, offset):
        for (String key : namesOfCandidates) {            
            System.out.println("\nNOW ANALYZE CAND: " + key + " ;docName=" + doc.pageName);
            
            String name = key.substring(0, key.lastIndexOf('\t'));
            int offset = Integer.parseInt(key.substring(name.length() + 1));

            // Set of all entities e' from all pairs (n',e') with p(n'|e') >= theta and n' \in t=n-,n,n+
            HashSet<String> possibleEntities = new HashSet<String>();
            
            // for each n' \in t=n-,n,n+
            Vector<TokenSpan> surroundingTokenSpans = Utils.getTokenSpans(doc.getRawText(), offset, name.length());
            for (TokenSpan ts : surroundingTokenSpans) {
                // extract n'
                String extendedName = doc.getRawText().substring(ts.start, ts.end);
                
                // for each (n',e') with p(n'|e') >= theta and n' \in t=n-,n,n+
                if (!indexOfCandidates.containsKey(extendedName)) {
                    continue;
                }
                for (Candidate cand : indexOfCandidates.get(extendedName)) {
                    // retain just e'
                    possibleEntities.add(cand.entityURL);
                }
            }
            
            String winnerURL = "";
            double winnerScore = 0.0; 
            
            // Different initialization for p(dummy|n): it is 1.0 if the name contains at most 2 tokens, 0.0 in the rest.            
            double dummyInit = (Utils.NumTokens(name) <= 2 ? 0.99999 : 0.00001);
            
            // for each such e' compute l(n'\in t, e') and retain the highest scored e'
            for (String entity : possibleEntities) {
                double numerator = 0.0;
                double denominator = 0.0;
                
                for (TokenSpan ts : surroundingTokenSpans) {
                    // extract n'
                    String extendedName = doc.getRawText().substring(ts.start, ts.end);
                                        
                    // p(dummy | name)
                    double dummyProb = dummyInit;
                    if (includeDummyEnt && dummyProbabilities.containsKey(extendedName)) {
                        DummyIntPair dp = dummyProbabilities.get(extendedName);
                        dummyProb = 1 - (dp.numDocsWithAnchorName + 0.0) / dp.numDocsWithName;
                    }
                                        
                    denominator += (ComputeDenominator(extendedName, entity, docEntities) * (1 - dummyProb) + dummyProb);
                    
                    // if P(entity | extendedName, \exists ent) = 0 , we skip this candidate.
                    if (extendedName.contains("\n") || !dict.containsKey(extendedName) || !dict.get(extendedName).containsKey(entity)) {
                        continue;
                    }
                    
                    // TODO: delete this
                    boolean printed = false;
                    if (indexOfCandidates.containsKey(extendedName)) {
                        for (Candidate cand : indexOfCandidates.get(extendedName)) {
                            if (cand.textIndex == offset && cand.entityURL.equals(entity) && cand.name.equals(extendedName)) {
                                System.out.println("     Adding to numerator: extendedname=" + extendedName + ";url=" + entity + " ;p(∃e| n)=" + (1-dummyProb) +
                                        " ;p(e|n,∃e)=" + dict.get(extendedName).get(entity) +
                                        " ;p(n|e)=" + cand.prob_n_cond_e +
                                        " ;entsDocFreqs=" +entsDocFreqsInCorpus.get(entity) +
                                        " ;final adding=" + (dict.get(extendedName).get(entity) * totalNumDocs / entsDocFreqsInCorpus.get(entity) * (1-dummyProb)) );
                                  printed = true;
                            }
                        }
                    }
                    if (!printed) {
                        System.out.println("     Adding to numerator (NOT A CAND): extendedname=" + extendedName + ";url=" + entity + " ;p(∃e| n)=" + (1-dummyProb) +
                                " ;p(e|n,∃e)=" + dict.get(extendedName).get(entity) +
                                " ;entsDocFreqs=" +entsDocFreqsInCorpus.get(entity) +
                                " ;final adding=" + (dict.get(extendedName).get(entity) * totalNumDocs / entsDocFreqsInCorpus.get(entity) * (1-dummyProb)) );                     
                    }

                    numerator += dict.get(extendedName).get(entity) * totalNumDocs / entsDocFreqsInCorpus.get(entity) * (1-dummyProb);
                }

                double score;
                if (numerator == 0) {
                    continue;
                }
                if (denominator == 0) {
                    score = Double.POSITIVE_INFINITY;
                }
                else {
                    score = numerator / denominator;
                }

                System.out.println("  Candidate entity=" + entity + "; l(e, n \\in t)=" + score + " ;numerator=" + numerator + " ;denominator = " + denominator);
                if (score > winnerScore) {
                    winnerScore = score;
                    winnerURL = entity;
                }
            }
            
            
            // Compute dummy entity score l(n \in t, dummy)
            double dummyScore = 0.0;            
            if (includeDummyEnt) {
                double dummyNumerator = 0.0;
                double dummyDenominator = 0.0;

                for (TokenSpan ts : surroundingTokenSpans) {
                    String extendedName = doc.getRawText().substring(ts.start, ts.end);
                    
                    double dummyProb = dummyInit;
                    if (dummyProbabilities.containsKey(extendedName)) {
                        DummyIntPair dp = dummyProbabilities.get(extendedName);
                        dummyProb = 1 - (dp.numDocsWithAnchorName + 0.0) / dp.numDocsWithName;
                    }
                    dummyNumerator += dummyProb;
                    dummyDenominator += (1 - dummyProb) * ComputeDenominator(extendedName, "", docEntities);
                }
                                
                if (dummyNumerator > 0) {
                    if (dummyDenominator == 0) {
                        dummyScore = Double.POSITIVE_INFINITY;
                    }
                    else {
                        dummyScore = dummyNumerator / dummyDenominator;
                        System.out.println("  Candidate entity= DUMMY; l(e, n \\in t)=" + dummyScore + " ;numerator=" + dummyNumerator + " ;denominator = " + dummyDenominator);
                    }
                }
            } 

            if (includeDummyEnt && dummyScore > 0 && dummyScore > winnerScore) {
                // TODO: delete this, it might take a long time to finish
                for (TruthMention m : doc.truthMentions) {
                    if (offset == m.mentionOffsetInText && name.compareTo(m.anchorText) == 0) {
                        String key2 = "URL=" + m.wikiUrl + ";name=" + m.anchorText + ";offset=" + m.mentionOffsetInText;
                        System.out.println("## SHOULD HAVE BEEN A TRUTH MENTION ##: " + key2);
                        
                        String key3 = "URL=" + m.wikiUrl + ";name=" + m.anchorText + ";offset=" + m.mentionOffsetInText + ";doc=" + doc.pageName;                        
                        if (!debugMentionsInfos.get(key3).get("isCandidate")) System.out.println("  NOT A CANDIDATE");
                        if (!debugMentionsInfos.get(key3).get("EntIsInInvdict")) System.out.println("  ENT NOT IN INVDICT");
                        if (!debugMentionsInfos.get(key3).get("NameIsInInvdictForEnt")) System.out.println("  NAME NOT IN INVDICT FOR ENT");
                        if (!debugMentionsInfos.get(key3).get("EntIsInAllEnts")) System.out.println("  ENT NOT IN ALL ENTS");
                    }
                }

                System.out.println("  WINNER URL FOR CAND: ## DUMMY ##" );
                continue;
            }
            if (includeDummyEnt && dummyScore > 0 && dummyScore <= winnerScore) {
                System.out.println("## DUMMY WORSE THAN ME ###");
            }
            System.out.println("  WINNER URL FOR CAND: " + winnerURL);
            
            /*
             * 
             * Finding the proper token span now:
             * 
             */
            System.out.println("----NOW FINDING THE PROPER TOKEN SPAN :");
            
            String winnerName = "";
            double winnerProb_name_cond_ent = 0.0;
            int winnerOffset = -1;
            for (TokenSpan ts : surroundingTokenSpans) {
                // extract n'
                String extendedName = doc.getRawText().substring(ts.start, ts.end);
                System.out.println("  TOKEN SPAN: " + extendedName);

                // for each (n',e') with p(n'|e') >= theta and n' \in t=n-,n,n+
                if (!indexOfCandidates.containsKey(extendedName)) {
                    continue;
                }
                
                // Heuristic to overpass errors in Crosswikis inv.dict
                /*
                if (extendedName.toLowerCase().equals(winnerURL.replace('_', ' ').toLowerCase())) {
                    winnerName = extendedName;
                    winnerProb_name_cond_ent = 100000.0;
                    winnerOffset = ts.start;
                    break;
                }
                */
                
                // This might be improved to a O(1) by using a HashSet
                for (Candidate cand : indexOfCandidates.get(extendedName)) {
                    if (cand.entityURL.compareTo(winnerURL) == 0 && ts.start == cand.textIndex && dict.containsKey(extendedName) && 
                            dict.get(extendedName).containsKey(winnerURL)) {
                        System.out.println("     CAND APPEARING HERE :::" + extendedName + " ;url=" + cand.entityURL + 
                                " ;p(n|e)=" + cand.prob_n_cond_e + "; p(e|n,∃e)=" + dict.get(extendedName).get(winnerURL) + "; offset=" + cand.textIndex);
                    } 
                        
                    if (cand.entityURL.compareTo(winnerURL) == 0 && ts.start == cand.textIndex) {
                        if (isBetterNameGivenEntity(winnerURL, extendedName, cand.prob_n_cond_e, winnerName, winnerProb_name_cond_ent)) {
                            winnerName = extendedName;
                            winnerProb_name_cond_ent = cand.prob_n_cond_e;
                            winnerOffset = ts.start;                           
                        }
                    }
                }
            }
            String winnerKey = winnerURL + "\t" + winnerName + "\t" + winnerOffset;
            
            if (winnerOffset < 0) {
                continue;
            }

            System.out.println("  WINNER NAME FOR CAND: " + winnerName + "::: winner offset " + winnerOffset + " ::: WINNER URL = " + winnerURL);

            if (winnersSerialized.contains(winnerKey)) {
                System.out.println("## ALREADY FOUND BEFORE ##");
                continue;
            }
            winnersSerialized.add(winnerKey);
       
            
            if (dummyScore <= 0 || dummyScore <= winnerScore) {
                Candidate c = new Candidate(winnerURL, null, winnerName, winnerOffset, -1);
                c.posteriorProb = winnerScore;
                c.prob_n_cond_e = winnerProb_name_cond_ent;
                
                int start = Math.max(0, winnerOffset - 30);
                int end = Math.min(winnerOffset + 30, doc.getRawText().length() - 1);

                c.debug.context = doc.getRawText().substring(start, end).replace('\n', ' ');
                c.debug.initialName = name;

                System.out.println("  CONTEXT: \"" + c.debug.context + "\"");
                winnerMatchings.add(c);
            }
            
            if (serializedMentions.contains(winnerKey)) {
                System.out.println("## GOOD RESULT ##");
            } else {
                // TODO: delete this, it might take a long time to finish
                for (TruthMention m : doc.truthMentions) {
                    if (winnerOffset == m.mentionOffsetInText && winnerName.compareTo(m.anchorText) == 0) {
                        String key2 = "URL=" + m.wikiUrl + ";name=" + m.anchorText + ";offset=" + m.mentionOffsetInText;
                        System.out.println("## SHOULD HAVE BEEN A TRUTH MENTION ##: " + key2);
                        
                        String key3 = "URL=" + m.wikiUrl + ";name=" + m.anchorText + ";offset=" + m.mentionOffsetInText + ";doc=" + doc.pageName;                        
                        if (!debugMentionsInfos.get(key3).get("isCandidate")) System.out.println("  NOT A CANDIDATE");
                        if (!debugMentionsInfos.get(key3).get("EntIsInInvdict")) System.out.println("  ENT NOT IN INVDICT");
                        if (!debugMentionsInfos.get(key3).get("NameIsInInvdictForEnt")) System.out.println("  NAME NOT IN INVDICT FOR ENT");
                        if (!debugMentionsInfos.get(key3).get("EntIsInAllEnts")) System.out.println("  ENT NOT IN ALL ENTS");

                    }
                }
                System.out.println("## NEW ANNOT ##");
            }
            System.out.println("## ANNOTATION ##");
       
        }

        
        // TODO: delete this, it might take a long time to finish
        for (TruthMention m : doc.truthMentions) {
            boolean wasFound = false;
            for (Candidate c : winnerMatchings) {
                if (c.textIndex == m.mentionOffsetInText && c.entityURL.compareTo(m.wikiUrl) == 0 && c.name.compareTo(m.anchorText) == 0) {
                    wasFound = true;
                    break;
                }
            }  
            
            if (!wasFound) {
                String key = "URL=" + m.wikiUrl + ";name=" + m.anchorText + ";offset=" + m.mentionOffsetInText + ";doc=" + doc.pageName;
                System.out.println("## UNFOUND MENTION ##: " + key);
                
                if (!debugMentionsInfos.get(key).get("isCandidate")) System.out.println("  NOT A CANDIDATE");
                if (!debugMentionsInfos.get(key).get("EntIsInInvdict")) System.out.println("  ENT NOT IN INVDICT");
                if (!debugMentionsInfos.get(key).get("NameIsInInvdictForEnt")) System.out.println("  NAME NOT IN INVDICT FOR ENT");
                if (!debugMentionsInfos.get(key).get("EntIsInAllEnts")) System.out.println("  ENT NOT IN ALL ENTS");

                for (Candidate c : candidates) {
                    if (c.textIndex == m.mentionOffsetInText) {
                        double prob_e_cond_n = 0.0;
                        if (dict.containsKey(c.name) && dict.get(c.name).containsKey(c.entityURL)) {
                            prob_e_cond_n = dict.get(c.name).get(c.entityURL);
                        }
                        System.out.println("  [NORMAL CAND] url=" + c.entityURL + "; name=" + c.name + "; p(n|e)=" + c.prob_n_cond_e + "; p(e|n,∃e)=" + prob_e_cond_n + "; offset=" + c.textIndex);
                    }
                }
                for (Candidate c : winnerMatchings) {
                    if (c.textIndex == m.mentionOffsetInText) {
                        System.out.println("  [WINNER CAND] url=" + c.entityURL + "; initial name:" + c.debug.initialName + "; final name=" + c.name + "; p(n|e)=" + c.prob_n_cond_e + "; p(e|n,∃e)=" + dict.get(c.name).get(c.entityURL) + " ; winner entity score l(n \\in t, e)=" + c.posteriorProb + 
                                "; offset=" + c.textIndex + " ;context :::" + c.debug.context );
                       
                    }
                }

                System.out.println();
            }
        }

        
        /*
         * Heuristic to implement: if 2 winning (n1, e) and (n2,e) have n1 and n2 overlapping, keep just the one with highest p(n|e, context)
           TODO: optimize this !!
         */
        Vector<Candidate> winnerMatchingsWithoutOverlappingSameEnts = new Vector<Candidate>();
        for (Candidate c1 : winnerMatchings) {
            boolean wins = true;
            Candidate better = null;
            
            for (Candidate c2 : winnerMatchings) {
                if (!c1.entityURL.equals(c2.entityURL)) continue;
                if (c1.textIndex + c1.name.length() <= c2.textIndex) continue;
                if (c2.textIndex + c2.name.length() <= c1.textIndex) continue;
                if (isBetterNameGivenEntity(c1.entityURL, c2.name, c2.prob_n_cond_e, c1.name , c1.prob_n_cond_e)) {
                    wins = false;
                    better = c2;
                    break;
                }
            }
            
            if (wins) {
                winnerMatchingsWithoutOverlappingSameEnts.add(c1);
            } else {
                String winnerKey = c1.entityURL + "\t" + c1.name + "\t" + c1.textIndex;
                if (serializedMentions.contains(winnerKey)) {
                    System.out.println("## WE LOST GOOD ## url=" +c1.entityURL + " ;name=\n" + c1.name + " ;p(n|e)=" + c1.prob_n_cond_e + 
                            " -- in favor of -- \n" + better.name + " ;p(n|e)=" + better.prob_n_cond_e );
                }
            }
        }
        
        for (Candidate c : winnerMatchingsWithoutOverlappingSameEnts) {
            String winnerURL = c.entityURL;
            String winnerName = c.name;
            int winnerOffset = c.textIndex;
            
            String winnerKey = winnerURL + "\t" + winnerName + "\t" + winnerOffset;

            
            if (!serializedMentions.contains(winnerKey)) {
                /*
                System.err.println("  DOC: " + doc.pageName);
                System.err.println("  NAME: " + winnerName);
                System.err.println("  OFFSET: " + winnerOffset);
                System.err.println("  URL: " + winnerURL);
            
                int start = Math.max(0, winnerOffset - 30);
                int end = Math.min(winnerOffset + 30, doc.getRawText().length() - 1);
                c.debug.context = doc.getRawText().substring(start, end).replace('\n', ' ');

                System.err.println("  CONTEXT: \"" + c.debug.context + "\"");
                System.err.println();
                */
            }
            
            int start = Math.max(0, winnerOffset - 30);
            int end = Math.min(winnerOffset + 30, doc.getRawText().length() - 1);
            String context = doc.getRawText().substring(start, end).replace('\n', ' ');
            System.out.println("FINALL: " + winnerKey + " ;context=" + context);
            if (serializedMentions.contains(winnerKey)) {
                System.out.println("## GOOD RESULT FINALL ##");
            } else {
                System.out.println("## NEW ANNOT FINALL ##");
            }
            System.out.println("## ANNOTATION FINALL ##");
    
        }
        
        
        // PRINT ALL ANNOTATIONS
        // TODO: delete this, it might take a long time to finish
        /*
        for (TruthMention m : doc.truthMentions) {
            boolean wasFound = false;
            for (Candidate c : winnerMatchingsWithoutOverlappingSameEnts) {
                if (c.textIndex == m.mentionOffsetInText && c.entityURL.compareTo(m.wikiUrl) == 0 && c.name.compareTo(m.anchorText) == 0) {
                    wasFound = true;
                    break;
                }
            }  
            
            if (!wasFound) {
                System.err.println("  DOC: " + doc.pageName);
                System.err.println("  NAME: " + m.anchorText );
                System.err.println("  OFFSET: " + m.mentionOffsetInText);
                System.err.println("  URL: " + m.wikiUrl);
            
                int start = Math.max(0, m.mentionOffsetInText - 30);
                int end = Math.min(m.mentionOffsetInText + 30, doc.getRawText().length() - 1);
                String context = doc.getRawText().substring(start, end).replace('\n', ' ');

                System.err.println("  CONTEXT: \"" + context + "\"");
                System.err.println();
                
            }
        }
        */
        return winnerMatchingsWithoutOverlappingSameEnts;
    }

    // Main function that generates the candidates and selects the highest scored ones.
    // Supports both simple and extended token span implementations with dummy entity or not.
    public static void run(
            String invdictFilename, 
            String dictFilename, 
            String allEntsFilename, 
            String existenceProbsFilename,
            String contextProbsFilename,
            String valueToKeep,
            double multiplyConst,
            double theta, 
            GenericPagesIterator inputPagesIterator,
            boolean extendedTokenSpan,
            boolean includeDummyEnt,
            boolean baseLine) throws IOException, InterruptedException {
        
        entsDocFreqsInCorpus = LoadWikilinksEntsOrNamesWithFreqs.load(allEntsFilename, "entities");
        
        // ***** STAGE 1: Generate all candidate pairs (n,e) such that P(n|e) >= theta
        HashSet<String> allEntitiesFromAllPages = GetAllEntitiesFromAllPages(inputPagesIterator);        
        invdict = LoadCrosswikisInvdict.load(invdictFilename, allEntitiesFromAllPages, entsDocFreqsInCorpus);
        System.gc();

        HashSet<String> allCandidateNames = new HashSet<String>();
        Vector<Vector<Candidate>> allCandidates = new Vector<Vector<Candidate>>();
        GenericPagesIterator p = inputPagesIterator.hardCopy();

        // TODO : delete this debug info in the end !!!!!!!
        HashMap<String,HashMap<String, Boolean>> debugMentionsInfos = new HashMap<String, HashMap<String,Boolean>>();

        GenAllCandidatesFromAllPages(p, theta, allCandidateNames, allCandidates, debugMentionsInfos);

        System.gc(); // Clean inv.dict index
        // ***** STAGE 2: Compute the l(n,e) values, group by n and find the winning candidate.        
        dict = LoadCrosswikisDict.load(dictFilename, allCandidateNames);
        contextProbs = LoadContextProbs.load(contextProbsFilename);

        if (includeDummyEnt) {
            // Select one possible set of existence probs:
            
            // dummyProbabilities = LoadKeyphrasenessDummyProbs.load(existenceProbsFilename);
            dummyProbabilities = LoadExistenceCrosswikisProbs.load(existenceProbsFilename, valueToKeep, multiplyConst);
        }
        
        System.err.println("[INFO] Writing winner entities...");
        p = inputPagesIterator.hardCopy();
        int nr_page = -1;
        while (p.hasNext()) {
            nr_page++;

            GenericSinglePage doc = p.next();
            System.out.println("[INFO] ------- Page: " + nr_page + " ---- Page name: " + doc.pageName);

            // compute M.doc.E
            HashSet<String> docEntities = new HashSet<String>();
            for (TruthMention m : doc.truthMentions) {
                if (m.wikiUrl.length() > 0) {
                    docEntities.add(m.wikiUrl);
                }
            }			

            if (extendedTokenSpan) {
                Vector<Candidate> matchings = GenWinningEntitiesWithExtendedTokenSpan(doc, allCandidates.get(nr_page), docEntities, includeDummyEnt, debugMentionsInfos);

                // Annotate the input doc.rawText using the newly found entities:
                // ExtractSentencesWithTheNewAnnotations.run(doc, matchings);
            } else if (baseLine) {
                Vector<Candidate>  matchings = GenWinningEntitiesWithBaselineApproach(doc, allCandidates.get(nr_page), docEntities, includeDummyEnt, debugMentionsInfos);
            } else {
                GenWinningEntities(doc, allCandidates.get(nr_page), docEntities, includeDummyEnt);
            }
        }
        System.err.println("[INFO] --------------- Done ---------------");
    }

}