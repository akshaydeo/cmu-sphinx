/*
* Copyright 1999-2002 Carnegie Mellon University.
* Portions Copyright 2002 Sun Microsystems, Inc.
* Portions Copyright 2002 Mitsubishi Electronic Research Laboratories.
* All Rights Reserved.  Use is subject to license terms.
*
* See the file "license.terms" for information on usage and
* redistribution of this file, and for a DISCLAIMER OF ALL
* WARRANTIES.
*
*/

package edu.cmu.sphinx.decoder.search;


// a test search manager.

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.cmu.sphinx.decoder.scorer.AcousticScorer;
import edu.cmu.sphinx.linguist.HMMSearchState;
import edu.cmu.sphinx.linguist.Linguist;
import edu.cmu.sphinx.linguist.SearchState;
import edu.cmu.sphinx.linguist.SearchStateArc;
import edu.cmu.sphinx.linguist.WordSearchState;
import edu.cmu.sphinx.linguist.WordSequence;
import edu.cmu.sphinx.linguist.dictionary.Word;
import edu.cmu.sphinx.result.Result;
import edu.cmu.sphinx.util.LogMath;
import edu.cmu.sphinx.util.SphinxProperties;
import edu.cmu.sphinx.util.StatisticsVariable;
import edu.cmu.sphinx.util.Timer;



/**
 * Provides the breadth first search. To perform recognition
 * an application should call initialize before
 * recognition begins, and repeatedly call <code> recognize </code>
 * until Result.isFinal() returns true.  Once a final result has been
 * obtained, <code> terminate </code> should be called.
 *
 * All scores and probabilities are maintained in the log math log
 * domain.
 */

public class WordPruningBreadthFirstSearchManager implements  SearchManager {

    private final static String PROP_PREFIX =
            "edu.cmu.sphinx.decoder.search.BreadthFirstSearchManager.";

    /**
     * Sphinx property that defines the type of active list to use
     */
    public final static String PROP_ACTIVE_LIST_TYPE =
            PROP_PREFIX + "activeListType";

    /**
     * The default value for the PROP_ACTIVE_LIST_TYPE property
     */
    public final static String PROP_ACTIVE_LIST_TYPE_DEFAULT =
            "edu.cmu.sphinx.decoder.search.SimpleActiveList";


    /**
     * A sphinx property than, when set to <code>true</code> will
     * cause the recognizer to count up all the tokens in the active
     * list after every frame.
     */
    public final static String PROP_SHOW_TOKEN_COUNT =
            PROP_PREFIX + "showTokenCount";

    /**
     * The default value for the PROP_SHOW_TOKEN_COUNT property
     */
    public final static boolean PROP_SHOW_TOKEN_COUNT_DEFAULT = false;


    /**
     * Sphinx property for checking if the order of states is valid.
     */
    public final static String PROP_CHECK_STATE_ORDER = 
        PROP_PREFIX + "checkStateOrder";

    /**
     * The default value of the PROP_CHECK_STATE_ORDER property.
     */
    public final static boolean PROP_CHECK_STATE_ORDER_DEFAULT = false;

    /**
     * Sphinx property that specifies whether to build a word lattice.
     */
    public final static String PROP_BUILD_WORD_LATTICE =
        PROP_PREFIX + "buildWordLattice";

    /**
     * The default value of the PROP_BUILD_WORD_LATTICE property.
     */
    public final static boolean PROP_BUILD_WORD_LATTICE_DEFAULT = true;

    /**
     * A sphinx property that controls the number of frames processed
     * for every time the decode growth step is skipped. Setting this
     * property to zero disables grow skipping.  Setting this number
     * to a small integer will increase the speed of the decoder but
     * will also decrease its accuracy.
     */
    public final static String PROP_GROW_SKIP_INTERVAL = PROP_PREFIX +
                    "growSkipInterval";
    /**
     * The default value for the PROP_GROW_SKIP_INTERVAL property.
     */
    public final static int PROP_GROW_SKIP_INTERVAL_DEFAULT = 0;


    /**
     * A sphinx property that controls the amount of simple acoustic
     * lookahead performed.  Setting the property to zero (the
     * default) disables simple acoustic lookahead. The lookahead need
     * not be an integer.
     */
    public final static String PROP_ACOUSTIC_LOOKAHEAD_FRAMES = PROP_PREFIX +
                    "acousticLookaheadFrames";

    /**
     * The default value for the PROP_ACOUSTIC_LOOKAHEAD_FRAMES property.
     */
    public final static float PROP_ACOUSTIC_LOOKAHEAD_FRAMES_DEFAULT = 0F;

    /**
     * A sphinx property that controls whether or not we keep all
     * tokens.  If this is set to false, only word tokens are
     * retained, otherwise all tokens are retained.
     * 
     */
    public final static String PROP_KEEP_ALL_TOKENS = PROP_PREFIX +
                    "keepAllTokens";

    /**
     * The default value for the PROP_ACOUSTIC_LOOKAHEAD_FRAMES property.
     */
    public final static boolean PROP_KEEP_ALL_TOKENS_DEFAULT = false;

    // TODO: since the token stacks are permanently disabled,
    // we may want to just remove all of the supporting code
    private final static boolean wantTokenStacks = false;

    

    private Linguist linguist;		// Provides grammar/language info
    private Pruner pruner;		// used to prune the active list
    private AcousticScorer scorer;	// used to score the active list

    private int currentFrameNumber;	// the current frame number
    private ActiveList activeList;	// the list of active tokens
    private ActiveListManager activeBucket;
    private List resultList;		// the current set of results
    private SphinxProperties props;	// the sphinx properties
    private LogMath logMath;

    private Timer scoreTimer;
    private Timer pruneTimer;
    private Timer growTimer;

    private StatisticsVariable totalTokensScored;
    private StatisticsVariable curTokensScored;
    private StatisticsVariable tokensCreated;

    private boolean showTokenCount;
    private boolean checkStateOrder;
    private boolean buildWordLattice;
    private boolean allowSinglePathThroughHMM = false;
    private boolean keepAllTokens = false;

    private Map bestTokenMap;
    private AlternateHypothesisManager loserManager;
    private Class[] stateOrder;



    private int maxTokenHeapSize = 3;
    private TokenTracker tokenTracker;
    private int growSkipInterval = 0;
    private Map skewMap;
    private float relativeBeamWidth;
    private float acousticLookaheadFrames = 0.0f;

    private long tokenSum = 0;
    private int tokenCount = 0;
    


    /**
     * Initializes this BreadthFirstSearchManager with the given
     * context, linguist, scorer and pruner.
     *
     * @param context the context to use
     * @param linguist the Linguist to use
     * @param scorer the AcousticScorer to use
     * @param pruner the Pruner to use
     */
    public void initialize(String context, Linguist linguist,
                           AcousticScorer scorer, Pruner pruner) {
        this.linguist = linguist;
        this.scorer = scorer;
        this.pruner = pruner;
        this.props = SphinxProperties.getSphinxProperties(context);
        this.logMath = LogMath.getLogMath(context);

        scoreTimer = Timer.getTimer(context, "Score");
        pruneTimer = Timer.getTimer(context, "Prune");
        growTimer = Timer.getTimer(context, "Grow");

        totalTokensScored = StatisticsVariable.getStatisticsVariable(
                props.getContext(), "totalTokensScored");
        curTokensScored = StatisticsVariable.getStatisticsVariable(
                props.getContext(), "curTokensScored");
        tokensCreated =
                StatisticsVariable.getStatisticsVariable(props.getContext(),
                        "tokensCreated");

        showTokenCount = props.getBoolean(PROP_SHOW_TOKEN_COUNT, 
                                          PROP_SHOW_TOKEN_COUNT_DEFAULT);
        checkStateOrder = props.getBoolean(PROP_CHECK_STATE_ORDER,
                                           PROP_CHECK_STATE_ORDER_DEFAULT);
        buildWordLattice = props.getBoolean(PROP_BUILD_WORD_LATTICE,
                                            PROP_BUILD_WORD_LATTICE_DEFAULT);
        acousticLookaheadFrames = props.getFloat(PROP_ACOUSTIC_LOOKAHEAD_FRAMES,
                                    PROP_ACOUSTIC_LOOKAHEAD_FRAMES_DEFAULT);

        keepAllTokens = props.getBoolean(PROP_KEEP_ALL_TOKENS,
                                            PROP_KEEP_ALL_TOKENS_DEFAULT);

        growSkipInterval = props.getInt(PROP_GROW_SKIP_INTERVAL,
                    PROP_GROW_SKIP_INTERVAL_DEFAULT);

        tokenTracker = new TokenTracker();

	double linearRelativeBeamWidth  
	    = props.getDouble(ActiveList.PROP_RELATIVE_BEAM_WIDTH, 
			      ActiveList.PROP_RELATIVE_BEAM_WIDTH_DEFAULT);
        this.relativeBeamWidth = 
                logMath.linearToLog(linearRelativeBeamWidth);
    }


    /**
     * Called at the start of recognition. Gets the search manager
     * ready to recognize
     */
    public void start() {
        linguist.start();
        pruner.start();
        scorer.start();
        localStart();

    }

    /**
     * Performs the recognition for the given number of frames.
     *
     * @param nFrames the number of frames to recognize
     *
     * @return the current result
     */
    public Result recognize(int nFrames) {
        boolean done = false;
        Result result;

        for (int i = 0; i < nFrames && !done; i++) {
	    // System.out.println("Frame " + currentFrameNumber);
            // score the emitting list

            tokenTracker.startFrame();
            activeList = activeBucket.getEmittingList();
            if (activeList != null) {
                do  {
                    currentFrameNumber++;
                    done = !scoreTokens();
                } while (!done && (growSkipInterval > 1 && 
                        ((currentFrameNumber % growSkipInterval) == 0)));
                if (!done) {
                    bestTokenMap = createBestTokenMap();
                    // prune and grow the emitting list
                    pruneBranches();

                    resultList = new LinkedList();
                    growEmittingBranches();
                        // prune and grow the non-emitting lists
                        // activeBucket.dump();
                    growNonEmittingLists();
                }
            }
            tokenTracker.stopFrame();
        }

        result = new Result(loserManager, activeList, resultList, 
                            currentFrameNumber, done);

        if (showTokenCount) {
            showTokenCount();
        }
        return result;
    }



    /**
     * creates a new best token map
     * with the best size
     *
     * @return the best token map
     */
    private Map createBestTokenMap() {
        // int mapSize = activeList.size() * 10;
        int mapSize = activeList.size() * 2;
        if (mapSize == 0) {
            mapSize = 1;
        }
        return new HashMap(mapSize, 0.5F);
    }


    /**
     * Terminates a recognition
     */
    public void stop() {
        localStop();
        scorer.stop();
        pruner.stop();
        linguist.stop();
    }


    /**
     * Gets the initial grammar node from the linguist and
     * creates a GrammarNodeToken
     */
    protected void localStart() {
        currentFrameNumber = 0;
        curTokensScored.value = 0;

        try {
            skewMap = new HashMap();
            stateOrder = linguist.getSearchStateOrder();
            activeBucket = new SimpleActiveListManager(props, stateOrder);
            if (buildWordLattice) {
                loserManager = new AlternateHypothesisManager(props);
            }

            SearchState state = linguist.getInitialSearchState();
            
            activeList = (ActiveList)
                Class.forName
                (props.getString(PROP_ACTIVE_LIST_TYPE,
                                 PROP_ACTIVE_LIST_TYPE_DEFAULT)).newInstance();
            activeList.setProperties(props);
            activeList.add(new Token(state, currentFrameNumber));
	    resultList = new LinkedList();

            bestTokenMap = new HashMap();
            growBranches();
            growNonEmittingLists();

        } catch (ClassNotFoundException fe) {
            throw new Error("Can't create active list", fe);
        } catch (InstantiationException ie) {
            throw new Error("Can't create active list", ie);
        } catch (IllegalAccessException iea) {
            throw new Error("Can't create active list", iea);
        }

        tokenTracker.setEnabled(false);
        tokenTracker.startUtterance();
    }


    /**
     * Local cleanup for this search manager
     */
    protected void localStop() {
        tokenTracker.stopUtterance();
    }


    /**
     * Goes through the active list of tokens and expands each
     * token, finding the set of successor tokens until all the successor
     * tokens are emitting tokens.
     *
     */
    protected void growBranches() {
        growTimer.start();
        Iterator iterator = activeList.iterator();
        float relativeBeamThreshold = activeList.getBeamThreshold();
	if (false) {
	   System.out.println("thresh : " + relativeBeamThreshold + " bs " +
			   activeList.getBestScore() + " tok " +
			   activeList.getBestToken());
	}
        while (iterator.hasNext()) {
            Token token = (Token) iterator.next();
            if (token.getScore() >= relativeBeamThreshold && skewPrune(token)) {
                collectSuccessorTokens(token);
            }
        }
        growTimer.stop();

        // activeBucket.dump();
    }

    /**
     * Grows the emitting branches. This version applies a simple
     * acoustic lookahead based upon the current acoustic score.
     */
    protected void growEmittingBranchesOld() {
        if (acousticLookaheadFrames > 0F) {
            growTimer.start();
            float bestScore = -Float.MAX_VALUE;
            for (Iterator i = activeList.iterator(); i.hasNext(); ) {
                Token t = (Token) i.next();
                float score = t.getScore() 
                    + t.getAcousticScore() * acousticLookaheadFrames;
                if (score > bestScore) {
                    bestScore = score;
                }
                t.setWorkingScore(score);
            }
            float relativeBeamThreshold = bestScore + relativeBeamWidth;

            for (Iterator i = activeList.iterator(); i.hasNext(); ) {
                Token t = (Token) i.next();
                if (t.getWorkingScore() >= relativeBeamThreshold) {
                    collectSuccessorTokens(t);
                }
            }
            growTimer.stop();
        } else {
            growBranches();
        }
    }

    /**
     * Grows the emitting branches. This version applies a simple
     * acoustic lookahead based upon the rate of change in the 
     * current acoustic score.
     */
    protected void growEmittingBranches() {
        if (acousticLookaheadFrames > 0F) {
            growTimer.start();
            float bestScore = -Float.MAX_VALUE;
            for (Iterator i = activeList.iterator(); i.hasNext(); ) {
                Token t = (Token) i.next();
                Token p = getLastEmittingToken(t);
                float delta = 0;
                if (p != null ) {
                    delta = t.getAcousticScore() - p.getAcousticScore();
                }
                float score = t.getScore() + 
                    (t.getAcousticScore() + delta) * acousticLookaheadFrames;
                if (score > bestScore) {
                    bestScore = score;
                }
                t.setWorkingScore(score);
            }
            float relativeBeamThreshold = bestScore + relativeBeamWidth;

            for (Iterator i = activeList.iterator(); i.hasNext(); ) {
                Token t = (Token) i.next();
                if (t.getWorkingScore() >= relativeBeamThreshold) {
                    collectSuccessorTokens(t);
                }
            }
            growTimer.stop();
        } else {
            growBranches();
        }
    }


    /**
     * Given a token find the most recent predecessor emitting token
     *
     * @param t the token to start searching from
     *
     * @return the most recent emitting token, or null
     */
    protected Token getLastEmittingToken(Token t) {
        while ((t = t.getPredecessor()) != null) {
            if (t.isEmitting()) {
                break;
            }
        }
        return t;
    }


    /**
     * Grow the non-emitting ActiveLists, until the tokens reach
     * an emitting state.
     */
    private void growNonEmittingLists() {
        for (Iterator i = activeBucket.getNonEmittingListIterator();
             i.hasNext(); ) {
	    activeList = (ActiveList) i.next();
	    if (activeList != null) {
                i.remove();
		pruneBranches();
		growBranches();
	    }
        }
    }


    /**
     * Calculate the acoustic scores for the active list. The active
     * list should contain only emitting tokens.
     *
     * @return <code>true</code>  if there are more frames to score,
     * otherwise, false
     *
     */
    protected boolean scoreTokens() {
	boolean moreTokens;
        Token bestToken = null;
	scoreTimer.start();
        bestToken = (Token) scorer.calculateScores(activeList.getTokens());
	scoreTimer.stop();

        moreTokens =  (bestToken != null);
        activeList.setBestToken(bestToken);

        // monitorWords(activeList);
        monitorStates(activeList);
        if (false) {
            System.out.println("BEST " + bestToken);
        }

	curTokensScored.value += activeList.size();
	totalTokensScored.value += activeList.size();

	return moreTokens;

    }


    /**
     * Keeps track of and reports all of the active word histories for
     * the given active list
     *
     * @param activeList the activelist to track
     */
    private void monitorWords(ActiveList activeList) {
        WordTracker tracker = new WordTracker(currentFrameNumber);

        for (Iterator i = activeList.iterator(); i.hasNext(); ) {
            Token t = (Token) i.next();
            tracker.add(t);
        }
        tracker.dump();
    }


    /**
     * Keeps track of and reports statistics about the number of
     * active states
     *
     * @param activeList the active list of states
     */
    private void monitorStates(ActiveList activeList) {

        tokenSum += activeList.size();
        tokenCount++;

        if ((tokenCount % 1000) == 0) {
            System.out.println("   Average Tokens/State: " +
                (tokenSum / tokenCount));
        }
    }



    /**
     * Removes unpromising branches from the active list
     *
     */
    protected void pruneBranches() {
        pruneTimer.start();
        activeList =  pruner.prune(activeList);
        pruneTimer.stop();
    }

    /**
     * Gets the best token for this state
     *
     * @param state the state of interest
     *
     * @return the best token
     */
    protected Token getBestToken(SearchState state) {
        Object key = getStateKey(state);

        if (!wantTokenStacks) {
            return (Token) bestTokenMap.get(key);
        } else {
            // new way... if the heap for this state isn't full return 
            // null, otherwise return the worst scoring token
            TokenHeap th = (TokenHeap) bestTokenMap.get(key);
            Token t;

            if (th == null) {
                return null;
            } else if ((t = th.get(state)) != null) {
                return t;
            } else if (!th.isFull()) {
                return null;
            } else {
                return th.getSmallest();
            }
        }
    }

    /**
     * Sets the best token for a given state
     *
     * @param token the best token
     *
     * @param state the state
     *
     * @return the previous best token for the given state, or null if
     *    no previous best token
     */
    protected void setBestToken(Token token, SearchState state) {
        Object key = getStateKey(state);
        if (!wantTokenStacks) {
            bestTokenMap.put(key, token);
        } else {
            TokenHeap th = (TokenHeap) bestTokenMap.get(key);
            if (th == null) {
                th = new TokenHeap(maxTokenHeapSize);
                bestTokenMap.put(key, th);
            }
            th.add(token);
        }
    }



    /**
     * Find the best token to use as a predecessor token given a
     * candidate predecessor. The predecessor is the most recent word
     * token unless keepAllTokens is set, in which case, the
     * predecessor is always the candidate predecessor.
     *
     * @param token the token of interest
     *
     * @return the immediate successor word token
     */
    protected Token getWordPredecessor(Token token) {
        if (keepAllTokens) {    
            return token; 
        } else {
            while (token != null && !token.isWord()) {
                token = token.getPredecessor();
            }
        }
        return token;
    }


    /**
     * Returns the state key for the given state. If we are using
     * token stacks then the key will be related to the search state
     * and the word history (depending on the type of token stacks),
     * otherwise, the key will simply be the state itself.  Currently
     * we are not using token stacks so the state is the key.
     *
     * @param state the state to get the key for
     *
     * @return the key for the given state
     */
    private Object getStateKey(SearchState state) {
        if (!wantTokenStacks) {
            return state;
        } else {
            if (state.isEmitting()) {
                return new  SinglePathThroughHMMKey(((HMMSearchState) state));
                // return ((HMMSearchState) state).getHMMState().getHMM();
            } else {
                 return state;
            }
        }
    }

    /**
     * Checks that the given two states are in legitimate order.
     */
    private void checkStateOrder(SearchState fromState, SearchState toState) {
        Class fromClass = fromState.getClass();
        Class toClass = toState.getClass();

        // first, find where in stateOrder is the fromState
        int i = 0;
        for (; i < stateOrder.length; i++) {
            if (stateOrder[i] == fromClass) {
                break;
            }
        }

        // We are assuming that the last state in the state order
        // is an emitting state. We assume that emitting states can
        // expand to any state type. So if (i == (stateOrder.length)),
        // which means that fromState is an emitting state, we don't
        // do any checks.

        if (i < (stateOrder.length - 1)) {
            for (int j = 0; j <= i; j++) {
                if (stateOrder[j] == toClass) {
                    throw new Error("IllegalState order: from " + 
                                    fromState.getClass().getName() + " " +
                                    fromState.toPrettyString() + " to " + 
                                    toState.getClass().getName() + " " +
                                    toState.toPrettyString());
                }
            }
        }
    }

    /**
     * Collects the next set of emitting tokens from a token
     * and accumulates them in the active or result lists
     *
     * @param token  the token to collect successors from
     * be immediately expaned are placed. Null if we should always
     * expand all nodes.
     *
     */
    protected void collectSuccessorTokens(Token token) {

        tokenTracker.add(token);

        // If this is a final state, add it to the final list

        if (token.isFinal()) {
            resultList.add(getWordPredecessor(token));
            return;
        }

        SearchState state = token.getSearchState();
        SearchStateArc[] arcs = state.getSuccessors();
        Token predecessor = getWordPredecessor(token);
        
        // For each successor
        // calculate the entry score for the token based upon the
        // predecessor token score and the transition probabilities
        // if the score is better than the best score encountered for
        // the SearchState and frame then create a new token, add
        // it to the lattice and the SearchState.
        // If the token is an emitting token add it to the list,
        // othewise recursively collect the new tokens successors.

        for (int i = 0; i < arcs.length; i++) {
            SearchStateArc arc = arcs[i];
            SearchState nextState = arc.getState();

            if (checkStateOrder) {
                checkStateOrder(state, nextState);
            }

            // We're actually multiplying the variables, but since
            // these come in log(), multiply gets converted to add
            float logEntryScore = token.getScore() +  arc.getProbability();


            Token bestToken = getBestToken(nextState);
            boolean firstToken = bestToken == null ;

            if (firstToken || bestToken.getScore() < logEntryScore) {
                Token newBestToken = new Token(predecessor,
                                               nextState,
                                               logEntryScore,
                                               arc.getLanguageProbability(),
                                               arc.getInsertionProbability(),
                                               currentFrameNumber);
                tokensCreated.value++;

                setBestToken(newBestToken, nextState);
                if (firstToken) {
		    activeBucket.add(newBestToken);
                } else {
                    if (false) {
                        System.out.println("Replacing " + bestToken + " with " 
                                + newBestToken);
                    }
		    activeBucket.replace(bestToken, newBestToken);
		    if (buildWordLattice && newBestToken.isWord()) {
                        
                        // Move predecessors of bestToken to precede 
                        // newBestToken, bestToken will be garbage collected.
                        loserManager.changeSuccessor(newBestToken,bestToken);
                        loserManager.addAlternatePredecessor
                            (newBestToken,bestToken.getPredecessor());
                    }
                }
            } else {
                if (buildWordLattice && 
                    nextState instanceof WordSearchState)  {
                    if (predecessor != null) {
                        loserManager.addAlternatePredecessor
                            (bestToken, predecessor);
                    }
                }
            }
        }
    }


    // FRAME Skew
    // this is a set of experimental code used to test out a frame
    // skew algorithm. This code is currently disabled.

    private final static int SKEW = 0;

    /**
     * Apply frame skew. Determine if the given token should be
     * expanded based upon frame skew
     *
     * @param t the token to test
     *
     * @return <code>true</code> if the token should be expanded
     */
    private boolean skewPrune(Token t) {
        return true;    // currently disabled
       // return skewPruneWord(t);
    }

    /**
     * Frame skew based on HMM states
     *
     * @param t the token to test
     *
     * @return <code>true</code> if the token should be expanded
     */
    private boolean skewPruneHMM(Token t) {
        boolean keep = true;
        SearchState ss = t.getSearchState();
        if (SKEW > 0 && ss instanceof HMMSearchState) {
            if (!t.isEmitting()) {
                // HMMSearchState hss = (HMMSearchState) ss;
                Token lastToken = (Token) skewMap.get(ss);
                if (lastToken != null) {
                    int lastFrame = lastToken.getFrameNumber();
                    if (t.getFrameNumber() - lastFrame > SKEW ||
                            t.getScore() > lastToken.getScore()) {
                        keep = true;
                    } else {
                        if (false) {
                            System.out.println("Dropped " 
                                    + t + " in favor of " + lastToken);
                        }
                        keep = false;
                    }

                } else {
                    keep = true;
                }

                if (keep) {
                    skewMap.put(ss, t);
                }
            }
        }
        return keep;
    }

    /**
     * Frame skew based on word states
     *
     * @param t the token to test
     *
     * @return <code>true</code> if the token should be expanded
     */
    private boolean skewPruneWord(Token t) {
        boolean keep = true;
        SearchState ss = t.getSearchState();
        if (SKEW > 0 && ss instanceof WordSearchState) {
            Token lastToken = (Token) skewMap.get(ss);
            if (lastToken != null) {
                int lastFrame = lastToken.getFrameNumber();
                if (t.getFrameNumber() - lastFrame > SKEW ||
                        t.getScore() > lastToken.getScore()) {
                    keep = true;
                } else {
                    if (false) {
                        System.out.println("Dropped " 
                                + t + " in favor of " + lastToken);
                    }
                    keep = false;
                }

            } else {
                keep = true;
            }

            if (keep) {
                skewMap.put(ss, t);
            }
        }
        return keep;
    }

    /**
     * Counts all the tokens in the active list (and displays them).
     * This is an expensive operation.
     */
    private void showTokenCount() {
        Set tokenSet = new HashSet();

        for (Iterator i = activeList.iterator(); i.hasNext(); ) {
            Token token = (Token) i.next();
            while (token != null) {
                tokenSet.add(token);
                token = token.getPredecessor();
            }
        }

        System.out.println("Token Lattice size: " + tokenSet.size());

        tokenSet = new HashSet();

        for (Iterator i = resultList.iterator(); i.hasNext(); ) {
            Token token = (Token) i.next();
            while (token != null) {
                tokenSet.add(token);
                token = token.getPredecessor();
            }
        }

        System.out.println("Result Lattice size: " + tokenSet.size());
    }


    /**
     * Returns the Linguist.
     *
     * @return the Linguist
     */
    public Linguist getLinguist() {
        return linguist;
    }


    /**
     * Returns the Pruner.
     *
     * @return the Pruner
     */
    public Pruner getPruner() {
        return pruner;
    }


    /**
     * Returns the AcousticScorer.
     *
     * @return the AcousticScorer
     */
    public AcousticScorer getAcousticScorer() {
        return scorer;
    }


    /**
     * Returns the LogMath used.
     *
     * @return the LogMath used
     */
    public LogMath getLogMath() {
        return logMath;
    }


    /**
     * Returns the SphinxProperties used.
     *
     * @return the SphinxProperties used
     */
    public SphinxProperties getSphinxProperties() {
        return props;
    }


    /**
     * Returns the best token map.
     *
     * @return the best token map
     */
    protected Map getBestTokenMap() {
        return bestTokenMap;
    }


    /**
     * Sets the best token Map.
     *
     * @param bestTokenMap the new best token Map
     */
    protected void setBestTokenMap(Map bestTokenMap) {
        this.bestTokenMap = bestTokenMap;
    }


    /**
     * Returns the ActiveList.
     *
     * @return the ActiveList
     */
    public ActiveList getActiveList() {
        return activeList;
    }


    /**
     * Sets the ActiveList.
     *
     * @param activeList the new ActiveList
     */
    public void setActiveList(ActiveList activeList) {
        this.activeList = activeList;
    }


    /**
     * Returns the result list.
     *
     * @return the result list
     */
    public List getResultList() {
        return resultList;
    }


    /**
     * Sets the result list.
     *
     * @param resultList the new result list
     */
    public void setResultList(List resultList) {
        this.resultList = resultList;
    }


    /**
     * Returns the current frame number.
     *
     * @return the current frame number
     */
    public int getCurrentFrameNumber() {
        return currentFrameNumber;
    }


    /**
     * Returns the Timer for growing.
     *
     * @return the Timer for growing
     */
    public Timer getGrowTimer() {
        return growTimer;
    }


    /**
     * Returns the tokensCreated StatisticsVariable.
     *
     * @return the tokensCreated StatisticsVariable.
     */
    public StatisticsVariable getTokensCreated() {
        return tokensCreated;
    }
}
// ---------------------------------------------------------
// Experimental code
// ---------------------------------------------------------
// There's some experimental code here used to track tokens
// and word beams.
/**
 * A 'best token' key. This key will allow hmm states that have
 * identical word histories and are in the same HMM state to be
 * treated equivalently. When used as the best token key, only the
 * best scoring token with a given word history survives per HMM.
 */
class SinglePathThroughHMMKey  {
    private HMMSearchState hmmSearchState;

    public SinglePathThroughHMMKey(HMMSearchState hmmSearchState) {
        this.hmmSearchState = hmmSearchState;
    }

    public int hashCode() {
        return hmmSearchState.getLexState().hashCode() * 13 +
               hmmSearchState.getWordHistory().hashCode();
    }

    public boolean equals(Object o) {
        if (this == o) {
            return true;
        } else if (o instanceof SinglePathThroughHMMKey) {
            SinglePathThroughHMMKey other = (SinglePathThroughHMMKey) o;
            boolean equal = hmmSearchState.getLexState().equals(
                    other.hmmSearchState.getLexState()) &&
                hmmSearchState.getWordHistory().equals(
                        other.hmmSearchState.getWordHistory());
            if (equal && false) {
                System.out.println("SPTHK A: " + hmmSearchState);
                System.out.println("SPTHK B: " + other.hmmSearchState);
            }
            return equal;
        }
        return false;
    }
}


/**
 * A quick and dirty token heap that allows us to perform token stack
 * experiments. It is not very efficient. We will likely replace this
 * with something better once we figure out how we want to prune
 * things.
 */
class TokenHeap {
    Token[] tokens;
    int curSize = 0;

    /**
     * Creates a token heap with the maximum size
     *
     * @param maxSize the maximum size of the heap
     */
    TokenHeap(int maxSize) {
        tokens = new Token[maxSize];
    }

    /**
     * Adds a token to the heap
     *
     * @param token the token to add
     */
    void add(Token token) {
        // first, if an identical state exists, replace
        // it.

        if (!tryReplace(token)) {
            if (curSize < tokens.length) {
                tokens[curSize++] = token;
            } else if (token.getScore() > tokens[curSize - 1].getScore()) {
                tokens[curSize - 1] = token;
            }
        }
        fixupInsert();
    }

    /**
     * Returns the smallest scoring token on the heap
     *
     * @return the smallest scoring token
     */
    Token getSmallest() {
        if (curSize == 0) {
            return null;
        } else {
            return tokens[curSize - 1];
        }
    }

    /**
     * Determines if the heap is ful
     *
     * @return <code>true</code> if the heap is full
     */
    boolean isFull() {
        return curSize == tokens.length;
    }

    /**
     * Checks to see if there is already a token t on the heap that
     * has the same search state. If so, this token replaces that one
     *
     * @param t the token to try to add to the heap
     *
     * @return <code>true</code> if the token was added
     */
    private boolean tryReplace(Token t) {
        for (int i = 0; i < curSize; i++) {
            if (t.getSearchState().equals(tokens[i].getSearchState())) {
                assert t.getScore() > tokens[i].getScore();
                tokens[i] = t;
                return true;
            }
        }
        return false;
    }


    /**
     * Orders the heap after an insert
     */
    private void fixupInsert() {
        Arrays.sort(tokens, 0, curSize - 1, Token.COMPARATOR);
    }

    /**
     * returns a token on the heap that matches the given search state
     *
     * @param s the search state
     *
     * @return the token that matches, or null
     */
    Token  get(SearchState s) {
        for (int i = 0; i < curSize; i++) {
            if (tokens[i].getSearchState().equals(s)) {
                return tokens[i];
            }
        }
        return null;
    }
}


/**
 * A class that keeps track of word histories 
 */
class WordTracker {
    Map statMap;
    int frameNumber;
    int stateCount;
    int maxWordHistories;

    /**
     * Creates a word tracker for the given frame number
     *
     * @param frameNumber the frame number
     */
    WordTracker(int frameNumber) {
        statMap = new HashMap();
        this.frameNumber = frameNumber;
    }

    /**
     * Adds a word history for the given token to the word tracker
     *
     * @param t the token to add
     */
    void add(Token t) {
        stateCount++;
        WordSequence ws = getWordSequence(t);
        WordStats stats = (WordStats) statMap.get(ws);
        if (stats == null) {
            stats = new WordStats(ws);
            statMap.put(ws, stats);
        }
        stats.update(t);
    }

    /**
     * Dumps the word histories in the tracker
     */
    void dump() {
        dumpSummary();
        Object[] stats = statMap.values().toArray();
        Arrays.sort(stats, WordStats.COMPARATOR);
        for (int i = 0; i < stats.length; i++) {
            System.out.println("   " + stats[i]);
        }
    }


    /**
     * Dumps summary information in the tracker
     */
    void dumpSummary() {
        System.out.println("Frame: " + frameNumber 
                + " states: " + stateCount + " histories " + statMap.size());
    }

    /**
     * Given a token, gets the word sequence represented by the token
     *
     * @param token the token of interest
     *
     * @return the word sequence for the token
     */
    private WordSequence getWordSequence(Token token) {
        List wordList = new LinkedList();

        while (token != null) {
            if (token.isWord()) {
                WordSearchState wordState =
                        (WordSearchState) token.getSearchState();
                Word word = wordState.getPronunciation().getWord();
                wordList.add(0, word);
            }
            token = token.getPredecessor();
        }
        return WordSequence.getWordSequence(wordList);
    }
}

/**
 * Keeps track of statistics for a particular word sequence
 */
class WordStats {
    public final static Comparator COMPARATOR = new Comparator() {
	    public int compare(Object o1, Object o2) {
		WordStats ws1 = (WordStats) o1;
		WordStats ws2 = (WordStats) o2;

		if (ws1.maxScore > ws2.maxScore) {
		    return -1;
		} else if (ws1.maxScore ==  ws2.maxScore) {
		    return 0;
		} else {
		    return 1;
		}
	    }
	};

    private int size;
    private float maxScore;
    private float minScore;
    private WordSequence ws;

    /**
     * Creates a word stat for the given sequence
     *
     * @param ws the word sequence
     */
    WordStats(WordSequence ws) {
        size = 0;
        maxScore = -Float.MAX_VALUE;
        minScore = Float.MAX_VALUE;
        this.ws = ws;
    }

    /**
     * Updates the stats based upon the scores for the given token
     *
     * @param t the token
     */
    void update(Token t) {
        size++;
        if (t.getScore() > maxScore) {
            maxScore = t.getScore();
        }
        if (t.getScore() < minScore) {
            minScore = t.getScore();
        }
    }

    /**
     * Returns a string representation of the stats
     *
     * @return a string representation
     */
    public String toString() {
        return "states:" + size + " max:" + maxScore + " min:" + minScore +
            " " + ws;
    }
}

/**
 * This debugging class is used to track the number of active tokens
 * per state
 */
class TokenTracker {
    private Map stateMap;
    private boolean enabled;
    private int frame = 0;

    private int utteranceStateCount;
    private int utteranceMaxStates;
    private int utteranceSumStates;

    /**
     * Enables or disables the token tracker
     *
     * @param enabled if <code>true</code> the tracker is enabled
     */
    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Starts the per-utterance tracking
     */
    void startUtterance() {
        if (enabled) {
            frame = 0;
            utteranceStateCount = 0;
            utteranceMaxStates = -Integer.MAX_VALUE;
            utteranceSumStates = 0;
        }
    }

    /**
     * stops the per-utterance tracking
     */
    void stopUtterance() {
        if (enabled) {
            dumpSummary();
        }
    }

    /**
     * Starts the per-frame tracking
     */
    void startFrame() {
        if (enabled) {
            stateMap = new HashMap();
        }
    }

    /**
     * Adds a new token to the tracker
     *
     * @param t the token to add.
     */
    void add(Token t) {
        if (enabled) {
            TokenStats stats = getStats(t);
            stats.update(t);
        }
    }

    /**
     * Stops the per-frame tracking
     */
    void stopFrame() {
        if (enabled) {
            frame++;
            dumpDetails();
        }
    }

    /**
     * Dumps summary info about the tokens
     */
    void dumpSummary() {
        if (enabled) {
            float avgStates = 0f;
            if (utteranceStateCount > 0 ) {
                avgStates = ((float) utteranceSumStates) /
                    utteranceStateCount;
            } 
            System.out.print("# Utterance stats " );
            System.out.print(" States: " + utteranceStateCount / frame);

            if (utteranceStateCount > 0) {
                System.out.print(" Paths: " + utteranceSumStates / frame);
                System.out.print(" Max: " + utteranceMaxStates);
                System.out.print(" Avg: " + avgStates);
            }

            System.out.println();
        }
    }

    /**
     * Dumps detailed info about the tokens
     */
    void dumpDetails() {
        if (enabled) {
            int maxStates = -Integer.MAX_VALUE;
            int hmmCount = 0;
            int sumStates = 0;

            for (Iterator i = stateMap.values().iterator(); i.hasNext(); ) {
                TokenStats stats = (TokenStats) i.next();
                if (stats.isHMM) {
                    hmmCount++;
                }
                sumStates += stats.count;
                utteranceSumStates += stats.count;
                if (stats.count > maxStates) {
                    maxStates = stats.count;
                }

                if (stats.count > utteranceMaxStates) {
                    utteranceMaxStates = stats.count;
                }
            }

            utteranceStateCount += stateMap.size();

            float avgStates = 0f;
            if (stateMap.size() >0 ) {
                avgStates = ((float) sumStates) / stateMap.size();
            } 
            System.out.print("# Frame " + frame);
            System.out.print(" States: " + stateMap.size());


            if (stateMap.size() > 0) {
                System.out.print(" Paths: " + sumStates);
                System.out.print(" Max: " + maxStates);
                System.out.print(" Avg: " + avgStates);
                System.out.print(" HMM: " + hmmCount);
            }

            System.out.println();
        }
    }

    /**
     * Gets the stats for a particular token
     *
     * @param t the token of interest
     *
     * @return the token stats associated with the given token
     */
    private TokenStats getStats(Token t) {
        TokenStats stats = (TokenStats)
            stateMap.get(t.getSearchState().getLexState());
        if (stats == null) {
            stats = new TokenStats();
            stateMap.put(t.getSearchState().getLexState(), stats);
        }
        return stats;
    }
}

/**
 * A class for keeing track of statistics about tokens. Tracks the
 * count, min and max score for a particular state.
 */
class TokenStats {
    int count;
    float maxScore;
    float minScore;
    boolean isHMM;

    TokenStats() {
        count = 0;
        maxScore = -Float.MAX_VALUE;
        minScore = Float.MIN_VALUE;
    }

    /**
     * Update this state with the given token
     */
    public void update(Token t) {
        count++;
        if (t.getScore() > maxScore) {
            maxScore = t.getScore();
        }

        if (t.getScore() < minScore) {
            minScore = t.getScore();
        }

        isHMM = t.getSearchState() instanceof HMMSearchState;
    }
}
