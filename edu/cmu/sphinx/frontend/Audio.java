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


package edu.cmu.sphinx.frontend;

/**
 * Represents an audio data frame of type double.
 */
public class Audio extends Data implements Cloneable {

    private double[] audioSamples;


    /**
     * Constructs an Audio object with the given audio data.
     *
     * @param audioSamples the audio samples for this Audio
     */
    public Audio(double[] audioSamples) {
	this.audioSamples = audioSamples;
    }


    /**
     * Constructs an Audio object with the given audio data and Utterance.
     *
     * @param audioSamples the audio samples for this Audio
     * @param utterance the Utterance associated with this Audio
     */
    public Audio(double[] audioSamples, Utterance utterance) {
        super(utterance);
	this.audioSamples = audioSamples;
    }


    /**
     * Constructs an Audio object with the given Signal.
     *
     * @param signal the Signal this Audio object carries
     */
    public Audio(Signal signal) {
        super(signal);
    }

    
    /**
     * Returns the audio samples.
     *
     * @return the audio samples
     */
    public double[] getSamples() {
	return audioSamples;
    }


    /**
     * Returns a string representation of this Audio.
     * The format of the string is:
     * <pre>audioFrameLength data0 data1 ...</pre>
     *
     * @return a string representation of this Audio
     */
    public String toString() {
        return Util.doubleArrayToString(audioSamples);
    }


    /**
     * Returns a duplicate of this Audio object.
     *
     * @return a duplicate of this Audio object
     */
    public Object clone() {
	if (hasContent()) {
	    double[] newSamples = (double[]) audioSamples.clone();
	    return (new Audio(newSamples, getUtterance()));
	} else {
	    return (new Audio(getSignal()));
	}
    }
}
